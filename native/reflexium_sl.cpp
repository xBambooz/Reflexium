// Reflexium Streamline bridge: NVIDIA Streamline SDK 2.14.16 driving DLSS Super Resolution,
// Reflex and PCL on Minecraft's own Vulkan device. Manual hooking: the instance, device and
// swapchain stay owned by the game; Streamline is told about them through slSetVulkanInfo.
//
// This file can be built without the SDK on machines without MSVC: the Java build uses the
// checked-in DLL. Frames, tags and constants follow the Streamline programming guides.
//
// This software contains source code provided by NVIDIA Corporation.

#include <vulkan/vulkan.h>

#include "sl.h"
#include "sl_core_api.h"
#include "sl_core_types.h"
#include "sl_dlss.h"
#include "sl_dlss_g.h"
#include "sl_helpers_vk.h"
#include "sl_pcl.h"
#include "sl_reflex.h"
#include "sl_security.h"

#include "frame_constants.h"

#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstring>

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
#include <mutex>
#include <string>

#define RX_EXPORT extern "C" __declspec(dllexport)

namespace {

using PFun_slInit = sl::Result(const sl::Preferences&, uint64_t);
using PFun_slShutdown = sl::Result();
using PFun_slSetVulkanInfo = sl::Result(const sl::VulkanInfo&);
using PFun_slGetFeatureRequirements = sl::Result(sl::Feature, sl::FeatureRequirements&);
using PFun_slGetFeatureVersion = sl::Result(sl::Feature, sl::FeatureVersion&);
using PFun_slGetFeatureFunction = sl::Result(sl::Feature, const char*, void*&);
using PFun_slGetNewFrameToken = sl::Result(sl::FrameToken*&, const uint32_t*);
using PFun_slSetConstants = sl::Result(const sl::Constants&, const sl::FrameToken&, const sl::ViewportHandle&);
using PFun_slSetTagForFrame = sl::Result(const sl::FrameToken&, const sl::ViewportHandle&, const sl::ResourceTag*, uint32_t, sl::CommandBuffer*);
using PFun_slEvaluateFeature = sl::Result(sl::Feature, const sl::FrameToken&, const sl::BaseStructure**, uint32_t, sl::CommandBuffer*);
using PFun_slIsFeatureSupported = sl::Result(sl::Feature, const sl::AdapterInfo&);
using PFun_slFreeResources = sl::Result(sl::Feature, const sl::ViewportHandle&);
using PFun_slSetFeatureLoaded = sl::Result(sl::Feature, bool);

HMODULE g_interposer = nullptr;
PFun_slInit* g_slInit = nullptr;
PFun_slShutdown* g_slShutdown = nullptr;
PFun_slSetVulkanInfo* g_slSetVulkanInfo = nullptr;
PFun_slGetFeatureRequirements* g_slGetFeatureRequirements = nullptr;
PFun_slGetFeatureVersion* g_slGetFeatureVersion = nullptr;
PFun_slGetFeatureFunction* g_slGetFeatureFunction = nullptr;
PFun_slGetNewFrameToken* g_slGetNewFrameToken = nullptr;
PFun_slSetConstants* g_slSetConstants = nullptr;
PFun_slSetTagForFrame* g_slSetTagForFrame = nullptr;
PFun_slEvaluateFeature* g_slEvaluateFeature = nullptr;
PFun_slIsFeatureSupported* g_slIsFeatureSupported = nullptr;
PFun_slFreeResources* g_slFreeResources = nullptr;
PFun_slSetFeatureLoaded* g_slSetFeatureLoaded = nullptr;

using PFun_slDLSSSetOptions = sl::Result(const sl::ViewportHandle&, const sl::DLSSOptions&);
using PFun_slDLSSGetOptimalSettings = sl::Result(const sl::DLSSOptions&, sl::DLSSOptimalSettings&);
using PFun_slDLSSGSetOptions = sl::Result(const sl::ViewportHandle&, const sl::DLSSGOptions&);
using PFun_slDLSSGGetState = sl::Result(const sl::ViewportHandle&, sl::DLSSGState&, const sl::DLSSGOptions*);
using PFun_slReflexSetOptions = sl::Result(const sl::ReflexOptions&);
using PFun_slReflexSleep = sl::Result(const sl::FrameToken&);
using PFun_slReflexGetState = sl::Result(sl::ReflexState&);
using PFun_slPCLSetMarker = sl::Result(sl::PCLMarker, const sl::FrameToken&);
using PFun_slPCLGetState = sl::Result(sl::PCLState&);

PFun_slDLSSSetOptions* g_slDLSSSetOptions = nullptr;
PFun_slDLSSGetOptimalSettings* g_slDLSSGetOptimalSettings = nullptr;
PFun_slDLSSGSetOptions* g_slDLSSGSetOptions = nullptr;
PFun_slDLSSGGetState* g_slDLSSGGetState = nullptr;
PFun_slReflexSetOptions* g_slReflexSetOptions = nullptr;
PFun_slReflexSleep* g_slReflexSleep = nullptr;
PFun_slReflexGetState* g_slReflexGetState = nullptr;
PFun_slPCLSetMarker* g_slPCLSetMarker = nullptr;
PFun_slPCLGetState* g_slPCLGetState = nullptr;

bool g_initialized = false;
bool g_deviceSet = false;
bool g_proxiesReady = false;
bool g_dlssSupported = false;
bool g_dlssgSupported = false;
bool g_reflexSupported = false;
bool g_pclSupported = false;
VkInstance g_instance = VK_NULL_HANDLE;
VkPhysicalDevice g_physicalDevice = VK_NULL_HANDLE;
VkDevice g_device = VK_NULL_HANDLE;
sl::FrameToken* g_token = nullptr;
uint32_t g_tokenIndex = 0;
sl::Constants g_constants{};

sl::FeatureRequirements g_dlssReqs{};
sl::FeatureRequirements g_dlssgReqs{};
sl::FeatureRequirements g_reflexReqs{};
sl::FeatureRequirements g_pclReqs{};
int g_dlssgMaxGenerated = 0;
bool g_dlssgDynamicSupported = false;
bool g_dlssgFeatureLoaded = true;
uint64_t g_constantsFrame = 0;
int g_dlssgStatus = 0;

std::mutex g_logMutex;
std::string g_log;

void appendLog(const std::string& text) {
	std::lock_guard<std::mutex> lock(g_logMutex);
	if (g_log.size() < 64 * 1024) {
		g_log += text;
		if (!g_log.empty() && g_log.back() != '\n') g_log += '\n';
	}
}

std::string narrow(const wchar_t* text) {
	if (text == nullptr) return {};
	int size = WideCharToMultiByte(CP_UTF8, 0, text, -1, nullptr, 0, nullptr, nullptr);
	if (size <= 1) return {};
	std::string out(static_cast<size_t>(size - 1), '\0');
	WideCharToMultiByte(CP_UTF8, 0, text, -1, out.data(), size, nullptr, nullptr);
	return out;
}

void logCallback(sl::LogType, const char* message) {
	std::string text = message == nullptr ? "" : message;
	appendLog(text);
}

std::string joinExtensions(const char* const* values, uint32_t count) {
	std::string text;
	for (uint32_t i = 0; i < count; i++) {
		if (i) text += ' ';
		text += values[i];
	}
	return text;
}

int copyOut(const std::string& text, char* out, int outSize) {
	if (out == nullptr || outSize <= 0) return static_cast<int>(text.size());
	int n = static_cast<int>(text.size()) < outSize - 1 ? static_cast<int>(text.size()) : outSize - 1;
	std::memcpy(out, text.data(), static_cast<size_t>(n));
	out[n] = '\0';
	return n;
}

template <typename T>
void resolve(const char* name, T*& target) {
	FARPROC proc = g_interposer == nullptr ? nullptr : GetProcAddress(g_interposer, name);
	if (proc == nullptr) {
		appendLog(std::string("missing export ") + name);
		return;
	}
	target = reinterpret_cast<T*>(proc);
}

template <typename T>
bool featureFunction(sl::Feature feature, const char* name, T*& target) {
	target = nullptr;
	if (g_slGetFeatureFunction == nullptr) return false;
	void* function = nullptr;
	sl::Result result = g_slGetFeatureFunction(feature, name, function);
	if (result != sl::Result::eOk) {
		appendLog(std::string("slGetFeatureFunction failed for ") + name);
		return false;
	}
	target = reinterpret_cast<T*>(function);
	return true;
}

sl::Resource makeResource(const RxImage* image, uint32_t layout) {
	sl::Resource resource(sl::ResourceType::eTex2d,
			reinterpret_cast<void*>(image->image), nullptr, reinterpret_cast<void*>(image->view));
	resource.state = layout;
	resource.width = static_cast<uint32_t>(image->width);
	resource.height = static_cast<uint32_t>(image->height);
	resource.nativeFormat = static_cast<uint32_t>(image->format);
	resource.mipLevels = 1;
	resource.arrayLayers = 1;
	resource.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT
			| VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT;
	return resource;
}

sl::Extent fullExtent(const RxImage* image) {
	sl::Extent extent{};
	extent.width = static_cast<uint32_t>(image->width);
	extent.height = static_cast<uint32_t>(image->height);
	return extent;
}

sl::Boolean toBoolean(bool value) {
	return value ? sl::Boolean::eTrue : sl::Boolean::eFalse;
}

void fillConstants(const RxFrameConstants* camera, uint32_t renderWidth, uint32_t renderHeight, bool flipY) {
	sl::Constants values{};
	std::memcpy(values.cameraViewToClip.row, camera->viewToClip, sizeof(camera->viewToClip));
	std::memcpy(values.clipToCameraView.row, camera->clipToView, sizeof(camera->clipToView));
	std::memcpy(values.clipToLensClip.row, camera->clipToLens, sizeof(camera->clipToLens));
	std::memcpy(values.clipToPrevClip.row, camera->clipToPrevious, sizeof(camera->clipToPrevious));
	std::memcpy(values.prevClipToClip.row, camera->previousToClip, sizeof(camera->previousToClip));
	values.jitterOffset.x = camera->jitter[0];
	// The tagged images are mirrored into the presented backbuffer orientation, so pixel-space inputs flip too.
	values.jitterOffset.y = flipY ? -camera->jitter[1] : camera->jitter[1];
	// No pinhole offset is used. Leaving the default INVALID_FLOAT makes the plugin reject all constants.
	values.cameraPinholeOffset.x = 0.0f;
	values.cameraPinholeOffset.y = 0.0f;
	// Not used while cameraMotionIncluded is true, but the common validator still requires a finite value.
	values.motionVectorsInvalidValue = -65504.0f;
	values.mvecScale.x = renderWidth == 0 ? 0.0f : 1.0f / static_cast<float>(renderWidth);
	values.mvecScale.y = renderHeight == 0 ? 0.0f : (flipY ? -1.0f : 1.0f) / static_cast<float>(renderHeight);
	values.cameraPos.x = camera->position[0];
	values.cameraPos.y = camera->position[1];
	values.cameraPos.z = camera->position[2];
	values.cameraUp.x = camera->up[0];
	values.cameraUp.y = camera->up[1];
	values.cameraUp.z = camera->up[2];
	values.cameraRight.x = camera->right[0];
	values.cameraRight.y = camera->right[1];
	values.cameraRight.z = camera->right[2];
	values.cameraFwd.x = camera->forward[0];
	values.cameraFwd.y = camera->forward[1];
	values.cameraFwd.z = camera->forward[2];
	values.cameraNear = camera->nearPlane;
	values.cameraFar = camera->farPlane;
	values.cameraFOV = camera->verticalFov;
	values.cameraAspectRatio = camera->aspect;
	values.depthInverted = sl::Boolean::eTrue;
	values.cameraMotionIncluded = sl::Boolean::eTrue;
	values.motionVectors3D = sl::Boolean::eFalse;
	values.motionVectorsDilated = sl::Boolean::eFalse;
	values.motionVectorsJittered = sl::Boolean::eFalse;
	values.orthographicProjection = sl::Boolean::eFalse;
	values.reset = sl::Boolean::eFalse;
	g_constants = values;
}

} // namespace

