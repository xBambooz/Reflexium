// Reflexium NGX bridge: a small C ABI over NVIDIA's NGX SDK so the Java side can drive DLSS Super
// Resolution on Minecraft's own Vulkan device through the Foreign Function API.
//
// NGX cannot be called through the driver's _nvngx.dll exports directly (it crashes inside the
// driver); the static nvsdk_ngx library linked here supplies the state those exports expect.
//
// This software contains source code provided by NVIDIA Corporation (the NGX helper headers).

#include <vulkan/vulkan.h>

#include "nvsdk_ngx_vk.h"
#include "nvsdk_ngx_helpers.h"
#include "nvsdk_ngx_helpers_vk.h"

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>

#define RX_EXPORT extern "C" __declspec(dllexport)

namespace {

// Picked once for Reflexium; NGX only needs it to be stable and unique per application.
constexpr const char* kProjectId = "4f6b2a0e-6d51-4c8a-9a7e-7c1f3d2b9e15";

VkDevice g_device = VK_NULL_HANDLE;
NVSDK_NGX_Parameter* g_params = nullptr;
NVSDK_NGX_Handle* g_dlss = nullptr;
bool g_initialized = false;

std::mutex g_logMutex;
std::string g_log;

void NVSDK_CONV logCallback(const char* message, NVSDK_NGX_Logging_Level, NVSDK_NGX_Feature) {
	std::lock_guard<std::mutex> lock(g_logMutex);
	if (g_log.size() < 64 * 1024) {
		g_log += message;
		if (!g_log.empty() && g_log.back() != '\n') {
			g_log += '\n';
		}
	}
}

int copyOut(const std::string& text, char* out, int outSize) {
	if (out == nullptr || outSize <= 0) {
		return static_cast<int>(text.size());
	}
	int n = static_cast<int>(text.size()) < outSize - 1 ? static_cast<int>(text.size()) : outSize - 1;
	std::memcpy(out, text.data(), n);
	out[n] = '\0';
	return n;
}

} // namespace

// Image handed over from Java. Layout must match RxImage in NgxBridge.java (32 bytes).
struct RxImage {
	uint64_t image;
	uint64_t view;
	int32_t format;
	int32_t width;
	int32_t height;
	int32_t aspect;
};
static_assert(sizeof(RxImage) == 32, "RxImage must match the Java FFM layout");

static NVSDK_NGX_Resource_VK toResource(const RxImage* img, bool readWrite) {
	VkImageSubresourceRange range = {};
	range.aspectMask = static_cast<VkImageAspectFlags>(img->aspect);
	range.baseMipLevel = 0;
	range.levelCount = 1;
	range.baseArrayLayer = 0;
	range.layerCount = 1;
	return NVSDK_NGX_Create_ImageView_Resource_VK(
		reinterpret_cast<VkImageView>(img->view), reinterpret_cast<VkImage>(img->image), range,
		static_cast<VkFormat>(img->format), static_cast<unsigned int>(img->width), static_cast<unsigned int>(img->height), readWrite);
}

// Space-separated instance and device extensions NGX needs, written as "instance|device".
RX_EXPORT int rx_required_extensions(char* out, int outSize) {
	unsigned int instanceCount = 0, deviceCount = 0;
	const char** instanceExts = nullptr;
	const char** deviceExts = nullptr;
	NVSDK_NGX_Result result = NVSDK_NGX_VULKAN_RequiredExtensions(&instanceCount, &instanceExts, &deviceCount, &deviceExts);
	if (NVSDK_NGX_FAILED(result)) {
		copyOut("", out, outSize);
		return static_cast<int>(result);
	}
	std::string text;
	for (unsigned int i = 0; i < instanceCount; i++) {
		if (i) text += ' ';
		text += instanceExts[i];
	}
	text += '|';
	for (unsigned int i = 0; i < deviceCount; i++) {
		if (i) text += ' ';
		text += deviceExts[i];
	}
	copyOut(text, out, outSize);
	return 0;
}

