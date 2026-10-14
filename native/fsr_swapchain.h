// AMD's Vulkan presentation scheduler. The app supplies an already interpolated/composited image.
// Private queues keep the SDK's presentation threads away from Minecraft's queue submissions.
#pragma once
namespace {
ffxContext scContext = nullptr;
ffxCreateContextDescFrameGenerationSwapChainVK scDescription = {};
ffxQueryDescSwapchainReplacementFunctionsVK scFunctions = {};
VkSwapchainKHR scHandle = VK_NULL_HANDLE;
VkQueue scGameQueue = VK_NULL_HANDLE;
VkDevice scDevice = VK_NULL_HANDLE;
VkPhysicalDevice scPhysical = VK_NULL_HANDLE;
uint32_t scFamily = 0, scFirstPrivate = 0;
RxImage scGenerated = {};
PFN_vkCmdBlitImage scBlit = nullptr;

ffxReturnCode_t copyGenerated(ffxDispatchDescFrameGeneration* params, void*) {
    if (!scGenerated.image || !params->outputs[0].resource) return FFX_API_RETURN_ERROR_PARAMETER;
    VkImageBlit region = {};
    region.srcSubresource.aspectMask = region.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    region.srcSubresource.layerCount = region.dstSubresource.layerCount = 1;
    // Minecraft's swapchain blit flips Y; apply exactly the same transform to the generated image.
    region.srcOffsets[0] = {0, scGenerated.height, 0};
    region.srcOffsets[1] = {scGenerated.width, 0, 1};
    region.dstOffsets[1] = {scGenerated.width, scGenerated.height, 1};
    scBlit(static_cast<VkCommandBuffer>(params->commandList), reinterpret_cast<VkImage>(scGenerated.image), VK_IMAGE_LAYOUT_GENERAL,
        static_cast<VkImage>(params->outputs[0].resource), VK_IMAGE_LAYOUT_GENERAL, 1, &region, VK_FILTER_NEAREST);
    params->numGeneratedFrames = 1;
    return FFX_API_RETURN_OK;
}
}

RX_EXPORT void rx_fsr_sc_init(void* device, void* physical, void* gdpa, void* gameQueue, uint32_t family, uint32_t firstPrivate) {
    scDevice = static_cast<VkDevice>(device);
    scPhysical = static_cast<VkPhysicalDevice>(physical);
    realDeviceProcAddr = reinterpret_cast<PFN_vkGetDeviceProcAddr>(gdpa);
    scGameQueue = static_cast<VkQueue>(gameQueue);
    scFamily = family; scFirstPrivate = firstPrivate;
    scBlit = reinterpret_cast<PFN_vkCmdBlitImage>(deviceProcAddr(scDevice, "vkCmdBlitImage"));
}

RX_EXPORT void rx_fsr_sc_wait() {
    if (!scContext) return;
    ffxDispatchDescFrameGenerationSwapChainWaitForPresentsVK wait = {};
    wait.header.type = FFX_API_DISPATCH_DESC_TYPE_FGSWAPCHAIN_WAIT_FOR_PRESENTS_VK;
    ffxDispatch(&scContext, &wait.header);
}

RX_EXPORT int rx_fsr_sc_schedule(const RxImage* generated, uint64_t frameId) {
    if (!scContext || !fgContext) return -1;
    scGenerated = generated ? *generated : RxImage{};
    ffxConfigureDescFrameGeneration config = {};
    config.header.type = FFX_API_CONFIGURE_DESC_TYPE_FRAMEGENERATION;
    config.swapChain = reinterpret_cast<void*>(scHandle);
    config.frameGenerationEnabled = generated != nullptr;
    config.allowAsyncWorkloads = false;
    config.frameGenerationCallback = copyGenerated;
    config.frameID = frameId;
    config.generationRect = {0, 0, static_cast<int32_t>(scDescription.createInfo.imageExtent.width), static_cast<int32_t>(scDescription.createInfo.imageExtent.height)};
    return ffxConfigure(&fgContext, &config.header);
}