bool resolveVulkanProxies();

// Image layout is RenderPearl's stable GENERAL layout for every render target and texture.
static constexpr uint32_t kImageLayout = VK_IMAGE_LAYOUT_GENERAL;

// Loads and initializes Streamline. Safe to call more than once. Returns 0 on success.
RX_EXPORT int rx_sl_init(const wchar_t* pluginDir, const wchar_t* logDir, const char* engineVersion) {
	if (g_initialized) return 0;
	if (g_interposer != nullptr) return 1;
	if (pluginDir == nullptr) {
		appendLog("no Streamline plugin directory");
		return 2;
	}
	std::wstring dir(pluginDir);
	std::wstring interposer = dir + L"\\sl.interposer.dll";
	if (!sl::security::verifyEmbeddedSignature(interposer.c_str())) {
		appendLog("sl.interposer.dll signature verification failed");
		return 3;
	}
	for (const wchar_t* name : {L"sl.common.dll", L"sl.dlss.dll", L"sl.dlss_g.dll", L"sl.reflex.dll", L"sl.pcl.dll", L"NvLowLatencyVk.dll"}) {
		std::wstring module = dir + L"\\" + name;
		if (GetFileAttributesW(module.c_str()) == INVALID_FILE_ATTRIBUTES) {
			appendLog("missing Streamline module " + narrow(name));
			return 4;
		}
		if (!sl::security::verifyEmbeddedSignature(module.c_str())) {
			appendLog("signature verification failed for " + narrow(name));
			return 4;
		}
	}
	SetDllDirectoryW(dir.c_str());
	g_interposer = LoadLibraryW(interposer.c_str());
	if (g_interposer == nullptr) {
		appendLog("LoadLibrary(sl.interposer.dll) failed " + std::to_string(GetLastError()));
		return 5;
	}
	resolve("slInit", g_slInit);
	resolve("slShutdown", g_slShutdown);
	resolve("slSetVulkanInfo", g_slSetVulkanInfo);
	resolve("slGetFeatureRequirements", g_slGetFeatureRequirements);
	resolve("slGetFeatureVersion", g_slGetFeatureVersion);
	resolve("slGetFeatureFunction", g_slGetFeatureFunction);
	resolve("slGetNewFrameToken", g_slGetNewFrameToken);
	resolve("slSetConstants", g_slSetConstants);
	resolve("slSetTagForFrame", g_slSetTagForFrame);
	resolve("slEvaluateFeature", g_slEvaluateFeature);
	resolve("slIsFeatureSupported", g_slIsFeatureSupported);
	resolve("slFreeResources", g_slFreeResources);
	resolve("slSetFeatureLoaded", g_slSetFeatureLoaded);
	if (g_slInit == nullptr || g_slShutdown == nullptr || g_slSetVulkanInfo == nullptr
			|| g_slGetFeatureRequirements == nullptr || g_slGetFeatureFunction == nullptr
			|| g_slGetNewFrameToken == nullptr || g_slSetConstants == nullptr
			|| g_slSetTagForFrame == nullptr || g_slEvaluateFeature == nullptr
			|| g_slIsFeatureSupported == nullptr || g_slFreeResources == nullptr) {
		appendLog("Streamline exports incomplete");
		return 6;
	}

	const wchar_t* pluginPaths[] = {dir.c_str()};
	sl::Feature features[] = {sl::kFeatureDLSS, sl::kFeatureReflex, sl::kFeaturePCL, sl::kFeatureDLSS_G};
	sl::Preferences preferences{};
	preferences.showConsole = false;
	// Verbose while the DLSS-G present path is being validated; the callback buffer is capped and drained
	// after init and after a run of frames without generated presents.
	preferences.logLevel = sl::LogLevel::eVerbose;
	if (logDir != nullptr) CreateDirectoryW(logDir, nullptr);
	preferences.pathsToPlugins = pluginPaths;
	preferences.numPathsToPlugins = 1;
	preferences.pathToLogsAndData = logDir;
	preferences.logMessageCallback = logCallback;
	preferences.flags = sl::PreferenceFlags::eUseManualHooking
			| sl::PreferenceFlags::eDisableDebugText
			| sl::PreferenceFlags::eUseFrameBasedResourceTagging;
	preferences.featuresToLoad = features;
	preferences.numFeaturesToLoad = 4;
	preferences.engine = sl::EngineType::eCustom;
	preferences.engineVersion = engineVersion == nullptr ? "26.3" : engineVersion;
	preferences.projectId = "4f6b2a0e-6d51-4c8a-9a7e-7c1f3d2b9e15";
	preferences.renderAPI = sl::RenderAPI::eVulkan;

	sl::Result result = g_slInit(preferences, sl::kSDKVersion);
	if (result != sl::Result::eOk) {
		appendLog("slInit failed " + std::to_string(static_cast<int>(result)));
		return static_cast<int>(100 + static_cast<int>(result));
	}
	if (g_slGetFeatureRequirements(sl::kFeatureDLSS, g_dlssReqs) != sl::Result::eOk
			|| g_slGetFeatureRequirements(sl::kFeatureReflex, g_reflexReqs) != sl::Result::eOk
			|| g_slGetFeatureRequirements(sl::kFeaturePCL, g_pclReqs) != sl::Result::eOk) {
		appendLog("slGetFeatureRequirements failed");
		return 7;
	}
	// DLSS-G is optional: keep SR and Reflex working when frame generation cannot initialize.
	if (g_slGetFeatureRequirements(sl::kFeatureDLSS_G, g_dlssgReqs) != sl::Result::eOk) {
		appendLog("slGetFeatureRequirements(DLSS-G) failed; frame generation unavailable");
	}
	g_initialized = true;
	appendLog("Streamline initialized, manual Vulkan hooking");
	return 0;
}

