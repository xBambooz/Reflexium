# Frame generation and Reflex audit and implementation specification

Date: 2026-10-14. Status: corrections implemented; standalone checks passed; runtime and cadence acceptance incomplete. Evidence and remaining requirements: docs/validation/frame-generation-reflex-2026-10-14.md. Implementers: Codex and Antigravity (Gemini 3.8 Flash High).

## Scope, sequencing and project context

Ryan expanded the engine-native quality target beyond DLSS/FSR reconstruction to frame generation and Reflex. Implement this specification after the active UPSCALING_QUALITY_SPEC.md implementation has stopped, in the same checkout. Do not run concurrent writers on the shared renderer, mixins or debug server. The parent must start a NEW T3 delegated Sonnet 5.5 task for this phase, carrying the previous implementation results and review objections. Do not treat a childThreadId as a new delegated round. The first upscaling task ID is node:delegated-task:command%3Amcp%3A12343258-b29e-42c4-84b5-906768568bae%3Adelegate-task%3Areflexium-upscaling-quality-sonnet55-20261014-r1.

Target: correct engine integration and measured behavior, not an unsupported claim of reproducing hypothetical Mojang internals. Frame generation must improve delivered cadence without inventing game updates or corrupting HUD content. Reflex must pace before input, attribute real frames correctly, preserve user caps on failure, and report its actual state. Raw NGX and raw VK_NV_low_latency2 have different integration contracts from Streamline; use the correct one for each change.

Repository: C:/Users/Ryan/source/repos/Reflexium. Fabric 26.3, Java 25, renderpearl Vulkan, NGX 310.9.1 and AMD FSR 3.1.4 through native C++ bridges. JAVA_HOME=C:/Program Files/GraalVM/graalvm-jdk-25.0.4+7.1. .\\gradlew.bat build runs the assertion-based checks and installs the jar in Modrinth's 26.3 Testing Instance. native/build.cmd builds the bundled native bridges when C++ changes. Read global instructions, the ai-brain skills and INDEX, preferences and relevant Minecraft topic files, UPSCALING_QUALITY_SPEC.md and its implementation report, docs/FRAME_GENERATION.md, docs/FRAME_GENERATION_REFERENCE.md, docs/DEBUGGING.md, docs/PERFORMANCE.md, docs/REFLEX_UPSTREAM_AUDIT.md when present, and native/README.md.

Preserve all pre-existing edits and the preceding quality work. Do not revert concurrent or unrelated work. No repository push is requested. Parent will review and make the final repository-wide commit. No extra agents. No broad UI redesign. Use existing standalone assertion mains and the existing authenticated mc-agent-harness; no new testing/automation framework. Keep tokens and service secrets private. Verify exact owned test processes before shutdown.

## Automatic handoff authorized by Ryan

Ryan explicitly requested that this Sonnet implementation start automatically after the upscaling agent finishes. The existing T3 upscaling task's terminal notification wakes the parent thread. On that notification, read its result, review the actual source and verification evidence, address blocking upscaling defects, then call delegate_task for this specification without asking Ryan to prompt or approve again. Use providerInstanceId=claudeAgent, model=claude-sonnet-5-5, role=implementation, full-access runtime, and clientRequestId=reflexium-fg-reflex-sonnet55-20261014-r1 (stable across retries). Include the upscaling task's original brief, final results, parent findings and unresolved objections in the new prompt. Retain the new returned taskId. Do not start the second implementation while the first writer or its live GPU validation remains active. Do not create a periodic scheduler or a polling watcher for this dependency. A terminal task with unfinished child runs is not ready for handoff. If the first implementation fails or is interrupted, recover/review it before starting dependent renderer work.

## Primary references and prior evidence

