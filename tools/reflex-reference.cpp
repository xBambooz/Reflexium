// Standalone Win32/Vulkan low_latency2 reproducer. No Minecraft, NGX, SDL or JVM.
// Build/run instructions and limits: docs/PERFORMANCE.md.
#define VK_USE_PLATFORM_WIN32_KHR
#define NOMINMAX
#include <windows.h>
#include <vulkan/vulkan.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <numeric>
#include <vector>

static std::atomic_uint validationErrors{0};
static VKAPI_ATTR VkBool32 VKAPI_CALL validationMessage(VkDebugUtilsMessageSeverityFlagBitsEXT severity,
    VkDebugUtilsMessageTypeFlagsEXT, const VkDebugUtilsMessengerCallbackDataEXT* data, void*) {
    std::fprintf(stderr, "%s\n", data->pMessage);
    if (severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) ++validationErrors;
    return VK_FALSE;
}

static void check(VkResult result) {
    if (result != VK_SUCCESS) { std::fprintf(stderr, "Vulkan result %d\n", result); std::exit(1); }
}
static double nowUs() {
    using Clock = std::chrono::steady_clock;
    return std::chrono::duration<double, std::micro>(Clock::now().time_since_epoch()).count();
}
static double mean(const std::vector<double>& v) {
    return v.empty() ? 0 : std::accumulate(v.begin(), v.end(), 0.0) / v.size();
}
static double percentile(std::vector<double> v, double p) {
    if (v.empty()) return 0;
    std::sort(v.begin(), v.end());
    return v[static_cast<size_t>((v.size() - 1) * p)];
}
static BOOL CALLBACK secondary(HMONITOR monitor, HDC, LPRECT, LPARAM value) {
    MONITORINFO info{sizeof(info)};
    GetMonitorInfo(monitor, &info);
    if (!(info.dwFlags & MONITORINFOF_PRIMARY)) *reinterpret_cast<RECT*>(value) = info.rcWork;
    return TRUE;
}