RX_EXPORT int rx_sl_instance_extensions(char* out, int outSize) {
	return copyOut(joinExtensions(g_dlssReqs.vkInstanceExtensions, g_dlssReqs.vkNumInstanceExtensions), out, outSize);
}

namespace {

void appendUniqueExtensions(std::string& text, const char* const* values, uint32_t count) {
	for (uint32_t i = 0; i < count; i++) {
		const char* value = values[i];
		if (text.find(value) != std::string::npos) continue;
		if (!text.empty()) text += ' ';
		text += value;
	}
}

std::string mergedExtensions(const char* const* first, uint32_t firstCount, const char* const* second, uint32_t secondCount,
		const char* const* third, uint32_t thirdCount) {
	std::string text;
	appendUniqueExtensions(text, first, firstCount);
	appendUniqueExtensions(text, second, secondCount);
	appendUniqueExtensions(text, third, thirdCount);
	return text;
}

} // namespace

RX_EXPORT int rx_sl_device_extensions(char* out, int outSize) {
	std::string text = mergedExtensions(g_dlssReqs.vkDeviceExtensions, g_dlssReqs.vkNumDeviceExtensions,
			g_dlssgReqs.vkDeviceExtensions, g_dlssgReqs.vkNumDeviceExtensions,
			g_reflexReqs.vkDeviceExtensions, g_reflexReqs.vkNumDeviceExtensions);
	return copyOut(text, out, outSize);
}

RX_EXPORT int rx_sl_features12(char* out, int outSize) {
	std::string text = mergedExtensions(g_dlssReqs.vkFeatures12, g_dlssReqs.vkNumFeatures12,
			g_dlssgReqs.vkFeatures12, g_dlssgReqs.vkNumFeatures12, nullptr, 0);
	return copyOut(text, out, outSize);
}

RX_EXPORT int rx_sl_features13(char* out, int outSize) {
	std::string text = mergedExtensions(g_dlssReqs.vkFeatures13, g_dlssReqs.vkNumFeatures13,
			g_dlssgReqs.vkFeatures13, g_dlssgReqs.vkNumFeatures13, nullptr, 0);
	return copyOut(text, out, outSize);
}

RX_EXPORT int rx_sl_queue_requirements(int* graphics, int* compute, int* opticalFlow) {
	if (graphics) *graphics = static_cast<int>((std::max)(g_dlssReqs.vkNumGraphicsQueuesRequired, g_dlssgReqs.vkNumGraphicsQueuesRequired));
	if (compute) *compute = static_cast<int>((std::max)(g_dlssReqs.vkNumComputeQueuesRequired, g_dlssgReqs.vkNumComputeQueuesRequired));
	if (opticalFlow) *opticalFlow = static_cast<int>((std::max)(g_dlssReqs.vkNumOpticalFlowQueuesRequired, g_dlssgReqs.vkNumOpticalFlowQueuesRequired));
	return 0;
}

