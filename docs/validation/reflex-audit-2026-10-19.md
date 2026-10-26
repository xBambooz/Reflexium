# Reflex and PCL Integration Audit

Status: the 2026-10-19 checklist below is historical. Since round 10, Reflex, PCL markers and
DLSS-G run only through NVIDIA Streamline 2.14.16; the raw `VK_NV_low_latency2` path and the raw
NGX bridge are deleted. The current Streamline re-audit and its evidence are in
[round10-2026-10-24.md](round10-2026-10-24.md), section 5. References to `NgxBridge`, raw
`vkSetLatencySleepModeNV`, `vkLatencySleepNV`, `vkSetLatencyMarkerNV`, explicit submission IDs and
`VK_NV_present_metering` below describe the removed code and must not be used to review the current
tree.

Historical audit of Reflexium (Fabric Minecraft 26.3) NVIDIA Reflex implementation via direct
`VK_NV_low_latency2` Vulkan extension against NVIDIA Reflex and PCL integration and validation
checklists.

Base commit: `92c928c`

---

## Audit Checklist Results

### 1. Initialization and Device Registration
- **Status:** PASS
- **Requirement:** Streamline / low latency extension initialized before device and swapchain use; device registered; feature availability queried.
- **Evidence:**
  - `src/client/java/com/bambooz/reflexium/vulkan/VulkanLifecycle.java:31-59`: `onDeviceCreated` discovers `session.lowLatency2()` from enumerated physical device extensions and notifies `ReflexController.get().onDeviceCreated(session)`.
  - `src/client/java/com/bambooz/reflexium/reflex/ReflexController.java:88-97`: `onDeviceCreated` checks `session.lowLatency2()`, stores `VkDevice`, creates timeline semaphore `sleepSemaphore`, and caches `presentIdEnabled`.
  - `src/client/java/com/bambooz/reflexium/mixin/VulkanGpuSurfaceMixin.java:125-132`: Swapchain creation chains `VkSwapchainLatencyCreateInfoNV` via `VulkanLifecycle.chainSwapchainLatency` with `latencyModeEnable(true)`.

### 2. Reflex Options (Off, On, On + Boost)
- **Status:** PASS
- **Requirement:** User options for Off, On, and On + Boost mapping to driver low latency modes.
- **Evidence:**
  - `src/client/java/com/bambooz/reflexium/reflex/ReflexMode.java:7-31`: Defines `OFF`, `ON`, and `ON_BOOST` with `enabled()` returning true for `ON` and `ON_BOOST`.
  - `src/client/java/com/bambooz/reflexium/reflex/ReflexController.java:386-394`: `applySleepMode` sets `lowLatencyMode(mode.enabled())` and `lowLatencyBoost(mode == ReflexMode.ON_BOOST)` in `VkLatencySleepModeInfoNV`.

### 3. Sleep Placement and Frequency
- **Status:** PASS
- **Requirement:** Sleep called once per rendered frame; placed after prior frame completion and before input sampling and simulation; called across all modes including Off when feature is loaded.
- **Evidence:**
  - `src/client/java/com/bambooz/reflexium/mixin/MinecraftMixin.java:23-26`: Injected into `Minecraft.run()` immediately before `RenderSystem.pollEvents(SDLEventHandler)` where SDL input is sampled.
  - `src/client/java/com/bambooz/reflexium/reflex/ReflexController.java:191-204`: `beginFrame()` calls `sleep()` once per application frame when `pacing.canSleep() && sleepSemaphore != 0 && !isPacingPaused()`.
  - `src/client/java/com/bambooz/reflexium/reflex/ReflexController.java:383-401`: In Off mode, `applySleepMode()` configures `lowLatencyMode(false)`, leaving `pacing.canSleep()` true so `vkLatencySleepNV` executes for driver telemetry without GPU queue pacing.
  - `src/client/java/com/bambooz/reflexium/reflex/ReflexController.java:406-444`: `sleep()` calls `vkLatencySleepNV` followed by `vkWaitSemaphores` with 100 ms timeout on `sleepSemaphore`. Unsignaled timeouts transition to non-blocking poll `pollPendingSleep()` without stalling the game.