- Khronos explicit submission attribution: https://docs.vulkan.org/refpages/latest/refpages/source/VkLatencySubmissionPresentIdNV.html . A missing tag inherits previously tracked attribution; zero clears a queue. Explicit attribution is respected only at extension specVersion >=3.
- Khronos marker ordering and intermediate FG layers: https://docs.vulkan.org/refpages/latest/refpages/source/VkLatencyMarkerNV.html and https://docs.vulkan.org/spec/latest/chapters/VK_KHR_surface/wsi.html#low-latency-frame-attribution . Intermediate layers should retain application PRESENT_START/END and surround physical deferred/replacement presents with OUT_OF_BAND_PRESENT markers.
- Khronos swapchain limits: https://docs.vulkan.org/refpages/latest/refpages/source/VkSwapchainCreateInfoKHR.html . VUID minImageCount-01272 requires respecting nonzero surface maxImageCount.
- Khronos metering: https://docs.vulkan.org/refpages/latest/refpages/source/VkSetPresentConfigNV.html . Configuration governs the next numFramesPerBatch present calls, at most eight; feedback is a success/error result.
- NVIDIA Reflex integration/QA: https://github.com/NVIDIA-RTX/Streamline/blob/main/docs/ProgrammingGuideReflex.md . The application calls sleep and sends markers even in Off in the reference contract; identify which requirements apply to our raw Vulkan path. Verification tools, trigger-flash and PC-latency telemetry are separate from the driver's render-latency timestamps.
- NVIDIA's actual Vulkan reference implementation: https://github.com/NVIDIA-RTX/Streamline/blob/main/source/platforms/sl.chi/vknvll2.cpp . Read code, including state-change error handling, device-lifetime semaphore values, sleep, swapchain lifetime and OOB queues. Do not copy D3D-only waitable-swapchain logic.
- NVIDIA FG guide: https://github.com/NVIDIA-RTX/Streamline/blob/main/docs/ProgrammingGuideDLSS_G.md and local native/deps/DLSS/include/nvsdk_ngx_params_dlssg.h. Static raw-NGX MFG on Vulkan works in the tested device; Streamline Dynamic MFG and FG plus VSync have different platform restrictions. Do not invent Vulkan support from D3D12 behavior.
- AMD FG manual: https://gpuopen.com/manuals/fidelityfx_sdk/techniques/super-resolution-interpolation/ and installed native/deps/FidelityFX-SDK-v1.1.4/ffx-api/include/ffx_api/ffx_framegeneration.h. The SDK provides frame-specific callback user contexts; allowAsyncWorkloads=false only disables async compute, not necessarily every presentation worker.
- Modrinth/source strategy research is already recorded for Radiance/MCVR in UPSCALING_QUALITY_SPEC.md and docs/FRAME_GENERATION.md. It is a reference for real motion inputs and UI separation, not GPL source to copy.

Important existing evidence: the RTX 5070/617.42 native reference reproduces a large high-FPS Reflex On penalty and explicit-ID timestamp inversions without Minecraft, Java or NGX. Read the retained native/API-dump captures before proposing a fix. No safe production throughput fix has yet been demonstrated. Do not hide the penalty by turning Reflex off, forcing an arbitrary minimum interval, removing safe resource waits or relabeling GPU completion as input-to-photon latency. Do not generalize one hardware/driver result to all shipping games or a universal fixed FPS ceiling.

## 1. Failed Reflex mode application is recorded as success (confirmed bug)

Source: src/client/java/com/bambooz/reflexium/reflex/ReflexController.java, applySleepMode at approximately 344-374. vkSetLatencySleepModeNV failure is logged, but appliedMode and appliedIntervalUs are updated unconditionally. isSleeping uses requested state rather than a successful applied state, and ownsFrameLimit can consequently suppress vanilla's limiter although driver pacing was not established.

Only commit applied state after success. Distinguish requested mode, successfully applied mode and actual pacing health. On failure preserve the last known valid state where safe, make failure observable, and give the FPS cap back to vanilla when driver pacing cannot be relied on. Provide a bounded retry policy on explicit mode/swapchain lifecycle changes without spamming every frame. A stale appliedMode must not prevent recovery. Never report On or cap ownership solely because those settings were requested.

Leave a deterministic failure-path check exercising success, configuration failure, fallback cap ownership and recovery. A fake return-code seam or small pure state helper is acceptable; no general Vulkan wrapper framework is required.

## 2. Sleep timeout and lifecycle failure handling (confirmed gap)

Source: ReflexController.sleep at approximately 378-396; shutdown/onDeviceCreated/onSwapchainCreated; isSleeping and ownsFrameLimit. VK_TIMEOUT from the 100 ms wait is explicitly treated as benign and never changes health or cap-ownership state. sleepFailed is reset in setMode, but not in shutdown/onDeviceCreated, so an old failure can survive into a new device.

Track the outcome of the actual sleep/wait separately from configuration success. A timeout need not be treated as device loss, but it must be visible and must not count as a successful completion proof. Preserve bounded waits and safe fallback pacing; repeated timeouts should not hang the client or cause it to suppress a limiter it is not replacing. Clear per-device/per-swapchain failure and timing state at the correct lifecycle boundaries. Maintain strictly increasing device-lifetime timeline values across swapchain changes. Do not destroy/reuse a timeline semaphore while a signal operation can still reference it; handle timeout/shutdown ordering deliberately.

Test timeout, hard failure, repeated frames, new swapchain, new device and mode retry. Check Off/On/Boost transitions. Keep real resource-safety waits intact and avoid device-idle calls in the ordinary frame loop.