RX_EXPORT void rx_fsr_sc_pause() {
    if (!scContext) return;
    if (fgContext) rx_fsr_sc_schedule(nullptr, 0);
    rx_fsr_sc_wait();
    auto idle = reinterpret_cast<PFN_vkDeviceWaitIdle>(deviceProcAddr(scDevice, "vkDeviceWaitIdle"));
    idle(scDevice);
}

RX_EXPORT int rx_fsr_sc_create(const VkSwapchainCreateInfoKHR* info, const VkAllocationCallbacks* allocator, VkSwapchainKHR* output) {
    if (!scDevice || scContext || info->oldSwapchain) return VK_ERROR_INITIALIZATION_FAILED;
    scDescription = {};
    scDescription.header.type = FFX_API_CREATE_CONTEXT_DESC_TYPE_FGSWAPCHAIN_VK;
    scDescription.physicalDevice = scPhysical;
    scDescription.device = scDevice;
    scDescription.swapchain = &scHandle;
    scDescription.allocator = const_cast<VkAllocationCallbacks*>(allocator);
    scDescription.createInfo = *info;
    scDescription.createInfo.pNext = nullptr; // Reflex cannot use the SDK's opaque proxy handle.
    scDescription.gameQueue = {scGameQueue, scFamily, nullptr};
    auto getQueue = reinterpret_cast<PFN_vkGetDeviceQueue>(deviceProcAddr(scDevice, "vkGetDeviceQueue"));
    VkQueue queues[3] = {};
    for (uint32_t i = 0; i < 3; i++) getQueue(scDevice, scFamily, scFirstPrivate + i, &queues[i]);
    scDescription.asyncComputeQueue = {queues[0], scFamily, nullptr};
    scDescription.presentQueue = {queues[1], scFamily, nullptr};
    scDescription.imageAcquireQueue = {queues[2], scFamily, nullptr};
    auto result = ffxCreateContext(&scContext, &scDescription.header, nullptr);
    if (result != FFX_API_RETURN_OK) { scContext = nullptr; scHandle = VK_NULL_HANDLE; return VK_ERROR_INITIALIZATION_FAILED; }
    scFunctions = {};
    scFunctions.header.type = FFX_API_QUERY_DESC_TYPE_FGSWAPCHAIN_FUNCTIONS_VK;
    result = ffxQuery(&scContext, &scFunctions.header);
    if (result != FFX_API_RETURN_OK) { ffxDestroyContext(&scContext, nullptr); scContext = nullptr; scHandle = VK_NULL_HANDLE; return VK_ERROR_INITIALIZATION_FAILED; }
    *output = scHandle;
    return VK_SUCCESS;
}

RX_EXPORT void rx_fsr_sc_destroy(const VkAllocationCallbacks*) {
    if (!scContext) return;
    if (fgContext) rx_fsr_sc_schedule(nullptr, 0);
    rx_fsr_sc_wait();
    ffxDestroyContext(&scContext, nullptr);
    scContext = nullptr; scHandle = VK_NULL_HANDLE; scGenerated = {};
}
RX_EXPORT int rx_fsr_sc_images(uint32_t* count, VkImage* images) {
    return scFunctions.pOutGetSwapchainImagesKHR(scDevice, scHandle, count, images);
}
RX_EXPORT int rx_fsr_sc_acquire(uint64_t timeout, uint64_t semaphore, uint64_t fence, uint32_t* index) {
    return scFunctions.pOutAcquireNextImageKHR(scDevice, scHandle, timeout, reinterpret_cast<VkSemaphore>(semaphore), reinterpret_cast<VkFence>(fence), index);
}
RX_EXPORT int rx_fsr_sc_present(const VkPresentInfoKHR* info) {
    // Only the game thread touches scGameQueue. The SDK's workers use the three private queues.
    return scFunctions.pOutQueuePresentKHR(scGameQueue, info);
}
RX_EXPORT uint64_t rx_fsr_sc_present_count() {
    return scContext ? scFunctions.pOutGetLastPresentCountFFXAPI(scHandle) : 0;
}