RX_EXPORT int rx_init(void* instance, void* physicalDevice, void* device, void* gipa, void* gdpa,
		const wchar_t* dataPath, const wchar_t* featurePath, const char* engineVersion, int logLevel) {
	if (g_initialized) {
		return 0;
	}
	const wchar_t* paths[] = {featurePath};
	NVSDK_NGX_FeatureCommonInfo info = {};
	info.PathListInfo.Path = paths;
	info.PathListInfo.Length = featurePath != nullptr ? 1 : 0;
	info.LoggingInfo.LoggingCallback = logCallback;
	info.LoggingInfo.MinimumLoggingLevel = static_cast<NVSDK_NGX_Logging_Level>(logLevel);
	info.LoggingInfo.DisableOtherLoggingSinks = false;

	NVSDK_NGX_Result result = NVSDK_NGX_VULKAN_Init_with_ProjectID(
		kProjectId, NVSDK_NGX_ENGINE_TYPE_CUSTOM, engineVersion, dataPath,
		static_cast<VkInstance>(instance), static_cast<VkPhysicalDevice>(physicalDevice), static_cast<VkDevice>(device),
		reinterpret_cast<PFN_vkGetInstanceProcAddr>(gipa), reinterpret_cast<PFN_vkGetDeviceProcAddr>(gdpa),
		&info, NVSDK_NGX_Version_API);
	if (NVSDK_NGX_FAILED(result)) {
		return static_cast<int>(result);
	}
	g_device = static_cast<VkDevice>(device);
	result = NVSDK_NGX_VULKAN_GetCapabilityParameters(&g_params);
	if (NVSDK_NGX_FAILED(result)) {
		NVSDK_NGX_VULKAN_Shutdown1(g_device);
		g_device = VK_NULL_HANDLE;
		return static_cast<int>(result);
	}
	g_initialized = true;
	return 0;
}

// Returns 1 when DLSS Super Resolution can run. Out values describe why not.
RX_EXPORT int rx_dlss_available(int* needsDriverUpdate, unsigned int* minDriverMajor, unsigned int* minDriverMinor, int* initResult) {
	*needsDriverUpdate = 0;
	*minDriverMajor = 0;
	*minDriverMinor = 0;
	*initResult = 0;
	if (!g_initialized) {
		return 0;
	}
	int available = 0;
	g_params->Get(NVSDK_NGX_Parameter_SuperSampling_NeedsUpdatedDriver, needsDriverUpdate);
	g_params->Get(NVSDK_NGX_Parameter_SuperSampling_MinDriverVersionMajor, minDriverMajor);
	g_params->Get(NVSDK_NGX_Parameter_SuperSampling_MinDriverVersionMinor, minDriverMinor);
	g_params->Get(NVSDK_NGX_Parameter_SuperSampling_FeatureInitResult, initResult);
	NVSDK_NGX_Result result = g_params->Get(NVSDK_NGX_Parameter_SuperSampling_Available, &available);
	return NVSDK_NGX_SUCCEED(result) && available ? 1 : 0;
}

RX_EXPORT int rx_optimal_settings(unsigned int displayWidth, unsigned int displayHeight, int quality,
		unsigned int* renderWidth, unsigned int* renderHeight) {
	if (!g_initialized) {
		return -1;
	}
	unsigned int maxW = 0, maxH = 0, minW = 0, minH = 0;
	float sharpness = 0.0f;
	NVSDK_NGX_Result result = NGX_DLSS_GET_OPTIMAL_SETTINGS(g_params, displayWidth, displayHeight,
		static_cast<NVSDK_NGX_PerfQuality_Value>(quality), renderWidth, renderHeight, &maxW, &maxH, &minW, &minH, &sharpness);
	return NVSDK_NGX_FAILED(result) ? static_cast<int>(result) : 0;
}

RX_EXPORT void rx_release() {
	if (g_dlss != nullptr) {
		NVSDK_NGX_VULKAN_ReleaseFeature(g_dlss);
		g_dlss = nullptr;
	}
}