// Tells Streamline which device and queues it may use. Must be called once after device creation.
RX_EXPORT int rx_sl_set_device(VkInstance instance, VkPhysicalDevice physicalDevice, VkDevice device,
		uint32_t graphicsFamily, uint32_t graphicsIndex, uint32_t computeFamily, uint32_t computeIndex,
		uint32_t opticalFlowFamily, uint32_t opticalFlowIndex) {
	if (!g_initialized) return 1;
	if (g_deviceSet) return 0;
	if (instance == VK_NULL_HANDLE || physicalDevice == VK_NULL_HANDLE || device == VK_NULL_HANDLE) return 2;
	sl::VulkanInfo info{};
	info.instance = instance;
	info.physicalDevice = physicalDevice;
	info.device = device;
	info.graphicsQueueFamily = graphicsFamily;
	info.graphicsQueueIndex = graphicsIndex;
	info.computeQueueFamily = computeFamily;
	info.computeQueueIndex = computeIndex;
	info.opticalFlowQueueFamily = opticalFlowFamily;
	info.opticalFlowQueueIndex = opticalFlowIndex;
	info.useNativeOpticalFlowMode = false;
	sl::Result result = g_slSetVulkanInfo(info);
	if (result != sl::Result::eOk) {
		appendLog("slSetVulkanInfo failed " + std::to_string(static_cast<int>(result)));
		return static_cast<int>(100 + static_cast<int>(result));
	}
	g_instance = instance;
	g_physicalDevice = physicalDevice;
	g_device = device;
	g_deviceSet = true;
	// Resolve the intercepted swapchain/present entry points now: the game asks whether Streamline
	// can own the swapchain before it creates the first one, and that answer must not depend on a
	// proxy call that has never happened.
	g_proxiesReady = resolveVulkanProxies();

	sl::AdapterInfo adapter{};
	adapter.vkPhysicalDevice = physicalDevice;
	auto supported = [&](sl::Feature feature) {
		sl::Result r = g_slIsFeatureSupported(feature, adapter);
		if (r != sl::Result::eOk) appendLog("feature " + std::to_string(static_cast<int>(feature)) + " unsupported " + std::to_string(static_cast<int>(r)));
		return r == sl::Result::eOk;
	};
	g_dlssSupported = supported(sl::kFeatureDLSS);
	g_dlssgSupported = supported(sl::kFeatureDLSS_G);
	g_reflexSupported = supported(sl::kFeatureReflex);
	g_pclSupported = supported(sl::kFeaturePCL);
	if (g_dlssSupported) {
		featureFunction(sl::kFeatureDLSS, "slDLSSSetOptions", g_slDLSSSetOptions);
		featureFunction(sl::kFeatureDLSS, "slDLSSGetOptimalSettings", g_slDLSSGetOptimalSettings);
	}
	if (g_dlssgSupported) {
		featureFunction(sl::kFeatureDLSS_G, "slDLSSGSetOptions", g_slDLSSGSetOptions);
		featureFunction(sl::kFeatureDLSS_G, "slDLSSGGetState", g_slDLSSGGetState);
		if (g_slDLSSGGetState != nullptr) {
			sl::DLSSGState state{};
			sl::Result stateResult = g_slDLSSGGetState(sl::ViewportHandle(0), state, nullptr);
			if (stateResult == sl::Result::eOk) {
				g_dlssgMaxGenerated = static_cast<int>(state.numFramesToGenerateMax);
				g_dlssgDynamicSupported = state.bIsDynamicMFGSupported == sl::Boolean::eTrue;
				g_dlssgStatus = static_cast<int>(state.status);
			} else {
				appendLog("slDLSSGGetState after device set failed " + std::to_string(static_cast<int>(stateResult)));
			}
		}
	}
	if (g_reflexSupported) {
		featureFunction(sl::kFeatureReflex, "slReflexSetOptions", g_slReflexSetOptions);
		featureFunction(sl::kFeatureReflex, "slReflexSleep", g_slReflexSleep);
		featureFunction(sl::kFeatureReflex, "slReflexGetState", g_slReflexGetState);
	}
	if (g_pclSupported) {
		featureFunction(sl::kFeaturePCL, "slPCLSetMarker", g_slPCLSetMarker);
		featureFunction(sl::kFeaturePCL, "slPCLGetState", g_slPCLGetState);
	}
	appendLog("device set: dlss=" + std::to_string(g_dlssSupported) + " dlssg=" + std::to_string(g_dlssgSupported)
			+ " maxGenerated=" + std::to_string(g_dlssgMaxGenerated) + " reflex=" + std::to_string(g_reflexSupported)
			+ " pcl=" + std::to_string(g_pclSupported));
	return 0;
}

// Swapchain, acquire and present proxies. Intercepted by Streamline in manual hooking mode; the game
// must call these instead of the native entry points so Streamline can pace and tag presents.
using PFN_vkCreateSwapchainKHR = VkResult(VKAPI_CALL*)(VkDevice, const VkSwapchainCreateInfoKHR*, const VkAllocationCallbacks*, VkSwapchainKHR*);
using PFN_vkDestroySwapchainKHR = void(VKAPI_CALL*)(VkDevice, VkSwapchainKHR, const VkAllocationCallbacks*);
using PFN_vkGetSwapchainImagesKHR = VkResult(VKAPI_CALL*)(VkDevice, VkSwapchainKHR, uint32_t*, VkImage*);
using PFN_vkAcquireNextImageKHR = VkResult(VKAPI_CALL*)(VkDevice, VkSwapchainKHR, uint64_t, VkSemaphore, VkFence, uint32_t*);
using PFN_vkQueuePresentKHR = VkResult(VKAPI_CALL*)(VkQueue, const VkPresentInfoKHR*);
using PFN_vkDeviceWaitIdle = VkResult(VKAPI_CALL*)(VkDevice);
using PFN_vkCreateWin32SurfaceKHR = VkResult(VKAPI_CALL*)(VkInstance, const VkWin32SurfaceCreateInfoKHR*, const VkAllocationCallbacks*, VkSurfaceKHR*);
using PFN_vkDestroySurfaceKHR = void(VKAPI_CALL*)(VkInstance, VkSurfaceKHR, const VkAllocationCallbacks*);
using PFN_vkGetDeviceProcAddr = PFN_vkVoidFunction(VKAPI_CALL*)(VkDevice, const char*);

PFN_vkCreateSwapchainKHR g_createSwapchain = nullptr;
PFN_vkDestroySwapchainKHR g_destroySwapchain = nullptr;
PFN_vkGetSwapchainImagesKHR g_getSwapchainImages = nullptr;
PFN_vkAcquireNextImageKHR g_acquireNextImage = nullptr;
PFN_vkQueuePresentKHR g_queuePresent = nullptr;
PFN_vkDeviceWaitIdle g_deviceWaitIdle = nullptr;
PFN_vkCreateWin32SurfaceKHR g_createWin32Surface = nullptr;
PFN_vkDestroySurfaceKHR g_destroySurface = nullptr;
VkSurfaceKHR g_slSurface = VK_NULL_HANDLE;