int main(int argc, char** argv) {
    const bool explicitIds = argc > 1 && std::atoi(argv[1]) != 0;
    const double cpuUs = argc > 2 ? std::atof(argv[2]) : 350.0;
    const bool splitSubmit = argc > 3 && std::atoi(argv[3]) != 0;
    const double targetGpuUs = argc > 4 ? std::atof(argv[4]) : 1100.0;
    if (!std::isfinite(cpuUs) || !std::isfinite(targetGpuUs) || cpuUs < 0 || cpuUs > 10000 || targetGpuUs < 100 || targetGpuUs > 10000) return 1;
    RECT monitor{0, 0, 1920, 1080};
    EnumDisplayMonitors(nullptr, nullptr, secondary, reinterpret_cast<LPARAM>(&monitor));
    WNDCLASSW cls{};
    cls.lpfnWndProc = DefWindowProcW;
    cls.hInstance = GetModuleHandle(nullptr);
    cls.lpszClassName = L"ReflexReference";
    RegisterClassW(&cls);
    HWND foreground = GetForegroundWindow();
    HWND window = CreateWindowExW(WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW, cls.lpszClassName,
        L"Reflex Vulkan reference", WS_OVERLAPPEDWINDOW, monitor.left + 40, monitor.top + 40,
        640, 360, nullptr, nullptr, cls.hInstance, nullptr);
    if (!window) return 1;
    ShowWindow(window, SW_SHOWNOACTIVATE);
    if (GetForegroundWindow() != foreground) { std::fprintf(stderr, "Focus changed\n"); return 1; }

    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "Reflex reference";
    app.apiVersion = VK_API_VERSION_1_3;
    const char* instanceExt[]{VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_WIN32_SURFACE_EXTENSION_NAME, VK_EXT_DEBUG_UTILS_EXTENSION_NAME};
    VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ici.pApplicationInfo = &app;
    ici.enabledExtensionCount = 3;
    ici.ppEnabledExtensionNames = instanceExt;
    VkInstance instance;
    check(vkCreateInstance(&ici, nullptr, &instance));
    VkDebugUtilsMessengerCreateInfoEXT debugInfo{VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT};
    debugInfo.messageSeverity = VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT;
    debugInfo.messageType = VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT;
    debugInfo.pfnUserCallback = validationMessage;
    VkDebugUtilsMessengerEXT debugMessenger;
    check(reinterpret_cast<PFN_vkCreateDebugUtilsMessengerEXT>(vkGetInstanceProcAddr(instance, "vkCreateDebugUtilsMessengerEXT"))(instance, &debugInfo, nullptr, &debugMessenger));
    VkWin32SurfaceCreateInfoKHR sci{VK_STRUCTURE_TYPE_WIN32_SURFACE_CREATE_INFO_KHR};
    sci.hinstance = cls.hInstance;
    sci.hwnd = window;
    VkSurfaceKHR surface;
    check(vkCreateWin32SurfaceKHR(instance, &sci, nullptr, &surface));
    uint32_t count = 0;
    check(vkEnumeratePhysicalDevices(instance, &count, nullptr));
    std::vector<VkPhysicalDevice> physicals(count);
    check(vkEnumeratePhysicalDevices(instance, &count, physicals.data()));
    VkPhysicalDevice physical = VK_NULL_HANDLE;
    VkPhysicalDeviceProperties properties{};
    for (auto candidate : physicals) {
        vkGetPhysicalDeviceProperties(candidate, &properties);
        if (properties.vendorID == 0x10de) { physical = candidate; break; }
    }
    if (!physical) { std::fprintf(stderr, "No NVIDIA GPU\n"); return 1; }
    vkGetPhysicalDeviceQueueFamilyProperties(physical, &count, nullptr);
    std::vector<VkQueueFamilyProperties> families(count);
    vkGetPhysicalDeviceQueueFamilyProperties(physical, &count, families.data());
    uint32_t family = UINT32_MAX;
    for (uint32_t i = 0; i < count; ++i) {
        VkBool32 present;
        check(vkGetPhysicalDeviceSurfaceSupportKHR(physical, i, surface, &present));
        if (present && (families[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) && families[i].timestampValidBits == 64) {
            family = i; break;
        }
    }
    if (family == UINT32_MAX) return 1;
    float priority = 1;
    VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    qci.queueFamilyIndex = family; qci.queueCount = 1; qci.pQueuePriorities = &priority;
    VkPhysicalDevicePresentIdFeaturesKHR pidFeature{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRESENT_ID_FEATURES_KHR};
    pidFeature.presentId = VK_TRUE;
    VkPhysicalDeviceVulkan12Features f12{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES};
    f12.timelineSemaphore = VK_TRUE; f12.pNext = &pidFeature;
    VkPhysicalDeviceVulkan13Features f13{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES};
    f13.synchronization2 = VK_TRUE; f13.pNext = &f12;
    const char* deviceExt[]{VK_KHR_SWAPCHAIN_EXTENSION_NAME, VK_NV_LOW_LATENCY_2_EXTENSION_NAME, VK_KHR_PRESENT_ID_EXTENSION_NAME};
    VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    dci.pNext = &f13; dci.queueCreateInfoCount = 1; dci.pQueueCreateInfos = &qci;
    dci.enabledExtensionCount = 3; dci.ppEnabledExtensionNames = deviceExt;
    VkDevice device;
    check(vkCreateDevice(physical, &dci, nullptr, &device));
    VkQueue queue;
    vkGetDeviceQueue(device, family, 0, &queue);
    auto setMode = reinterpret_cast<PFN_vkSetLatencySleepModeNV>(vkGetDeviceProcAddr(device, "vkSetLatencySleepModeNV"));
    auto sleep = reinterpret_cast<PFN_vkLatencySleepNV>(vkGetDeviceProcAddr(device, "vkLatencySleepNV"));
    auto marker = reinterpret_cast<PFN_vkSetLatencyMarkerNV>(vkGetDeviceProcAddr(device, "vkSetLatencyMarkerNV"));
    auto timings = reinterpret_cast<PFN_vkGetLatencyTimingsNV>(vkGetDeviceProcAddr(device, "vkGetLatencyTimingsNV"));
    if (!setMode || !sleep || !marker || !timings) return 1;
    VkSurfaceCapabilitiesKHR caps;
    check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physical, surface, &caps));
    check(vkGetPhysicalDeviceSurfaceFormatsKHR(physical, surface, &count, nullptr));
    std::vector<VkSurfaceFormatKHR> formats(count);
    check(vkGetPhysicalDeviceSurfaceFormatsKHR(physical, surface, &count, formats.data()));
    VkSwapchainLatencyCreateInfoNV latencyCreate{VK_STRUCTURE_TYPE_SWAPCHAIN_LATENCY_CREATE_INFO_NV};
    latencyCreate.latencyModeEnable = VK_TRUE;
    VkSwapchainCreateInfoKHR swapInfo{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
    swapInfo.pNext = &latencyCreate; swapInfo.surface = surface;
    swapInfo.minImageCount = std::max(3u, caps.minImageCount);
    if (caps.maxImageCount) swapInfo.minImageCount = std::min(swapInfo.minImageCount, caps.maxImageCount);
    swapInfo.imageFormat = formats[0].format; swapInfo.imageColorSpace = formats[0].colorSpace;
    swapInfo.imageExtent = caps.currentExtent; swapInfo.imageArrayLayers = 1;
    swapInfo.imageUsage = VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    swapInfo.preTransform = caps.currentTransform; swapInfo.compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    swapInfo.presentMode = VK_PRESENT_MODE_IMMEDIATE_KHR; swapInfo.clipped = VK_TRUE;
    VkSwapchainKHR swapchain;
    check(vkCreateSwapchainKHR(device, &swapInfo, nullptr, &swapchain));
    check(vkGetSwapchainImagesKHR(device, swapchain, &count, nullptr));
    std::vector<VkImage> images(count);
    check(vkGetSwapchainImagesKHR(device, swapchain, &count, images.data()));
    VkSemaphoreCreateInfo semInfo{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    std::vector<VkSemaphore> done(count);
    for (auto& sem : done) check(vkCreateSemaphore(device, &semInfo, nullptr, &sem));
    VkSemaphore acquire[3]; VkFence fences[3];
    VkFenceCreateInfo fenceInfo{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO}; fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
    for (int i = 0; i < 3; ++i) {
        check(vkCreateSemaphore(device, &semInfo, nullptr, &acquire[i]));
        check(vkCreateFence(device, &fenceInfo, nullptr, &fences[i]));
    }
    VkSemaphoreTypeCreateInfo timeline{VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO};
    timeline.semaphoreType = VK_SEMAPHORE_TYPE_TIMELINE; semInfo.pNext = &timeline;
    VkSemaphore sleepSem;
    check(vkCreateSemaphore(device, &semInfo, nullptr, &sleepSem));
    VkCommandPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    poolInfo.queueFamilyIndex = family; poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    VkCommandPool pool; check(vkCreateCommandPool(device, &poolInfo, nullptr, &pool));
    VkCommandBufferAllocateInfo alloc{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    alloc.commandPool = pool; alloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY; alloc.commandBufferCount = 6;
    VkCommandBuffer commands[6]; check(vkAllocateCommandBuffers(device, &alloc, commands));
    VkBufferCreateInfo bufferInfo{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bufferInfo.size = 64 * 1024 * 1024; bufferInfo.usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    VkBuffer buffer; check(vkCreateBuffer(device, &bufferInfo, nullptr, &buffer));
    VkMemoryRequirements requirements; vkGetBufferMemoryRequirements(device, buffer, &requirements);
    VkPhysicalDeviceMemoryProperties memProps; vkGetPhysicalDeviceMemoryProperties(physical, &memProps);
    VkMemoryAllocateInfo memoryInfo{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO}; memoryInfo.allocationSize = requirements.size;
    for (uint32_t i = 0; i < memProps.memoryTypeCount; ++i) {
        if ((requirements.memoryTypeBits & (1u << i)) && (memProps.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)) { memoryInfo.memoryTypeIndex = i; break; }
    }
    VkDeviceMemory memory; check(vkAllocateMemory(device, &memoryInfo, nullptr, &memory));
    check(vkBindBufferMemory(device, buffer, memory, 0));
    VkQueryPoolCreateInfo queryInfo{VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO};
    queryInfo.queryType = VK_QUERY_TYPE_TIMESTAMP; queryInfo.queryCount = 6;
    VkQueryPool queries; check(vkCreateQueryPool(device, &queryInfo, nullptr, &queries));

    std::printf("GPU=%s explicit_ids=%d cpu_us=%.0f images=%u split_submit=%d target_gpu_us=%.0f\n", properties.deviceName, explicitIds, cpuUs, count, splitSubmit, targetGpuUs);
    uint64_t frame = 0, sleepValue = 0;
    unsigned fills = 8;
    bool used[3]{};
    auto mark = [&](VkLatencyMarkerNV m) {
        VkSetLatencyMarkerInfoNV info{VK_STRUCTURE_TYPE_SET_LATENCY_MARKER_INFO_NV};
        info.presentID = frame; info.marker = m; marker(device, swapchain, &info);
    };
    // First phase calibrates transfer work to 1.1 ms GPU time. Later phases keep that workload.
    for (int phase = 0; phase < 5; ++phase) {
        bool on = phase > 1;
        uint32_t interval = phase == 3 ? 800 : 0;
        VkLatencySleepModeInfoNV mode{VK_STRUCTURE_TYPE_LATENCY_SLEEP_MODE_INFO_NV};
        mode.lowLatencyMode = on; mode.minimumIntervalUs = interval;
        check(setMode(device, swapchain, &mode));
        const double start = nowUs(), measureStart = start + (phase == 0 ? 500000 : 2000000);
        const double end = measureStart + (phase == 0 ? 1000000 : 4000000);
        std::vector<double> driverUs, waitUs, gpuUs, frameUs, latUs;
        double previous = 0;
        while (nowUs() < end) {
            MSG msg;
            while (PeekMessage(&msg, window, 0, 0, PM_REMOVE)) DispatchMessage(&msg);
            ++frame;
            unsigned slot = frame % 3;
            double driver = 0, wait = 0;
            // The sleep is before simulated input/CPU work, as in the Streamline sample.
            // Streamline calls sleep in Off mode too.
            {
                VkLatencySleepInfoNV info{VK_STRUCTURE_TYPE_LATENCY_SLEEP_INFO_NV};
                info.signalSemaphore = sleepSem; info.value = ++sleepValue;
                double t = nowUs(); check(sleep(device, swapchain, &info)); driver = nowUs() - t;
                VkSemaphoreWaitInfo wi{VK_STRUCTURE_TYPE_SEMAPHORE_WAIT_INFO};
                wi.semaphoreCount = 1; wi.pSemaphores = &sleepSem; wi.pValues = &sleepValue;
                t = nowUs(); check(vkWaitSemaphores(device, &wi, 100000000)); wait = nowUs() - t;
            }
            mark(VK_LATENCY_MARKER_SIMULATION_START_NV); mark(VK_LATENCY_MARKER_INPUT_SAMPLE_NV);
            const double input = nowUs();
            while (nowUs() - input < cpuUs) {}
            mark(VK_LATENCY_MARKER_SIMULATION_END_NV); mark(VK_LATENCY_MARKER_RENDERSUBMIT_START_NV);
            check(vkWaitForFences(device, 1, &fences[slot], VK_TRUE, UINT64_MAX));
            double gpu = 0;
            if (used[slot]) {
                uint64_t stamps[2];
                check(vkGetQueryPoolResults(device, queries, slot * 2, 2, sizeof(stamps), stamps, sizeof(uint64_t), VK_QUERY_RESULT_64_BIT));
                gpu = (stamps[1] - stamps[0]) * properties.limits.timestampPeriod / 1000.0;
            }
            check(vkResetFences(device, 1, &fences[slot]));
            uint32_t image;
            check(vkAcquireNextImageKHR(device, swapchain, UINT64_MAX, acquire[slot], VK_NULL_HANDLE, &image));
            auto cmd = commands[slot * 2]; check(vkResetCommandBuffer(cmd, 0));
            VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
            check(vkBeginCommandBuffer(cmd, &begin));
            vkCmdResetQueryPool(cmd, queries, slot * 2, 2);
            vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queries, slot * 2);
            for (unsigned i = 0; i < fills; ++i) {
                vkCmdFillBuffer(cmd, buffer, 0, VK_WHOLE_SIZE, static_cast<uint32_t>(frame + i));
                VkMemoryBarrier barrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
                barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT; barrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 1, &barrier, 0, nullptr, 0, nullptr);
            }
            if (splitSubmit) {
                check(vkEndCommandBuffer(cmd));
                cmd = commands[slot * 2 + 1]; check(vkResetCommandBuffer(cmd, 0));
                check(vkBeginCommandBuffer(cmd, &begin));
            }
            VkImageMemoryBarrier barrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
            barrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            barrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED; barrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
            barrier.srcQueueFamilyIndex = barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            barrier.image = images[image]; barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &barrier);
            VkClearColorValue color{{0.02f, 0.08f, 0.06f, 1}};
            vkCmdClearColorImage(cmd, images[image], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &color, 1, &barrier.subresourceRange);
            barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT; barrier.dstAccessMask = 0;
            barrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL; barrier.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 0, nullptr, 1, &barrier);
            vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, queries, slot * 2 + 1);
            check(vkEndCommandBuffer(cmd));
            VkLatencySubmissionPresentIdNV tags[2]{};
            VkCommandBufferSubmitInfo cbs[2]{};
            VkSubmitInfo2 submits[2]{};
            for (unsigned i = 0; i < (splitSubmit ? 2u : 1u); ++i) {
                tags[i].sType = VK_STRUCTURE_TYPE_LATENCY_SUBMISSION_PRESENT_ID_NV; tags[i].presentID = explicitIds ? frame : 0;
                cbs[i].sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_SUBMIT_INFO; cbs[i].commandBuffer = commands[slot * 2 + i];
                submits[i].sType = VK_STRUCTURE_TYPE_SUBMIT_INFO_2; submits[i].pNext = &tags[i];
                submits[i].commandBufferInfoCount = 1; submits[i].pCommandBufferInfos = &cbs[i];
            }
            VkSemaphoreSubmitInfo waitInfo{VK_STRUCTURE_TYPE_SEMAPHORE_SUBMIT_INFO};
            waitInfo.semaphore = acquire[slot]; waitInfo.stageMask = VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
            VkSemaphoreSubmitInfo signalInfo{VK_STRUCTURE_TYPE_SEMAPHORE_SUBMIT_INFO};
            signalInfo.semaphore = done[image]; signalInfo.stageMask = VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
            auto& last = submits[splitSubmit ? 1 : 0];
            last.waitSemaphoreInfoCount = 1; last.pWaitSemaphoreInfos = &waitInfo;
            last.signalSemaphoreInfoCount = 1; last.pSignalSemaphoreInfos = &signalInfo;
            check(vkQueueSubmit2(queue, splitSubmit ? 2 : 1, submits, fences[slot])); used[slot] = true;
            mark(VK_LATENCY_MARKER_RENDERSUBMIT_END_NV); mark(VK_LATENCY_MARKER_PRESENT_START_NV);
            VkPresentIdKHR presentId{VK_STRUCTURE_TYPE_PRESENT_ID_KHR}; presentId.swapchainCount = 1; presentId.pPresentIds = &frame;
            VkPresentInfoKHR present{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
            present.pNext = &presentId; present.waitSemaphoreCount = 1; present.pWaitSemaphores = &done[image];
            present.swapchainCount = 1; present.pSwapchains = &swapchain; present.pImageIndices = &image;
            check(vkQueuePresentKHR(queue, &present)); mark(VK_LATENCY_MARKER_PRESENT_END_NV);
            double t = nowUs();
            if (t >= measureStart && previous) {
                frameUs.push_back(t - previous); driverUs.push_back(driver); waitUs.push_back(wait);
                if (gpu) gpuUs.push_back(gpu);
            }
            previous = t;
        }
        VkLatencyTimingsFrameReportNV reports[64]{};
        for (auto& report : reports) report.sType = VK_STRUCTURE_TYPE_LATENCY_TIMINGS_FRAME_REPORT_NV;
        VkGetLatencyMarkerInfoNV reportInfo{VK_STRUCTURE_TYPE_GET_LATENCY_MARKER_INFO_NV};
        reportInfo.timingCount = 64; reportInfo.pTimings = reports; timings(device, swapchain, &reportInfo);
        unsigned inverted = 0;
        for (const auto& report : reports) {
            if (report.presentID && report.gpuRenderEndTimeUs && report.inputSampleTimeUs) {
                std::printf("report phase=%d id=%llu input=%llu submit_start=%llu submit_end=%llu driver_start=%llu driver_end=%llu gpu_start=%llu gpu_end=%llu\n",
                    phase, static_cast<unsigned long long>(report.presentID), static_cast<unsigned long long>(report.inputSampleTimeUs),
                    static_cast<unsigned long long>(report.renderSubmitStartTimeUs), static_cast<unsigned long long>(report.renderSubmitEndTimeUs),
                    static_cast<unsigned long long>(report.driverStartTimeUs), static_cast<unsigned long long>(report.driverEndTimeUs),
                    static_cast<unsigned long long>(report.gpuRenderStartTimeUs), static_cast<unsigned long long>(report.gpuRenderEndTimeUs));
                if (report.driverEndTimeUs < report.driverStartTimeUs) ++inverted;
                else if (report.gpuRenderEndTimeUs >= report.inputSampleTimeUs) latUs.push_back(static_cast<double>(report.gpuRenderEndTimeUs - report.inputSampleTimeUs));
            }
        }
        std::printf("phase=%d on=%d interval_us=%u fills=%u frames=%zu fps=%.1f driver_mean_us=%.2f driver_p50_us=%.2f driver_p95_us=%.2f wait_mean_us=%.2f gpu_mean_us=%.2f latency_us=%.2f inverted=%u\n",
            phase, on, interval, fills, frameUs.size(), 1000000 / mean(frameUs), mean(driverUs), percentile(driverUs, .5), percentile(driverUs, .95), mean(waitUs), mean(gpuUs), mean(latUs), inverted);
        std::fflush(stdout);
        if (phase == 0) fills = std::max(1u, static_cast<unsigned>(fills * targetGpuUs / mean(gpuUs) + .5));
    }
    check(vkDeviceWaitIdle(device));
    vkDestroyQueryPool(device, queries, nullptr); vkDestroyBuffer(device, buffer, nullptr); vkFreeMemory(device, memory, nullptr);
    vkDestroyCommandPool(device, pool, nullptr);
    for (int i = 0; i < 3; ++i) { vkDestroyFence(device, fences[i], nullptr); vkDestroySemaphore(device, acquire[i], nullptr); }
    for (auto sem : done) vkDestroySemaphore(device, sem, nullptr);
    vkDestroySemaphore(device, sleepSem, nullptr); vkDestroySwapchainKHR(device, swapchain, nullptr);
    vkDestroyDevice(device, nullptr); vkDestroySurfaceKHR(instance, surface, nullptr);
    reinterpret_cast<PFN_vkDestroyDebugUtilsMessengerEXT>(vkGetInstanceProcAddr(instance, "vkDestroyDebugUtilsMessengerEXT"))(instance, debugMessenger, nullptr);
    vkDestroyInstance(instance, nullptr);
    DestroyWindow(window);
    return validationErrors.load() ? 2 : 0;
}