### 4. PCL Marker Order and Set
- **Status:** FAIL (Fixed in commit `a8f1fd3`)
- **Requirement:** Expected marker sequence per presented frame: `InputSample` -> `SimulationStart` -> `SimulationEnd` -> `RenderSubmitStart` -> `RenderSubmitEnd` -> `PresentStart` -> `PresentEnd`.
- **Evidence:**
  - `src/client/java/com/bambooz/reflexium/reflex/ReflexController.java:228-234`:
    Line 228 emitted `VK_LATENCY_MARKER_SIMULATION_START_NV`.
    Line 234 emitted `VK_LATENCY_MARKER_INPUT_SAMPLE_NV`.
    `SIMULATION_START_NV` was emitted before `INPUT_SAMPLE_NV`. This was inverted relative to the NVIDIA specification, which mandates `InputSample` before `SimulationStart`.
  - `src/client/java/com/bambooz/reflexium/mixin/MinecraftMixin.java:35-38`: `endSimulation()` injected at `Minecraft.runTick` before `renderFrame`, emitting `VK_LATENCY_MARKER_SIMULATION_END_NV`. Correct placement.
  - `src/client/java/com/bambooz/reflexium/mixin/MinecraftMixin.java:40-44`: `beginRenderSubmit()` injected at `Minecraft.renderFrame` before renderer updates, emitting `VK_LATENCY_MARKER_RENDERSUBMIT_START_NV`. Correct placement.
  - `src/client/java/com/bambooz/reflexium/mixin/MinecraftMixin.java:46-56`: `beforePresent()` emits `VK_LATENCY_MARKER_RENDERSUBMIT_END_NV` and `PRESENT_START_NV` before `surface.present()`; `afterPresent()` emits `PRESENT_END_NV` after `surface.present()`. Correct placement for standard presentations.

### 5. Trigger Flash for Left Clicks
- **Status:** PASS
- **Requirement:** Click latency marker emitted on left-click during active simulation; no bogus latency flash hotkeys exposed in UI.
- **Evidence:**
  - `src/client/java/com/bambooz/reflexium/mixin/MouseHandlerReflexMixin.java:14-16`: Injected at `MouseHandler.onButton`; checks `action == 1` (press) and `button.button() == 1` (`InputConstants.MOUSE_BUTTON_LEFT`), calling `ReflexController.get().triggerFlash()`.
  - `src/client/java/com/bambooz/reflexium/reflex/ReflexController.java:265`: `triggerFlash()` emits `VK_LATENCY_MARKER_TRIGGER_FLASH_NV` only while `simulationOpen` is true.
  - No invalid flash hotkeys or unbacked flash screen overlays are exposed to users in configuration menus.

### 6. Out-of-Band Markers for Frame Generation
- **Status:** FAIL (Fixed in commit `bb80486`)
- **Requirement:** Out-of-band markers (`OUT_OF_BAND_PRESENT_START_NV` / `END_NV`, `OUT_OF_BAND_RENDERSUBMIT_START_NV` / `END_NV`) emitted exclusively for generated frames, never for the in-band real present.
- **Evidence:**
  - `src/client/java/com/bambooz/reflexium/reflex/ReflexController.java:257-262`:
    `public void beforeBatchPresent(boolean generated) { marker(VK_LATENCY_MARKER_OUT_OF_BAND_PRESENT_START_NV); }`
    `public void afterBatchPresent(boolean generated) { marker(VK_LATENCY_MARKER_OUT_OF_BAND_PRESENT_END_NV); }`
    The `generated` boolean parameter was ignored.
  - `src/client/java/com/bambooz/reflexium/mixin/VulkanGpuSurfaceMixin.java:149,155`:
    In a multi-frame present batch, the final present iteration (`i == count`) presents the real image (`reflexium$realView`) with `fg.isGeneratedPresent() == false`.
    Because `beforeBatchPresent` ignored the parameter, the real present in the batch was incorrectly marked with `VK_LATENCY_MARKER_OUT_OF_BAND_PRESENT_START_NV` and `OUT_OF_BAND_PRESENT_END_NV`.