bool resolveVulkanProxies() {
	auto gdpa = reinterpret_cast<PFN_vkGetDeviceProcAddr>(GetProcAddress(g_interposer, "vkGetDeviceProcAddr"));
	if (gdpa == nullptr) {
		appendLog("sl.interposer has no vkGetDeviceProcAddr");
		return false;
	}
	// Manual hooking guide (4.2): the proxies come from the interposer's vkGetDeviceProcAddr. Its
	// direct Vulkan exports forward to the loader and are not the intercepted entry points.
	auto proxy = [gdpa](const char* name) -> void* {
		void* found = reinterpret_cast<void*>(gdpa(g_device, name));
		return found != nullptr ? found : reinterpret_cast<void*>(GetProcAddress(g_interposer, name));
	};
	void* presentExport = reinterpret_cast<void*>(GetProcAddress(g_interposer, "vkQueuePresentKHR"));
	void* presentDevice = reinterpret_cast<void*>(gdpa(g_device, "vkQueuePresentKHR"));
	HMODULE vulkan = GetModuleHandleW(L"vulkan-1.dll");
	if (vulkan == nullptr) vulkan = LoadLibraryW(L"vulkan-1.dll");
	auto rawGdpa = vulkan == nullptr ? nullptr
			: reinterpret_cast<PFN_vkGetDeviceProcAddr>(GetProcAddress(vulkan, "vkGetDeviceProcAddr"));
	void* presentDriver = rawGdpa == nullptr ? nullptr : reinterpret_cast<void*>(rawGdpa(g_device, "vkQueuePresentKHR"));
	char pointers[256];
	std::snprintf(pointers, sizeof(pointers), "present pointers: interposerExport=%p deviceQuery=%p driver=%p",
			presentExport, presentDevice, presentDriver);
	appendLog(pointers);
	if (presentDevice != nullptr && presentDevice == presentDriver) {
		appendLog("vkQueuePresentKHR: device-query proxy equals the raw driver entry point");
	}
	g_createSwapchain = reinterpret_cast<PFN_vkCreateSwapchainKHR>(proxy("vkCreateSwapchainKHR"));
	g_destroySwapchain = reinterpret_cast<PFN_vkDestroySwapchainKHR>(proxy("vkDestroySwapchainKHR"));
	g_getSwapchainImages = reinterpret_cast<PFN_vkGetSwapchainImagesKHR>(proxy("vkGetSwapchainImagesKHR"));
	g_acquireNextImage = reinterpret_cast<PFN_vkAcquireNextImageKHR>(proxy("vkAcquireNextImageKHR"));
	g_queuePresent = reinterpret_cast<PFN_vkQueuePresentKHR>(proxy("vkQueuePresentKHR"));
	g_deviceWaitIdle = reinterpret_cast<PFN_vkDeviceWaitIdle>(proxy("vkDeviceWaitIdle"));
	g_createWin32Surface = reinterpret_cast<PFN_vkCreateWin32SurfaceKHR>(proxy("vkCreateWin32SurfaceKHR"));
	g_destroySurface = reinterpret_cast<PFN_vkDestroySurfaceKHR>(proxy("vkDestroySurfaceKHR"));
	return g_createSwapchain != nullptr && g_destroySwapchain != nullptr && g_getSwapchainImages != nullptr
			&& g_acquireNextImage != nullptr && g_queuePresent != nullptr;
}

RX_EXPORT int rx_sl_swapchain_ready() {
	return g_proxiesReady && g_createSwapchain != nullptr && g_queuePresent != nullptr ? 1 : 0;
}

RX_EXPORT int rx_sl_create_swapchain(VkDevice device, VkSwapchainCreateInfoKHR* info, const VkAllocationCallbacks* allocator, VkSwapchainKHR* out) {
	if (!g_proxiesReady && !resolveVulkanProxies()) return VK_ERROR_INITIALIZATION_FAILED;
	VkResult result = g_createSwapchain(device, info, allocator, out);
	if (result != VK_SUCCESS) {
		appendLog("interposer vkCreateSwapchainKHR failed " + std::to_string(static_cast<int>(result)));
	}
	return result;
}

RX_EXPORT void rx_sl_destroy_swapchain(VkDevice device, VkSwapchainKHR swapchain, const VkAllocationCallbacks* allocator) {
	if (g_destroySwapchain != nullptr) g_destroySwapchain(device, swapchain, allocator);
}

RX_EXPORT int rx_sl_get_swapchain_images(VkDevice device, VkSwapchainKHR swapchain, uint32_t* count, VkImage* images) {
	if (g_getSwapchainImages == nullptr) return VK_ERROR_INITIALIZATION_FAILED;
	return g_getSwapchainImages(device, swapchain, count, images);
}

RX_EXPORT int rx_sl_acquire_next_image(VkDevice device, VkSwapchainKHR swapchain, uint64_t timeout,
		VkSemaphore semaphore, VkFence fence, uint32_t* index) {
	if (g_acquireNextImage == nullptr) return VK_ERROR_INITIALIZATION_FAILED;
	return g_acquireNextImage(device, swapchain, timeout, semaphore, fence, index);
}

RX_EXPORT int rx_sl_present(VkQueue queue, const VkPresentInfoKHR* info) {
	if (g_queuePresent == nullptr) return VK_ERROR_INITIALIZATION_FAILED;
	return g_queuePresent(queue, info);
}

// The interposer records surface-to-HWND for its present/focus checks when the surface is created
// through this proxy. SDL creates it through the loader, so the game calls this instead.
RX_EXPORT int rx_sl_create_win32_surface(VkInstance instance, void* hwnd, VkSurfaceKHR* out) {
	if (out == nullptr || hwnd == nullptr) return VK_ERROR_INITIALIZATION_FAILED;
	if (g_createWin32Surface == nullptr && !resolveVulkanProxies()) return VK_ERROR_INITIALIZATION_FAILED;
	if (g_createWin32Surface == nullptr) return VK_ERROR_INITIALIZATION_FAILED;
	VkWin32SurfaceCreateInfoKHR info{};
	info.sType = VK_STRUCTURE_TYPE_WIN32_SURFACE_CREATE_INFO_KHR;
	info.hinstance = GetModuleHandleW(nullptr);
	info.hwnd = reinterpret_cast<HWND>(hwnd);
	VkSurfaceKHR surface = VK_NULL_HANDLE;
	VkResult result = g_createWin32Surface(instance, &info, nullptr, &surface);
	if (result != VK_SUCCESS) {
		appendLog("interposer vkCreateWin32SurfaceKHR failed " + std::to_string(static_cast<int>(result)));
		return result;
	}
	g_slSurface = surface;
	appendLog("interposer surface created");
	*out = surface;
	return VK_SUCCESS;
}

RX_EXPORT int rx_sl_owns_surface(VkSurfaceKHR surface) {
	return surface != VK_NULL_HANDLE && surface == g_slSurface ? 1 : 0;
}

RX_EXPORT void rx_sl_destroy_surface(VkInstance instance, VkSurfaceKHR surface, const VkAllocationCallbacks* allocator) {
	if (surface == VK_NULL_HANDLE) return;
	if (surface == g_slSurface && g_destroySurface != nullptr) {
		g_destroySurface(instance, surface, allocator);
		g_slSurface = VK_NULL_HANDLE;
	}
}

RX_EXPORT int rx_sl_device_wait_idle(VkDevice device) {
	if (g_deviceWaitIdle == nullptr) return VK_ERROR_INITIALIZATION_FAILED;
	return g_deviceWaitIdle(device);
}

RX_EXPORT int rx_sl_dlss_available() { return g_deviceSet && g_dlssSupported ? 1 : 0; }
RX_EXPORT int rx_sl_dlssg_available() { return g_deviceSet && g_dlssgSupported ? 1 : 0; }