## 3. Unconditional eight-image swapchain override violates surface limits (confirmed portability bug)

Source: src/client/java/com/bambooz/reflexium/vulkan/VulkanLifecycle.java:67-70, chainSwapchainLatency. info.minImageCount(max(original,8)) occurs before checking whether low-latency support even exists, with no surface maxImageCount check. This can invalidate an otherwise valid vanilla swapchain on a surface capped below eight images, including non-NVIDIA devices.

Respect physical surface min/max bounds, including maxImageCount=0 meaning unbounded. Preserve native defaults unless a verified active route actually requires a different count. Do not confuse maximum MFG batch length with a proven minimum swapchain image count. Compose existing pNext data and keep FSR proxy and native handles distinct. A failed optional FG prerequisite must fall back to a valid native configuration, not make device/window startup fail.

Test caps such as min=2/max=3, min=2/max=0, FG inactive, missing Reflex and the native/AMD route. Validate the actual create info under a Vulkan validation layer and live resize/monitor changes. Check requested versus actual image counts separately.

## 4. Generated submission attribution is not cleared, and marker scope differs from the intermediate-layer contract

Sources: VulkanLifecycle.chainSubmissionIds at approximately 94-118 returns immediately for isGeneratedPresent; ReflexController.beforePresent/afterPresent suppress application present markers for batches; VulkanGpuSurfaceMixin.reflexium$tagPresent emits OOB markers for generated presents but normal markers only around the final real present.

Skipping a VkLatencySubmissionPresentIdNV structure does not make a submission unassociated once the queue has entered explicit mode; it inherits the previous tracked ID. The current comment/behavior therefore does not establish the intended separation. The normal application present span is also replaced by per-physical-present markers, rather than retained around the app-owned logical presentation operation.

Implement a coherent application-versus-intermediate-FG attribution model from the Khronos contract. Preserve normal logical application markers and use correct OOB markers for the deferred/replacement physical presents and any appropriate OOB render submission spans. If explicit tagging is used, deliberately clear or supply attribution according to the documented queue rules; do not assume zero means unrelated work while another queue still keeps the device in explicit mode. Do not mark the game's only graphics queue permanently OOB without analyzing its real-frame submits. Keep pNext composition and monotonic IDs intact. Generated presents must not consume the real frame's VkPresentIdKHR. Close frame state at an explicit correct boundary and avoid attributing unrelated between-frame lifecycle work to a stale open frame.

Record a trace of multiple consecutive frames for FG off, 2x and highest supported MFG. Include logical frame ID, markers, each submit tag, each physical present and the real-versus-generated role. Verify actual API order, not only a standalone state-machine mock. Preserve the existing native reference evidence if the driver still reports inversions after corrected app semantics.

## 5. Explicit attribution does not record the extension revision (confirmed omission)

Sources: DeviceExtensions.java, VulkanSession.java and VulkanLifecycle.chainSubmissionIds. Device setup records only lowLatency2=true/false, not the advertised VkExtensionProperties.specVersion. Yet the path relies on explicit submission attribution, which is ignored before revision 3.

Query and carry the actual revision, distinguish extension availability from explicit-attribution support, and use valid implicit behavior when explicit attribution is unsupported. Clear tracked IDs on all previously tagged queues when switching attribution policy. Preserve existing diagnostic explicit/implicit A/B controls and label which policy actually took effect. Most apps do not need explicit tagging; choose the production policy based on correctness and measured evidence rather than complexity for its own sake.

Test revisions 1/2/3, missing extension and device recreation. Do not call a driver error an integration fix merely because a diagnostic can hide its invalid fields.

## 6. FG metering feedback is collected but not acted on (confirmed gap)

Source: dlss/FrameGenerationRenderer.java, chainMetering/onPresentResult. presentConfigFeedback is stored but does not affect capability or failure handling. The UI can continue implying active metered generation even if pacing configuration is rejected. Partial batches, errors and resizing can also diverge from the requested multiplier.

Interpret feedback according to the actual installed extension definitions. Preserve one config per batch and the eight-frame limit. On unsupported/rejected pacing or interrupted batches, transition safely to ordinary rendered presentation or a supported scheduler and explain the actual state. Do not keep claiming smooth metered delivery because vkQueuePresentKHR returned success. Never fabricate additional presents to complete a batch after the swapchain is invalid.

Add checks for accepted/rejected feedback, first present failure, middle-batch out-of-date, suboptimal swapchain, resize, menu suppression and retry. Record successful real/generated present submissions separately from requested/evaluated outputs and actual display feedback.