### 7. Marker Order Regression and Stale Frame Logging
- **Status:** FAIL (Fixed in commit `1dd6d44`)
- **Requirement:** Detect and log marker order regressions and stale or mixed frame ID usage in debug mode.
- **Evidence:**
  - `src/client/java/com/bambooz/reflexium/reflex/ReflexController.java:518-528`:
    `marker(int marker)` pushed `presentID(frameId)` and called `vkSetLatencyMarkerNV` without tracking prior marker state or checking that the emitted marker followed the required sequence. Regressions were not detected or logged.

### 8. Frame ID Ownership
- **Status:** PASS
- **Requirement:** Exactly one fresh frame token/ID per engine frame; same token across sleep, markers, submit, and present; monotonic; no stale reuse.
- **Evidence:**
  - `src/client/java/com/bambooz/reflexium/reflex/ReflexController.java:181-185`: `beginFrame()` increments `frameId++` and sets `frameOpen = true`.
  - `src/client/java/com/bambooz/reflexium/reflex/ReflexController.java:166-176`: `currentFrameId()` returns `frameOpen ? frameId : 0`. `takePresentId()` gives `frameId` only once per frame via `lastPresentedId = frameId`.
  - `src/client/java/com/bambooz/reflexium/vulkan/VulkanLifecycle.java:137-148`: `chainPresentId` attaches `VkPresentIdKHR` with `takePresentId()`.
  - `src/client/java/com/bambooz/reflexium/vulkan/VulkanLifecycle.java:155-177`: `chainSubmissionIds` attaches `VkLatencySubmissionPresentIdNV` with `currentFrameId()` to the graphics queue.
  - Frame IDs monotonically increase across swapchain recreation, satisfying Vulkan `VkPresentIdKHR` requirements.

### 9. FPS Cap via minimumIntervalUs and Vanilla Limiter Coordination
- **Status:** PASS
- **Requirement:** Wire engine frame limit into `minimumIntervalUs`; compute ceiling microseconds; coordinate with vanilla limiter to skip duplicate post-present sleep.
- **Evidence:**
  - `src/client/java/com/bambooz/reflexium/reflex/ReflexController.java:272-283`: `updateFrameLimit()` converts target FPS to ceiling microseconds `(1_000_000L + fps - 1) / fps`. Uncapped mode uses 0.
  - `src/client/java/com/bambooz/reflexium/reflex/ReflexController.java:311-313`: `ownsFrameLimit()` returns `isSleeping() && pacing.ownsCap(mode(), requestedIntervalUs)`.
  - `src/client/java/com/bambooz/reflexium/mixin/MinecraftMixin.java:68-71`: `reflexium$skipVanillaLimiter` wraps `FramerateLimiter.limitDisplayFPS` and skips vanilla sleep only when `ownsFrameLimit()` is true.
  - `src/client/java/com/bambooz/reflexium/reflex/PacingState.java:42-44`: `ownsCap` verifies that `vkSetLatencySleepModeNV` completed with `interval >= requested` and at least one sleep call succeeded.

### 10. Mode Change Handling and Runtime Toggles
- **Status:** PASS
- **Requirement:** Call `vkSetLatencySleepModeNV` at startup and upon option changes; handle toggles dynamically without restart or crash.
- **Evidence:**
  - `src/client/java/com/bambooz/reflexium/reflex/ReflexController.java:99-106`: `onSwapchainCreated` resets pacing and invokes `applySleepMode()` even if Reflex is Off.
  - `src/client/java/com/bambooz/reflexium/reflex/ReflexController.java:156-161`: `setMode(ReflexMode mode)` resets pacing, resets timing floor, and calls `applySleepMode()`.
  - `src/client/java/com/bambooz/reflexium/reflex/PacingState.java:18-28`: `configuration` tracks applied mode and interval, clearing latched failure upon mode switch to allow recovery.