// DLSS-G guide 18.0: unload the plugin while the swapchain is torn down, then recreate it
// with the feature loaded or unloaded. Feature functions are re-resolved on every load.
RX_EXPORT int rx_sl_dlssg_set_loaded(int loaded) {
	if (g_slSetFeatureLoaded == nullptr || !g_deviceSet || !g_dlssgSupported) return 1;
	bool want = loaded != 0;
	sl::Result result = g_slSetFeatureLoaded(sl::kFeatureDLSS_G, want);
	if (result != sl::Result::eOk) {
		appendLog("slSetFeatureLoaded(DLSS-G," + std::to_string(want ? 1 : 0) + ") failed " + std::to_string(static_cast<int>(result)));
		return static_cast<int>(100 + static_cast<int>(result));
	}
	g_dlssgFeatureLoaded = want;
	g_slDLSSGSetOptions = nullptr;
	g_slDLSSGGetState = nullptr;
	if (want) {
		featureFunction(sl::kFeatureDLSS_G, "slDLSSGSetOptions", g_slDLSSGSetOptions);
		featureFunction(sl::kFeatureDLSS_G, "slDLSSGGetState", g_slDLSSGGetState);
	}
	appendLog(std::string("DLSS-G feature loaded=") + (want ? "true" : "false"));
	return g_slDLSSGSetOptions != nullptr && g_slDLSSGGetState != nullptr ? 0 : (want ? 2 : 0);
}

RX_EXPORT int rx_sl_dlssg_loaded() { return g_dlssgFeatureLoaded ? 1 : 0; }
RX_EXPORT int rx_sl_reflex_available() { return g_deviceSet && g_reflexSupported ? 1 : 0; }
RX_EXPORT int rx_sl_pcl_available() { return g_deviceSet && g_pclSupported ? 1 : 0; }

// 0 on success; out[0]=numFramesToGenerateMax, out[1]=dynamic supported, out[2]=runtime status,
// out[3]=frames actually presented since the previous call, out[4]=minimum width/height.
RX_EXPORT int rx_sl_dlssg_state(int* out, int outCount) {
	if (g_slDLSSGGetState == nullptr || out == nullptr || outCount < 5) return 1;
	sl::DLSSGState state{};
	sl::Result result = g_slDLSSGGetState(sl::ViewportHandle(0), state, nullptr);
	if (result != sl::Result::eOk) return static_cast<int>(100 + static_cast<int>(result));
	out[0] = static_cast<int>(state.numFramesToGenerateMax);
	out[1] = state.bIsDynamicMFGSupported == sl::Boolean::eTrue ? 1 : 0;
	out[2] = static_cast<int>(state.status);
	out[3] = static_cast<int>(state.numFramesActuallyPresented);
	out[4] = static_cast<int>(state.minWidthOrHeight);
	g_dlssgMaxGenerated = out[0];
	g_dlssgDynamicSupported = out[1] != 0;
	g_dlssgStatus = out[2];
	return 0;
}

// mode: 0 off, 1 on (fixed multiplier). numFramesToGenerate is multiplier - 1.
RX_EXPORT int rx_sl_dlssg_set_options(int mode, int numFramesToGenerate, int uiRecomposition, int retainResources,
		int backBuffers, int mvecWidth, int mvecHeight, int colorWidth, int colorHeight) {
	if (g_slDLSSGSetOptions == nullptr || !g_dlssgSupported) return 1;
	sl::DLSSGOptions options{};
	options.mode = static_cast<sl::DLSSGMode>(mode);
	options.numFramesToGenerate = static_cast<uint32_t>(numFramesToGenerate < 1 ? 1 : numFramesToGenerate);
	// UI alpha is tagged instead of UI color+alpha, so fullscreen menu detection (which needs the color
	// buffer) is not requested. Menus and loading screens are handled by the application per frame.
	options.flags = retainResources ? sl::DLSSGFlags::eRetainResourcesWhenOff : static_cast<sl::DLSSGFlags>(0);
	options.enableUserInterfaceRecomposition = uiRecomposition ? sl::Boolean::eTrue : sl::Boolean::eFalse;
	// Manual hooking gives Streamline no swapchain description of its own; supply the expected layout.
	options.numBackBuffers = static_cast<uint32_t>(backBuffers < 0 ? 0 : backBuffers);
	options.mvecDepthWidth = static_cast<uint32_t>(mvecWidth < 0 ? 0 : mvecWidth);
	options.mvecDepthHeight = static_cast<uint32_t>(mvecHeight < 0 ? 0 : mvecHeight);
	options.colorWidth = static_cast<uint32_t>(colorWidth < 0 ? 0 : colorWidth);
	options.colorHeight = static_cast<uint32_t>(colorHeight < 0 ? 0 : colorHeight);
	sl::Result result = g_slDLSSGSetOptions(sl::ViewportHandle(0), options);
	if (result != sl::Result::eOk) {
		appendLog("slDLSSGSetOptions failed " + std::to_string(static_cast<int>(result)));
		return static_cast<int>(100 + static_cast<int>(result));
	}
	return 0;
}

// Tags DLSS-G inputs for the current real frame. Mask bits: 1 depth, 2 motion, 4 hudless, 8 UI alpha.
// Any image may be null; a null image clears that tag. Tags are set in separate calls because hudless is
// only complete after scene post effects and particles, and UI alpha after the GUI.
RX_EXPORT int rx_sl_dlssg_tags(void* commandBuffer, const RxImage* depth, const RxImage* motion,
		const RxImage* hudless, const RxImage* uiAlpha, int mask) {
	if (g_token == nullptr || g_slSetTagForFrame == nullptr || commandBuffer == nullptr) return 1;
	sl::Resource depthRes(sl::ResourceType::eTex2d, nullptr, nullptr, nullptr);
	sl::Resource motionRes(sl::ResourceType::eTex2d, nullptr, nullptr, nullptr);
	sl::Resource hudlessRes(sl::ResourceType::eTex2d, nullptr, nullptr, nullptr);
	sl::Resource uiRes(sl::ResourceType::eTex2d, nullptr, nullptr, nullptr);
	sl::Extent depthExtent{};
	sl::Extent motionExtent{};
	sl::Extent hudlessExtent{};
	sl::Extent uiExtent{};
	sl::ResourceTag tags[4] = {
		sl::ResourceTag(nullptr, sl::kBufferTypeDepth, sl::ResourceLifecycle::eValidUntilPresent),
		sl::ResourceTag(nullptr, sl::kBufferTypeMotionVectors, sl::ResourceLifecycle::eValidUntilPresent),
		sl::ResourceTag(nullptr, sl::kBufferTypeHUDLessColor, sl::ResourceLifecycle::eValidUntilPresent),
		sl::ResourceTag(nullptr, sl::kBufferTypeUIAlpha, sl::ResourceLifecycle::eValidUntilPresent),
	};
	uint32_t count = 0;
	if (mask & 1) {
		if (depth != nullptr) {
			depthRes = makeResource(depth, kImageLayout);
			depthExtent = fullExtent(depth);
		}
		tags[count++] = sl::ResourceTag(depth != nullptr ? &depthRes : nullptr, sl::kBufferTypeDepth,
				sl::ResourceLifecycle::eValidUntilPresent, depth != nullptr ? &depthExtent : nullptr);
	}
	if (mask & 2) {
		if (motion != nullptr) {
			motionRes = makeResource(motion, kImageLayout);
			motionExtent = fullExtent(motion);
		}
		tags[count++] = sl::ResourceTag(motion != nullptr ? &motionRes : nullptr, sl::kBufferTypeMotionVectors,
				sl::ResourceLifecycle::eValidUntilPresent, motion != nullptr ? &motionExtent : nullptr);
	}
	if (mask & 4) {
		if (hudless != nullptr) {
			hudlessRes = makeResource(hudless, kImageLayout);
			hudlessExtent = fullExtent(hudless);
		}
		tags[count++] = sl::ResourceTag(hudless != nullptr ? &hudlessRes : nullptr, sl::kBufferTypeHUDLessColor,
				sl::ResourceLifecycle::eValidUntilPresent, hudless != nullptr ? &hudlessExtent : nullptr);
	}
	if (mask & 8) {
		if (uiAlpha != nullptr) {
			uiRes = makeResource(uiAlpha, kImageLayout);
			uiExtent = fullExtent(uiAlpha);
		}
		// Alpha-only UI lets the plugin derive UI color from final and hudless, which keeps invert
		// blends such as the vanilla crosshair correct on generated frames.
		tags[count++] = sl::ResourceTag(uiAlpha != nullptr ? &uiRes : nullptr, sl::kBufferTypeUIAlpha,
				sl::ResourceLifecycle::eValidUntilPresent, uiAlpha != nullptr ? &uiExtent : nullptr);
	}
	if (count == 0) return 0;
	sl::Result result = g_slSetTagForFrame(*g_token, sl::ViewportHandle(0), tags, count,
			reinterpret_cast<sl::CommandBuffer*>(commandBuffer));
	if (result != sl::Result::eOk) {
		appendLog("slSetTagForFrame(DLSS-G) failed " + std::to_string(static_cast<int>(result)));
		return static_cast<int>(100 + static_cast<int>(result));
	}
	return 0;
}

