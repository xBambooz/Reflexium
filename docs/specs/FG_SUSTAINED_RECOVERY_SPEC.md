# Sustained frame generation recovery

Date: 2026-10-15. Status: researched implementation handoff; runtime cause unconfirmed.

## Context and ownership

Reflexium is a Java 25 Fabric mod for Minecraft 26.3 using RenderPearl Vulkan, NGX DLSS FG/MFG and FidelityFX FG. Build with `.\gradlew.bat build --console=plain`; this also installs the jar into the Modrinth `26.3 Testing Instance/mods` directory. Existing standalone checks include `FrameDeliveryCheck`. Read the shared ai-brain INDEX, preferences, `minecraft/vulkan-frame-generation.md`, `minecraft/fps-lab.md`, this file, `FRAME_GENERATION_REFLEX_SPEC.md`, and `docs/DEBUGGING.md` first. No repository-root AGENTS.md or CLAUDE.md exists at handoff.

The user requests Opus 5.5 Low implementation, followed by Haiku benchmark operation, comparing with live results. Implement in the current worktree, preserving existing changes. No other game JVM was running at handoff. Recheck GPU ownership before launching. Never terminate another benchmark owner. Keep historical captures and pinned jars immutable. The current uncommitted DeviceExtensions heap-allocation fix is required and must be retained. Do not change upscaling algorithms or native synchronization merely to address this feedback issue.

Order: preserve baseline, add diagnostic evidence and confirm the failure, implement correction, run contract/build checks, delegate isolated BM runs to Haiku, review results, retain a validation report and commit completed changes. Escalate contradictory runtime evidence to the parent, not another speculative workaround.

## 1. Permanent failure from unreported feedback (high)

Files: `src/client/java/com/bambooz/reflexium/dlss/FrameGenerationRenderer.java:205-238`, `src/client/java/com/bambooz/reflexium/mixin/VulkanGpuSurfaceMixin.java:78-110,126-155`, `src/test/java/com/bambooz/reflexium/dlss/FrameDeliveryCheck.java:28-38`.

Verified source chain: chainMetering seeds `presentConfigFeedback(-1)`. onPresentResult increments delivered counters for success/suboptimal, then treats every feedback other than zero as failure, setting `failed=true`. wantsMotion (line 65) then disables evaluation indefinitely until retry. The surface loop abandons interpolation and sends the real image when batchRejected. A benchmark mode change calls retry through DebugServer lines 603/609. This precisely permits one generated present per mode if the driver leaves feedback unchanged, but no retained raw feedback yet proves the driver does so. The existing test incorrectly conflates the initialized value and an observed error.

Primary sources reviewed:

- https://docs.vulkan.org/refpages/latest/refpages/source/VkSetPresentConfigNV.html specifies uint32 feedback and the next N present calls, with N <= 8. It does not enumerate feedback values or an unwritten sentinel. Do not claim it explicitly permits omitted writes or defines these values as VkResult.
- https://docs.vulkan.org/refpages/latest/refpages/source/VK_NV_present_metering.html identifies provisional revision 1.
- `build/VulkanGpuSurface.java:401-423` confirms an enclosing MemoryStack push spans the wrapped vkQueuePresentKHR and feedback read. Existing allocation lifetime is valid in this implementation; no persistent allocation is needed.
- Modrinth search found Salt's Anti-Aliasing (FSR scheduler) and Radiance. Radiance's public main tree exposes SR module configuration, not a directly reusable NVIDIA metering implementation. Do not describe these as proof of NVIDIA feedback semantics.

Implementation:

1. Preserve the existing candidate `build/perf-jars/08381e6.jar` (SHA-256 `72a34f52a4a59633cdbf903e90b4b3c57004ab7cf8bae63cac0dc50e0c48740d`) and old working baseline referenced in overnight protocol files. A filename is not build identity.
2. Add bounded first-feedback/rejection logging and debug status counters before relying on the diagnosis. Record seeded raw value, post-call unsigned raw value, VkResult, batch size, reset versus batch, and permanent failure state. Avoid per-frame logs. A short diagnostic run of the original logic should establish whether -1 survives and causes the latch. Haiku may operate this run using exact instructions from Opus. Do not run GPU diagnostics concurrently with BM.
3. Use a named sentinel distinct from known VkResult values, e.g. `Integer.MAX_VALUE`. An unchanged sentinel means unreported, not confirmed success and not a driver rejection. Retain separate unknown/accepted/rejected counters; expose them with the existing debug status. Raw unsigned feedback must be available.
4. Treat explicit nonzero, non-sentinel feedback conservatively as rejection, separately from vkQueuePresentKHR's result. Abandon the current batch, request a single-frame metering reset, invalidate history, and retain the real-frame fallback. Do not set the permanent NGX/evaluation failure latch for a transient metering response. Keep the error visible even though `failed` is false. Bound warning frequency. Persistent rejection must remain observable and must fail benchmark acceptance; never claim recovery just because retries continue.
5. Read reset feedback too, including non-batch presents. Ensure a failed reset remains pending and cannot silently be consumed as the first configuration of a new generated batch. Distinguish reset configurations from actual generation batches in counters. Preserve the pNext chain.
6. Preserve VkResult semantics: success and SUBOPTIMAL count as delivered submissions; non-success abandons a batch and invalidates. OUT_OF_DATE must not force another present on the invalid surface. Do not count errors as delivery.
7. Cover unchanged sentinel, zero, explicit 1 and unsigned 0xFFFFFFFE, first/mid-batch errors, SUBOPTIMAL, reset configuration/feedback, repeated rejection, and a successful later batch without calling retry. Check the permanent latch stays clear after metering rejection using the existing minimal test style; wantsMotion requires valid config/session/runtime, so do not write a meaningless assertion against an uninitialized singleton. Exercise real renderer methods, not a duplicated predicate.

