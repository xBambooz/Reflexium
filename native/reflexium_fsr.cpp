// Small C ABI over AMD's signed FidelityFX Vulkan API (SDK 1.1.4 / FSR 3.1.4).
#include <ffx_api/ffx_upscale.h>
#include <ffx_api/vk/ffx_api_vk.h>
#include <cstdint>
#include <string>

#define RX_EXPORT extern "C" __declspec(dllexport)

namespace {
ffxContext context = nullptr;
PFN_vkGetDeviceProcAddr realDeviceProcAddr = nullptr;

// FFX asks for some promoted functions by their KHR alias. On a Vulkan 1.1+ device that alias only
// resolves when the KHR extension is enabled, which Minecraft does not do, so fall back to the core name.
VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL deviceProcAddr(VkDevice device, const char* name) {
    PFN_vkVoidFunction fn = realDeviceProcAddr(device, name);
    if (fn == nullptr) {
        std::string core(name);
        for (const char* suffix : {"KHR", "EXT"}) {
            if (core.size() > 3 && core.compare(core.size() - 3, 3, suffix) == 0) {
                fn = realDeviceProcAddr(device, core.substr(0, core.size() - 3).c_str());
                break;
            }
        }
    }
    return fn;
}
ffxCreateBackendVKDesc backend = {};
ffxCreateContextDescUpscale description = {};
}

struct RxImage {
    uint64_t image, view;
    int32_t format, width, height, aspect;
};
static_assert(sizeof(RxImage) == 32);

static FfxApiResource resource(const RxImage* image, bool output) {
    VkImageCreateInfo info = {};
    info.imageType = VK_IMAGE_TYPE_2D;
    info.format = static_cast<VkFormat>(image->format);
    info.extent = {static_cast<uint32_t>(image->width), static_cast<uint32_t>(image->height), 1};
    info.mipLevels = info.arrayLayers = 1;
    info.usage = output ? VK_IMAGE_USAGE_STORAGE_BIT : VK_IMAGE_USAGE_SAMPLED_BIT;
    auto desc = ffxApiGetImageResourceDescriptionVK(reinterpret_cast<VkImage>(image->image), info, 0);
    // Minecraft keeps images in GENERAL. FFX restores registered resources to their initial state.
    return ffxApiGetResourceVK(reinterpret_cast<void*>(image->image), desc,
        output ? FFX_API_RESOURCE_STATE_UNORDERED_ACCESS : FFX_API_RESOURCE_STATE_GENERIC_READ);
}

RX_EXPORT void rx_fsr_release() {
    if (context) {
        ffxDestroyContext(&context, nullptr);
        context = nullptr;
    }
}

RX_EXPORT int rx_fsr_create(void* device, void* physicalDevice, void* gdpa,
        uint32_t renderWidth, uint32_t renderHeight, uint32_t displayWidth, uint32_t displayHeight) {
    rx_fsr_release();
    backend = {};
    backend.header.type = FFX_API_CREATE_CONTEXT_DESC_TYPE_BACKEND_VK;
    backend.vkDevice = static_cast<VkDevice>(device);
    backend.vkPhysicalDevice = static_cast<VkPhysicalDevice>(physicalDevice);
    realDeviceProcAddr = reinterpret_cast<PFN_vkGetDeviceProcAddr>(gdpa);
    backend.vkDeviceProcAddr = deviceProcAddr;
    description = {};
    description.header.type = FFX_API_CREATE_CONTEXT_DESC_TYPE_UPSCALE;
    description.header.pNext = &backend.header;
    description.flags = FFX_UPSCALE_ENABLE_DEPTH_INVERTED | FFX_UPSCALE_ENABLE_AUTO_EXPOSURE;
    description.maxRenderSize = {renderWidth, renderHeight};
    description.maxUpscaleSize = {displayWidth, displayHeight};
    return ffxCreateContext(&context, &description.header, nullptr);
}

RX_EXPORT int rx_fsr_evaluate(void* commandBuffer, const RxImage* color, const RxImage* depth,
        const RxImage* motion, const RxImage* output, const RxImage* reactive, float jitterX, float jitterY,
        int reset, float frameTimeMs, float nearPlane, float farPlane, float fov, float sharpness) {
    if (!context) return -1;
    ffxDispatchDescUpscale dispatch = {};
    dispatch.header.type = FFX_API_DISPATCH_DESC_TYPE_UPSCALE;
    dispatch.commandList = commandBuffer;
    dispatch.color = resource(color, false);
    dispatch.depth = resource(depth, false);
    dispatch.motionVectors = resource(motion, false);
    dispatch.output = resource(output, true);
    if (reactive) dispatch.reactive = resource(reactive, false);
    dispatch.jitterOffset = {jitterX, jitterY};
    dispatch.motionVectorScale = {1.0f, 1.0f};
    dispatch.renderSize = {static_cast<uint32_t>(color->width), static_cast<uint32_t>(color->height)};
    dispatch.upscaleSize = {static_cast<uint32_t>(output->width), static_cast<uint32_t>(output->height)};
    dispatch.enableSharpening = sharpness > 0;
    dispatch.sharpness = sharpness;
    dispatch.frameTimeDelta = frameTimeMs;
    dispatch.preExposure = 1.0f;
    dispatch.reset = reset != 0;
    // With inverted depth, FSR requires far distance in cameraNear and near distance in cameraFar.
    dispatch.cameraNear = farPlane;
    dispatch.cameraFar = nearPlane;
    dispatch.cameraFovAngleVertical = fov;
    dispatch.viewSpaceToMetersFactor = 1.0f;
    return ffxDispatch(&context, &dispatch.header);
}

// AMD's opaque/pre-upscale comparison. Run before hand/post effects so the two inputs share exposure.
RX_EXPORT int rx_fsr_generate_reactive(void* commandBuffer, const RxImage* opaque,
        const RxImage* color, const RxImage* reactive) {
    if (!context) return -1;
    ffxDispatchDescUpscaleGenerateReactiveMask dispatch = {};
    dispatch.header.type = FFX_API_DISPATCH_DESC_TYPE_UPSCALE_GENERATEREACTIVEMASK;
    dispatch.commandList = commandBuffer;
    dispatch.colorOpaqueOnly = resource(opaque, false);
    dispatch.colorPreUpscale = resource(color, false);
    dispatch.outReactive = resource(reactive, true);
    dispatch.renderSize = {static_cast<uint32_t>(color->width), static_cast<uint32_t>(color->height)};
    dispatch.scale = 1.0f;
    dispatch.cutoffThreshold = 0.2f;
    dispatch.binaryValue = 0.9f;
    // Tonemap/inverse-tonemap flags are unnecessary for Minecraft's LDR color target.
    dispatch.flags = FFX_UPSCALE_AUTOREACTIVEFLAGS_APPLY_THRESHOLD | FFX_UPSCALE_AUTOREACTIVEFLAGS_USE_COMPONENTS_MAX;
    return ffxDispatch(&context, &dispatch.header);
}