// Clears every DLSS-G input tag, for menus, loading screens and frame generation off.
RX_EXPORT int rx_sl_dlssg_clear_tags(void* commandBuffer) {
	return rx_sl_dlssg_tags(commandBuffer, nullptr, nullptr, nullptr, nullptr, 15);
}

RX_EXPORT int rx_sl_dlss_optimal(int mode, int outputWidth, int outputHeight, int hdr, int* renderWidth, int* renderHeight) {
	if (g_slDLSSGetOptimalSettings == nullptr) return 1;
	sl::DLSSOptions options{};
	options.mode = static_cast<sl::DLSSMode>(mode);
	options.outputWidth = static_cast<uint32_t>(outputWidth);
	options.outputHeight = static_cast<uint32_t>(outputHeight);
	options.colorBuffersHDR = hdr ? sl::Boolean::eTrue : sl::Boolean::eFalse;
	sl::DLSSOptimalSettings settings{};
	sl::Result result = g_slDLSSGetOptimalSettings(options, settings);
	if (result != sl::Result::eOk) return static_cast<int>(100 + static_cast<int>(result));
	if (renderWidth) *renderWidth = static_cast<int>(settings.optimalRenderWidth);
	if (renderHeight) *renderHeight = static_cast<int>(settings.optimalRenderHeight);
	return 0;
}

RX_EXPORT int rx_sl_dlss_set_options(int mode, int outputWidth, int outputHeight, int hdr, int preset) {
	if (g_slDLSSSetOptions == nullptr) return 1;
	sl::DLSSOptions options{};
	options.mode = static_cast<sl::DLSSMode>(mode);
	options.outputWidth = static_cast<uint32_t>(outputWidth);
	options.outputHeight = static_cast<uint32_t>(outputHeight);
	options.colorBuffersHDR = hdr ? sl::Boolean::eTrue : sl::Boolean::eFalse;
	options.dlaaPreset = static_cast<sl::DLSSPreset>(preset);
	options.qualityPreset = static_cast<sl::DLSSPreset>(preset);
	options.balancedPreset = static_cast<sl::DLSSPreset>(preset);
	options.performancePreset = static_cast<sl::DLSSPreset>(preset);
	options.ultraPerformancePreset = static_cast<sl::DLSSPreset>(preset);
	options.ultraQualityPreset = static_cast<sl::DLSSPreset>(preset);
	sl::Result result = g_slDLSSSetOptions(sl::ViewportHandle(0), options);
	if (result != sl::Result::eOk) {
		appendLog("slDLSSSetOptions failed " + std::to_string(static_cast<int>(result)));
		return static_cast<int>(100 + static_cast<int>(result));
	}
	return 0;
}

// Starts a frame: creates the Streamline token that all tags, constants and markers use.
RX_EXPORT int rx_sl_begin_frame(int frameIndex, int reset) {
	if (!g_deviceSet || g_slGetNewFrameToken == nullptr) return 1;
	g_tokenIndex = static_cast<uint32_t>(frameIndex);
	g_token = nullptr;
	sl::Result result = g_slGetNewFrameToken(g_token, &g_tokenIndex);
	if (result != sl::Result::eOk || g_token == nullptr) return static_cast<int>(100 + static_cast<int>(result));
	g_constants.reset = toBoolean(reset != 0);
	return 0;
}

RX_EXPORT int rx_sl_set_constants(const RxFrameConstants* camera, int reset, int renderWidth, int renderHeight, int flipY) {
	if (!g_deviceSet || g_token == nullptr || g_slSetConstants == nullptr || camera == nullptr) return 1;
	fillConstants(camera, static_cast<uint32_t>(renderWidth), static_cast<uint32_t>(renderHeight), flipY != 0);
	g_constants.reset = toBoolean(reset != 0);
	sl::Result result = g_slSetConstants(g_constants, *g_token, sl::ViewportHandle(0));
	if (result != sl::Result::eOk) {
		appendLog("slSetConstants failed " + std::to_string(static_cast<int>(result)));
		return static_cast<int>(100 + static_cast<int>(result));
	}
	g_constantsFrame = g_tokenIndex;
	return 0;
}

// Evaluates DLSS SR. Color and output are render-resolution and display-resolution color; depth
// and motion are render-resolution. Motion vectors are in render pixels, as in the NGX path.
RX_EXPORT int rx_sl_dlss_evaluate(void* commandBuffer, const RxImage* color, const RxImage* depth,
		const RxImage* motion, const RxImage* output) {
	if (!g_deviceSet || g_token == nullptr || !g_dlssSupported || commandBuffer == nullptr
			|| color == nullptr || depth == nullptr || motion == nullptr || output == nullptr) return 1;
	sl::Resource colorRes = makeResource(color, kImageLayout);
	sl::Resource depthRes = makeResource(depth, kImageLayout);
	sl::Resource motionRes = makeResource(motion, kImageLayout);
	sl::Resource outputRes = makeResource(output, kImageLayout);
	sl::Extent colorExtent = fullExtent(color);
	sl::Extent depthExtent = fullExtent(depth);
	sl::Extent motionExtent = fullExtent(motion);
	sl::Extent outputExtent = fullExtent(output);
	sl::ResourceTag tags[] = {
		sl::ResourceTag(&colorRes, sl::kBufferTypeScalingInputColor, sl::ResourceLifecycle::eOnlyValidNow, &colorExtent),
		sl::ResourceTag(&outputRes, sl::kBufferTypeScalingOutputColor, sl::ResourceLifecycle::eOnlyValidNow, &outputExtent),
		sl::ResourceTag(&depthRes, sl::kBufferTypeDepth, sl::ResourceLifecycle::eValidUntilPresent, &depthExtent),
		sl::ResourceTag(&motionRes, sl::kBufferTypeMotionVectors, sl::ResourceLifecycle::eOnlyValidNow, &motionExtent),
	};
	sl::Result result = g_slSetTagForFrame(*g_token, sl::ViewportHandle(0), tags, 4,
			reinterpret_cast<sl::CommandBuffer*>(commandBuffer));
	if (result != sl::Result::eOk) {
		appendLog("slSetTagForFrame(DLSS) failed " + std::to_string(static_cast<int>(result)));
		return static_cast<int>(100 + static_cast<int>(result));
	}
	sl::ViewportHandle viewport(0);
	const sl::BaseStructure* evaluateInputs[] = { &viewport };
	result = g_slEvaluateFeature(sl::kFeatureDLSS, *g_token, evaluateInputs, 1,
			reinterpret_cast<sl::CommandBuffer*>(commandBuffer));
	if (result != sl::Result::eOk) {
		appendLog("slEvaluateFeature(DLSS) failed " + std::to_string(static_cast<int>(result))
			+ " at frame " + std::to_string(g_tokenIndex) + " constants frame " + std::to_string(g_constantsFrame));
		return static_cast<int>(100 + static_cast<int>(result));
	}
	return 0;
}