// Records feature creation into the given command buffer. The caller releases any previous feature
// only after the GPU is done with it (rx_release waits for nothing).
RX_EXPORT int rx_create(void* commandBuffer, unsigned int renderWidth, unsigned int renderHeight,
		unsigned int displayWidth, unsigned int displayHeight, int quality, int flags, unsigned int preset) {
	if (!g_initialized) {
		return -1;
	}
	rx_release();
	for (const char* key : {NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_DLAA,
		NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Quality, NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Balanced,
		NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Performance, NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_UltraPerformance}) {
		g_params->Set(key, preset);
	}
	NVSDK_NGX_DLSS_Create_Params params = {};
	params.Feature.InWidth = renderWidth;
	params.Feature.InHeight = renderHeight;
	params.Feature.InTargetWidth = displayWidth;
	params.Feature.InTargetHeight = displayHeight;
	params.Feature.InPerfQualityValue = static_cast<NVSDK_NGX_PerfQuality_Value>(quality);
	params.InFeatureCreateFlags = flags;
	NVSDK_NGX_Result result = NGX_VULKAN_CREATE_DLSS_EXT1(g_device, static_cast<VkCommandBuffer>(commandBuffer),
		1, 1, &g_dlss, g_params, &params);
	if (NVSDK_NGX_FAILED(result)) {
		g_dlss = nullptr;
		return static_cast<int>(result);
	}
	return 0;
}

RX_EXPORT int rx_evaluate(void* commandBuffer, const RxImage* color, const RxImage* depth, const RxImage* motion,
		const RxImage* output, float jitterX, float jitterY, float mvScaleX, float mvScaleY, int reset, float frameTimeMs) {
	if (g_dlss == nullptr) {
		return -1;
	}
	NVSDK_NGX_Resource_VK colorRes = toResource(color, false);
	NVSDK_NGX_Resource_VK depthRes = toResource(depth, false);
	NVSDK_NGX_Resource_VK motionRes = toResource(motion, false);
	NVSDK_NGX_Resource_VK outputRes = toResource(output, true);

	NVSDK_NGX_VK_DLSS_Eval_Params eval = {};
	eval.Feature.pInColor = &colorRes;
	eval.Feature.pInOutput = &outputRes;
	eval.pInDepth = &depthRes;
	eval.pInMotionVectors = &motionRes;
	eval.InJitterOffsetX = jitterX;
	eval.InJitterOffsetY = jitterY;
	eval.InRenderSubrectDimensions.Width = static_cast<unsigned int>(color->width);
	eval.InRenderSubrectDimensions.Height = static_cast<unsigned int>(color->height);
	eval.InReset = reset;
	eval.InMVScaleX = mvScaleX;
	eval.InMVScaleY = mvScaleY;
	eval.InPreExposure = 1.0f;
	eval.InExposureScale = 1.0f;
	eval.InFrameTimeDeltaInMsec = frameTimeMs;
	NVSDK_NGX_Result result = NGX_VULKAN_EVALUATE_DLSS_EXT(static_cast<VkCommandBuffer>(commandBuffer), g_dlss, g_params, &eval);
	return NVSDK_NGX_FAILED(result) ? static_cast<int>(result) : 0;
}

RX_EXPORT void rx_shutdown() {
	rx_release();
	if (g_initialized) {
		if (g_params != nullptr) {
			NVSDK_NGX_VULKAN_DestroyParameters(g_params);
			g_params = nullptr;
		}
		NVSDK_NGX_VULKAN_Shutdown1(g_device);
		g_device = VK_NULL_HANDLE;
		g_initialized = false;
	}
}

// Drains NGX log lines collected since the last call.
RX_EXPORT int rx_take_log(char* out, int outSize) {
	std::lock_guard<std::mutex> lock(g_logMutex);
	int n = copyOut(g_log, out, outSize);
	g_log.clear();
	return n;
}