Unknown feedback permits continued submission but cannot establish successful pacing. Report that distinction explicitly. If the original diagnostic reports zero or another error, revise the causal conclusion and investigate before calling the fix validated. Other gates to inspect are sceneReady's Reflex isSleeping check (line 95), history resets and NGX evaluation status. Native evaluation exceptions remain permanent failures.

## 2. One generated frame passes BM acceptance (high)

Files: `tools/bm-quick-bench.ps1:343-390`; `src/client/java/com/bambooz/reflexium/debug/DebugServer.java:423-434`.

The current gate checks only inequality of evaluated/generated counters. Historical `build/bm-bench/overnight-08381e6-{A,B}/runs.jsonl` candidate captures each added ONE generated present and were accepted. Those rows cannot substantiate FG throughput gains.

Persist complete before/after evaluated, generated and real-present counters, status/failure, feedback counters, requested/effective multiplier and artifact hashes for each capture, including invalid captures. Calculate deltas, never cumulative totals. Require substantial sustained generation at the expected (multiplier - 1) generated frames per eligible real frame. Separate cuts/menu/warmup from steady generation: prefer an existing capture boundary or lightweight renderer counters for eligible batches and completed batches; do not add render-thread HTTP polling during uninstrumented measurements. Use at least two bounded diagnostic steady windows for functional acceptance, then keep throughput captures uninstrumented. A coarse whole-capture ratio can be a conservative rejection gate but cannot by itself prove continuous delivery. Document any tolerance and denominator.

Expand the runner's existing -SelfCheck with zero/one generated frame rejection, correct 2x/maximum ratios, stopped-mid-capture behavior and missing old-build fields. Do not fabricate new telemetry for historical jars. An old broken arm should be retained as expected-invalid evidence while allowing the fixed arm to run; it must not enter a valid FG performance comparison.

## Verification and benchmark handoff

Run `.\gradlew.bat build --console=plain` and `powershell -NoProfile -ExecutionPolicy Bypass -File tools/bm-quick-bench.ps1 -SelfCheck`. Check installed jar hash against the built jar. Pin the new jar under a unique filename. Build before captures, never during them.

Opus must leave a concrete Haiku run brief with actual pinned jar paths/hashes, exact commands, output paths, mode selection, expected counters, and owned process cleanup. Delegate through T3 `delegate_task` to `claudeAgent`, `claude-haiku-5-5` (low effort); user explicitly authorized this. Do not use top-level threads or a fabricated model-switch command. Return the task ID and evidence locations to the parent.

Use the existing frozen FPS Lab route and the runner's -Quick mode, limited to native/control, fg2 and the supported maximum MFG mode. Include Set B shader coverage. Inspect the runner mode names before issuing commands. Keep interactive runs around eight minutes, not full overnight ABBA. Never replace the frozen scene. Preserve world hashes and distinguish Quick evidence from final multi-block performance acceptance.

Read the existing live FPS Lab dataset and its dated archives to compare method, scene, jars and settings. The October 15 BM dataset marks broken sustained-FG rows invalid; October 14 legacy matrix has a different method. Compare fixed FG against a working matched baseline, and show the broken candidate only as a regression reproduction. Report base FPS, lows, generated/real submission ratios and feedback status separately. Never infer scanout or measured latency by multiplying FPS. Do not publish or overwrite live data solely to perform a comparison.

Write `docs/validation/fg-sustained-recovery-2026-10-15.md` with actual diagnosis, exact checks, jar hashes, run paths, comparison and remaining limits. If validation exposes another failure, report it and continue the investigation rather than accepting a counter increase. Update reusable brain facts only when supported, commit/push brain updates, then commit repository changes per standing workflow. Parent reviews the implementation and results before declaring completion.