RX_EXPORT void rx_sl_free_dlss() {
	if (g_slFreeResources != nullptr && g_deviceSet) {
		g_slFreeResources(sl::kFeatureDLSS, sl::ViewportHandle(0));
	}
}

RX_EXPORT int rx_sl_dlss_version(char* out, int outSize) {
	sl::FeatureVersion version{};
	if (g_slGetFeatureVersion == nullptr || g_slGetFeatureVersion(sl::kFeatureDLSS, version) != sl::Result::eOk) return 1;
	char text[64];
	std::snprintf(text, sizeof(text), "%u.%u.%u (SL %u.%u.%u)", version.versionNGX.major, version.versionNGX.minor,
			version.versionNGX.build, version.versionSL.major, version.versionSL.minor, version.versionSL.build);
	return copyOut(text, out, outSize);
}

RX_EXPORT int rx_sl_reflex_options(int mode, int frameLimitUs) {
	if (g_slReflexSetOptions == nullptr) return 1;
	sl::ReflexOptions options{};
	options.mode = static_cast<sl::ReflexMode>(mode);
	options.frameLimitUs = static_cast<uint32_t>(frameLimitUs);
	sl::Result result = g_slReflexSetOptions(options);
	if (result != sl::Result::eOk) {
		appendLog("slReflexSetOptions failed " + std::to_string(static_cast<int>(result)));
		return static_cast<int>(100 + static_cast<int>(result));
	}
	return 0;
}

RX_EXPORT int rx_sl_reflex_sleep() {
	if (g_slReflexSleep == nullptr || g_token == nullptr) return 1;
	sl::Result result = g_slReflexSleep(*g_token);
	if (result != sl::Result::eOk) {
		appendLog("slReflexSleep failed " + std::to_string(static_cast<int>(result)));
		return static_cast<int>(100 + static_cast<int>(result));
	}
	return 0;
}

RX_EXPORT int rx_sl_pcl_marker(int marker) {
	if (g_slPCLSetMarker == nullptr || g_token == nullptr) return 1;
	if (marker < 0 || marker >= static_cast<int>(sl::PCLMarker::eMaximum)) return 2;
	sl::Result result = g_slPCLSetMarker(static_cast<sl::PCLMarker>(marker), *g_token);
	if (result != sl::Result::eOk) {
		appendLog("slPCLSetMarker failed " + std::to_string(static_cast<int>(result)));
		return static_cast<int>(100 + static_cast<int>(result));
	}
	return 0;
}

RX_EXPORT int rx_sl_pcl_stats_message(unsigned int* message) {
	if (g_slPCLGetState == nullptr) return 1;
	sl::PCLState state{};
	sl::Result result = g_slPCLGetState(state);
	if (result != sl::Result::eOk) return static_cast<int>(100 + static_cast<int>(result));
	if (message) *message = state.statsWindowMessage;
	return 0;
}

// Reflex availability flags, for status diagnostics. Does not consume the frame report counter.
RX_EXPORT int rx_sl_reflex_flags(int* lowLatencyAvailable, int* latencyReportAvailable) {
	if (g_slReflexGetState == nullptr) return 1;
	sl::ReflexState state{};
	sl::Result result = g_slReflexGetState(state);
	if (result != sl::Result::eOk) return static_cast<int>(100 + static_cast<int>(result));
	if (lowLatencyAvailable) *lowLatencyAvailable = state.lowLatencyAvailable ? 1 : 0;
	if (latencyReportAvailable) *latencyReportAvailable = state.latencyReportAvailable ? 1 : 0;
	return 0;
}

// Copies the latest valid Reflex frame report. Times are the driver's, in microseconds.
RX_EXPORT int rx_sl_reflex_report(unsigned long long* presentId, long long* times) {
	if (g_slReflexGetState == nullptr || times == nullptr) return 1;
	sl::ReflexState state{};
	sl::Result result = g_slReflexGetState(state);
	if (result != sl::Result::eOk) return static_cast<int>(100 + static_cast<int>(result));
	if (!state.latencyReportAvailable) return 3;
	const sl::ReflexReport* latest = nullptr;
	for (int i = 0; i < sl::kReflexFrameReportCount; i++) {
		const sl::ReflexReport& report = state.frameReport[i];
		// The present-end timestamp can legitimately be zero on an async frame; timestamp validity is
		// checked in Java so an incomplete report names the missing field instead of hiding the report.
		if (report.frameID == 0) continue;
		if (latest == nullptr || report.frameID > latest->frameID) latest = &report;
	}
	if (latest == nullptr) return 2;
	if (presentId) *presentId = latest->frameID;
	times[0] = static_cast<long long>(latest->inputSampleTime);
	times[1] = static_cast<long long>(latest->simStartTime);
	times[2] = static_cast<long long>(latest->simEndTime);
	times[3] = static_cast<long long>(latest->renderSubmitStartTime);
	times[4] = static_cast<long long>(latest->renderSubmitEndTime);
	times[5] = static_cast<long long>(latest->presentStartTime);
	times[6] = static_cast<long long>(latest->presentEndTime);
	times[7] = static_cast<long long>(latest->driverStartTime);
	times[8] = static_cast<long long>(latest->driverEndTime);
	times[9] = static_cast<long long>(latest->osRenderQueueStartTime);
	times[10] = static_cast<long long>(latest->osRenderQueueEndTime);
	times[11] = static_cast<long long>(latest->gpuRenderStartTime);
	times[12] = static_cast<long long>(latest->gpuRenderEndTime);
	return state.lowLatencyAvailable ? 1 : 0;
}

RX_EXPORT void rx_sl_shutdown() {
	if (!g_initialized) return;
	if (g_slShutdown != nullptr) g_slShutdown();
	if (g_interposer != nullptr) {
		FreeLibrary(g_interposer);
		g_interposer = nullptr;
	}
	g_initialized = false;
	g_deviceSet = false;
	g_proxiesReady = false;
	g_token = nullptr;
}

RX_EXPORT int rx_sl_take_log(char* out, int outSize) {
	std::lock_guard<std::mutex> lock(g_logMutex);
	int n = copyOut(g_log, out, outSize);
	g_log.clear();
	return n;
}