### 11. UI Availability and Unsupported GPU Fallback
- **Status:** FAIL (Fixed in commit `f3cf324`)
- **Requirement:** Reflex UI disabled when low latency feature is unsupported; fallback paths do not crash on unsupported hardware or missing drivers.
- **Evidence:**
  - `src/client/java/com/bambooz/reflexium/gui/ReflexiumConfigScreen.java:34-38`: The Reflex mode dropdown was added to the screen with `active = true` unconditionally, without checking `ReflexController.isSupported()` or `session.lowLatency2()`.
  - `src/client/java/com/bambooz/reflexium/compat/SodiumConfigIntegration.java:46-56`: The `reflex_mode` option in Sodium lacked an `setEnabledProvider`, remaining enabled on non-NVIDIA GPUs or drivers without `VK_NV_low_latency2`.
  - Runtime fallback did not crash: `onDeviceCreated` exits early when `!session.lowLatency2()`, `device` stays null, `isAvailable()` returns false, and `statusLine()` accurately reports `Reflex: unavailable (not an NVIDIA GPU)` or `driver lacks VK_NV_low_latency2`.

### 12. Interaction with Frame Generation
- **Status:** PASS
- **Requirement:** DLSS FG requires Reflex On; restore user preference when FG disabled; minimum interval limits total presented frames; FSR proxy handles bypass raw Reflex swapchain.
- **Evidence:**
  - `src/client/java/com/bambooz/reflexium/reflex/ReflexController.java:150-153`: `mode()` promotes `OFF` to `ON` when `FrameGenerationRenderer.get().needsReflex()` is true, preserving the user's saved preference in config.
  - `src/client/java/com/bambooz/reflexium/reflex/ReflexController.java:276-281`: Under FG, `frameGenerationCap` applies to presented frames directly and is not divided by the FG multiplier (verified 2026-10-16 on RTX 5070).
  - `src/client/java/com/bambooz/reflexium/vulkan/VulkanLifecycle.java:125-132`: `chainSwapchainLatency` checks `!FsrSwapchain.canCreate(info)` before attaching latency structures, avoiding interference with AMD swapchain proxy handles.

### 13. Telemetry and Latency Reporting
- **Status:** PASS
- **Requirement:** Latency report queried via `vkGetLatencyTimingsNV`; sanitizes invalid/inconsistent driver timestamps; rejects stale data.
- **Evidence:**
  - `src/client/java/com/bambooz/reflexium/reflex/ReflexController.java:569-601`: `readLatency()` queries 64 report frames, filtering by `presentID > timingFloorId && presentID <= frameId`, and invalidates reports older than 1 second.
  - `src/client/java/com/bambooz/reflexium/reflex/ReflexController.java:632-646`: `LatencyReport.from` rejects inverted driver timestamps (`driverStartTimeUs > driverEndTimeUs`), incomplete GPU spans, and non-positive starts.
  - `src/test/java/com/bambooz/reflexium/reflex/ReflexTimingCheck.java:91-105`: Unit tests verify latency computation, GPU completion bounds, and rejection of inverted driver reports.

---

## Applied Fixes and Verification

1. **Commit `a8f1fd3`**: Emit `INPUT_SAMPLE` before `SIMULATION_START` in Reflex frame loop.
   - Fixed marker order inversion in `ReflexController.beginFrame()`.
   - Updated class javadoc sequence diagram.

2. **Commit `bb80486`**: Emit `OUT_OF_BAND` present markers only for generated presents.
   - Gated `beforeBatchPresent` and `afterBatchPresent` on `generated == true`.
   - Prevents tagging the real frame present in a batch with out-of-band markers.

3. **Commit `1dd6d44`**: Add Reflex marker order regression tracking and logging.
   - Added in-band marker rank tracking per active frame in `ReflexController.java`.
   - Logs warnings and increments `markerOrderRegressions` counter upon any out-of-order marker call.

4. **Commit `f3cf324`**: Disable Reflex and Smart Reflex UI options on unsupported platforms.
   - Added `ReflexController.isSupported()` checking `lowLatency2()` capability and FSR swapchain scheduler state.
   - Disabled Reflex dropdown button and Smart Reflex toggle in `ReflexiumConfigScreen` when unsupported.
   - Added `setEnabledProvider` to Sodium configuration options.