## 7. Displayed FPS is estimated from settings, even when generation is suppressed (confirmed reporting bug)

Sources: FrameGenerationRenderer.displayMultiplier, skipGeneratedFrames, invalidate and begin/endPresentBatch; MinecraftMixin.getFps; debug/FpsText.java; docs/FRAME_GENERATION.md. displayMultiplier returns two whenever the FSR proxy is active, or the selected/requested multiplier when no active batch exists. skipGeneratedFrames only clears ready. Consequently menus, resets, warmup or failed/partial delivery can still show multiplied FPS. Successful present submission counts also do not establish physical display scanout.

Preserve Ryan's existing dual FPS display format, but derive real/generated delivery counters from observed successful operations over a matching time window. Reset counters/state on suppression and route changes as appropriate. Distinguish rendered FPS, generated/submitted presentation rate, and measured displayed rate. If display telemetry is unavailable, identify the estimate rather than calling it measured display FPS. Do not replace Minecraft's simulation/frame-count semantics globally in ways that affect other mods' timing logic; audit every getFps caller and the BetterF3 compatibility path.

Test FG enabled with no world, blur menu open, initial history frame, mode change, disabled generation, failed batch, AMD route and normal steady MFG. Actual display cadence and dropped frames require the independent capture in item 12.

## 8. AMD scheduler callback owns only one mutable image descriptor (source-confirmed lifetime risk, runtime sequencing must be established)

Source: native/fsr_swapchain.h:13-27 and 47-59. Every schedule call overwrites global scGenerated; copyGenerated reads that global and ignores its user-context parameter. One generated TextureTarget is also reused. allowAsyncWorkloads=false disables async compute; it is not by itself a lifetime or thread-synchronization guarantee for presentation workers. The SDK header exposes frameGenerationCallbackUserContext and specifies frame IDs increment by exactly one.

Determine callback execution/lifetime using the installed API contract and instrumentation. Supply frame-specific immutable image/frame metadata through the actual callback context, or prove bounded synchronous consumption before mutation. Ensure the image itself stays alive and unmodified until all consumers finish. Prefer the SDK's completion/lifetime model and a small bounded in-flight pool, not a global lock or device idle every frame. Honor exact frame-ID progression, resets and same-frame configure/prepare/dispatch identity. Keep the three private queues disjoint from the app queue and externally synchronized where required. Preserve format conversion and exactly one presentation Y flip.

Propagate errors currently discarded by rx_fsr_sc_wait/pause/destroy as appropriate instead of declaring resources safe on unchecked failure. Audit create/destroy/recreate, all five replacement swapchain operations, oldSwapchain handoff and pNext compatibility. Do not indiscriminately drop other mods' compatible swapchain structures just to remove raw-Reflex data from the opaque proxy.

Validate the forced AMD scheduler route on the available NVIDIA hardware, including rapid toggles/resize/quit/rejoin and callback frame IDs. Explicitly retain the limitation that this does not validate AMD or Intel hardware.

## 9. Semaphore-ring and resource-reuse correctness need proof, not a larger magic number

Source: VulkanGpuSurfaceMixin expands acquireSemaphores to 16 at construction; per-generated presentation calls acquireNextTexture, encoder.submit and present. Other generated targets, uniforms and feature contexts have their own lifetimes.

Trace the actual RenderPearl acquire/wait/submit and reuse rules. A 16-element ring alone does not establish that every binary semaphore is unsignaled and no wait still references it when it is reused. Tie reuse to an actual completion invariant, handle partial allocation failure without leaking handles, and keep the native/off route valid. Do not remove the renderer's fence/resource waits for speed without replacing their safety guarantee. Investigate the measured FG extra-submit/resource-completion serialization with per-pass CPU/GPU spans; batching or reusing command resources is acceptable only if the same safety is preserved.

Run Khronos core and synchronization validation for steady 2x/highest MFG, failure/recreate paths and the forced AMD scheduler. Retain real warnings/errors and relevant VUIDs. A smooth screenshot or an FPS increase is not synchronization evidence.

## 10. Reflex caps, input timing and user preference need a full lifecycle audit

Sources: ReflexController.beginFrame/updateFrameLimit/ownsFrameLimit/SmartPacing, MinecraftMixin.run/renderFrame hooks, config/ReflexiumConfig.java. Audit one sleep call per actual application frame, before SDL polling, then simulation/input/render-submit/present markers at their actual boundaries. Do not duplicate sleeps for generated frames or increase game ticks/input samples with FG.

