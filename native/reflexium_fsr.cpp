// Small C ABI over AMD's signed FidelityFX Vulkan API (SDK 1.1.4 / FSR 3.1.4).
#include <ffx_api/ffx_upscale.h>
#include <ffx_api/ffx_framegeneration.h>
#include <ffx_api/vk/ffx_api_vk.h>
#include "frame_constants.h"
#include <cstdint>
#include <cstring>
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
ffxContext fgContext = nullptr;
ffxCreateBackendVKDesc fgBackend = {};
ffxCreateContextDescFrameGeneration fgDescription = {};
}

struct RxImage {
    uint64_t image, view;
    int32_t format, width, height, aspect;
};
static_assert(sizeof(RxImage) == 32);
RX_EXPORT void rx_fsr_sc_pause();

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

RX_EXPORT void rx_fsr_fg_release() {
    rx_fsr_sc_pause();
    if (fgContext) { ffxDestroyContext(&fgContext, nullptr); fgContext = nullptr; }
}

RX_EXPORT int rx_fsr_fg_create(void* device, void* physicalDevice, void* gdpa,
        uint32_t renderWidth, uint32_t renderHeight, uint32_t displayWidth, uint32_t displayHeight) {
    if (!device || !physicalDevice || !gdpa || !renderWidth || !renderHeight || !displayWidth || !displayHeight) return -1;
    rx_fsr_fg_release();
    realDeviceProcAddr = reinterpret_cast<PFN_vkGetDeviceProcAddr>(gdpa);
    fgBackend = {};
    fgBackend.header.type = FFX_API_CREATE_CONTEXT_DESC_TYPE_BACKEND_VK;
    fgBackend.vkDevice = static_cast<VkDevice>(device);
    fgBackend.vkPhysicalDevice = static_cast<VkPhysicalDevice>(physicalDevice);
    fgBackend.vkDeviceProcAddr = deviceProcAddr;
    fgDescription = {};
    fgDescription.header.type = FFX_API_CREATE_CONTEXT_DESC_TYPE_FRAMEGENERATION;
    fgDescription.header.pNext = &fgBackend.header;
    fgDescription.flags = FFX_FRAMEGENERATION_ENABLE_DEPTH_INVERTED;
    fgDescription.displaySize = {displayWidth, displayHeight};
    fgDescription.maxRenderSize = {renderWidth, renderHeight};
    fgDescription.backBufferFormat = FFX_API_SURFACE_FORMAT_R8G8B8A8_UNORM;
    return ffxCreateContext(&fgContext, &fgDescription.header, nullptr);
}

RX_EXPORT int rx_fsr_fg_evaluate(void* commands, const RxImage* color, const RxImage* depth,
        const RxImage* motion, const RxImage* output, const RxFrameConstants* camera, uint64_t frameId, int reset) {
    if (!fgContext || !commands || !color || !depth || !motion || !output || !camera) return -1;
    ffxConfigureDescFrameGeneration config = {};
    config.header.type = FFX_API_CONFIGURE_DESC_TYPE_FRAMEGENERATION;
    config.frameGenerationEnabled = true;
    config.flags = FFX_FRAMEGENERATION_FLAG_NO_SWAPCHAIN_CONTEXT_NOTIFY;
    config.generationRect = {0, 0, output->width, output->height};
    config.frameID = frameId;
    auto result = ffxConfigure(&fgContext, &config.header);
    if (result != FFX_API_RETURN_OK) return result;
    ffxDispatchDescFrameGenerationPrepareCameraInfo vectors = {};
    vectors.header.type = FFX_API_DISPATCH_DESC_TYPE_FRAMEGENERATION_PREPARE_CAMERAINFO;
    std::memcpy(vectors.cameraPosition, camera->position, sizeof(camera->position));
    std::memcpy(vectors.cameraUp, camera->up, sizeof(camera->up));
    std::memcpy(vectors.cameraRight, camera->right, sizeof(camera->right));
    std::memcpy(vectors.cameraForward, camera->forward, sizeof(camera->forward));
    ffxDispatchDescFrameGenerationPrepare prepare = {};
    prepare.header.type = FFX_API_DISPATCH_DESC_TYPE_FRAMEGENERATION_PREPARE;
    prepare.header.pNext = &vectors.header;
    prepare.frameID = frameId;
    prepare.commandList = commands;
    prepare.renderSize = {static_cast<uint32_t>(motion->width), static_cast<uint32_t>(motion->height)};
    prepare.jitterOffset = {camera->jitter[0], camera->jitter[1]};
    prepare.motionVectorScale = {1.0f, 1.0f};
    prepare.frameTimeDelta = camera->frameTimeMs;
    prepare.cameraNear = camera->farPlane; // reverse-Z convention, as in FSR upscaling
    prepare.cameraFar = camera->nearPlane;
    prepare.cameraFovAngleVertical = camera->verticalFov;
    prepare.viewSpaceToMetersFactor = 1.0f;
    prepare.depth = resource(depth, false);
    prepare.motionVectors = resource(motion, false);
    result = ffxDispatch(&fgContext, &prepare.header);
    if (result != FFX_API_RETURN_OK) return result;
    ffxDispatchDescFrameGeneration dispatch = {};
    dispatch.header.type = FFX_API_DISPATCH_DESC_TYPE_FRAMEGENERATION;
    dispatch.commandList = commands;
    dispatch.presentColor = resource(color, false);
    dispatch.outputs[0] = resource(output, true);
    dispatch.numGeneratedFrames = 1;
    dispatch.reset = reset != 0;
    dispatch.backbufferTransferFunction = FFX_API_BACKBUFFER_TRANSFER_FUNCTION_SRGB;
    dispatch.minMaxLuminance[1] = 1.0f;
    dispatch.generationRect = config.generationRect;
    dispatch.frameID = frameId;
    return ffxDispatch(&fgContext, &dispatch.header);
}

#include "fsr_swapchain.h"