Compare the raw Vulkan implementation with the official reference's Off/On/Boost behavior, including whether sleep must still be called in Off for the chosen API's reporting contract. Keep requested and effective Reflex distinct when NVIDIA FG requires On, restore the user's saved preference when that requirement ends, and never silently disable On at high FPS to pass a benchmark. Smart Reflex is an explicit optional policy and defaults off; preserve that distinction.

Keep exactly one cap owner. On driver pacing failure, timeout, unavailable swapchain, proxy-route change or device recreation, restore the native limiter safely. Preserve menu/AFK/minimized caps and accurately label VRR headroom as a heuristic when VRR engagement is not detected. Check monitor moves and refresh changes. FPS cap units must be clear when output contains generated frames; do not silently reinterpret an existing base-frame cap as a display cap. Preserve no-cap behavior. Use rounding that does not defeat the intended maximum.

Test Off/On/Boost, explicit caps, VSync states supported by each route, monitor/resize changes, menus, sleep failure and FG toggles. NVIDIA Vulkan FG requiring VSync off is an actual backend restriction, not something to bypass to imitate another title's D3D path.

## 11. Complete telemetry and latency verification without overstating it

Sources: ReflexController.LatencyReport, timingReports and latencyLine; FrameProfiler; debug status; tools/reflex-reference.cpp and docs/PERFORMANCE.md. Current software latency is input sample/simulation start to GPU completion; it does not include mouse hardware, scanout or panel response. Driver present return is not GPU completion. Retain rejection of incomplete/inverted reports, and add staleness/frame-correlation handling so an old good report cannot masquerade as current latency after a route or mode change.

Audit trigger-flash markers and vendor verification-tool support, including actual left-click integration at the right simulation boundary. Identify PCL/Reflex verification capabilities missing from the raw Vulkan path. Implement supported telemetry with the correct contract, rather than renaming render latency to PC latency. A click-to-photon claim requires an independent appropriate hardware or software measurement method and its limitations.

Measure performance with instrumentation off for the headline result, and use separate sparse CPU/GPU/API traces for attribution. Retain the known independent 617.42 oversleep reproduction; report unresolved driver/OS behavior honestly. Compare CPU-limited and GPU-limited scenes, Off/On/Boost and FG combinations. Do not present an arbitrary fixed interval or timer-resolution tweak as a verified solution. A first-party-quality target means passing applicable vendor QA, not only avoiding Java exceptions.

## 12. Actual FG cadence and visual acceptance (required delivery evidence)

Existing tools/check-frame-generation.ps1 verifies evaluation/present counts and settings combinations. Extend/reuse it, but keep functional acceptance distinct from delivered cadence and image quality. Use an isolated 1080p secondary-monitor harness with real terrain loaded. No second benchmark/game may contend for the GPU during headline captures; inspect parent benchmark processes too.

Cover FG off, DLSS 2x, highest supported static MFG and FSR 2x, plus relevant upscaler combinations allowed by settings. Capture pan/strafe, moving mobs and animated hands, disocclusions, thin geometry, particles, water, crosshair INVERT blending, chat/hotbar text, menus and screen blur. Check first/last generated frame chronology and spacing. Preserve prepared-GUI replay behavior that handles destination-dependent blending; replacing it with one transparent overlay is not equivalent. Investigate supported per-output UI animation without pretending replaying one simulation's draw list creates new game/input updates.

Use a functioning FrameView/PresentMon/ETW or equivalent display-feedback capture to measure presentation cadence, dropped/repeated frames and base versus generated output, with exact method/limitations recorded. Inspect why the prior CLI produced no capture rather than interpreting an empty file as a pass. If physical scanout cannot be observed, explicitly leave that acceptance unresolved; do not substitute CPU submission timestamps, a multiplied counter or library-success return codes. Do not ship an unsupported dynamic multiplier algorithm just to match monitor Hz.

Run .\\gradlew.bat build and relevant existing checks, rebuild native bridges after C++/ABI changes, and verify the installed jar contains the updated DLLs. Record all new nontrivial failure/lifecycle logic in one or more small existing-style runnable checks. Restore saved settings, unfreeze server ticks after motion tests, and shut down only the verified owned test process.

Write docs/validation/frame-generation-reflex-2026-10-14.md with the outcome of every numbered item, confirmed fixes versus investigated risks, exact commands, artifacts, measured results and unresolved requirements. Correct stale claims in docs/FRAME_GENERATION.md, docs/PERFORMANCE.md, docs/DEBUGGING.md and shared brain facts as appropriate. Reference the preceding upscaling work, because FG cannot be fully correct with incorrect depth/motion inputs. Parent reviews the integrated diff, tests and evidence, then commits all finished work. Do not claim the full engine-native target is achieved while any required input, pacing or validation contract remains unmet.
