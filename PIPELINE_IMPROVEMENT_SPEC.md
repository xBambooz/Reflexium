# Reflexium pipeline improvements: Opus implementation brief

Prepared 2026-10-15 against Reflexium `565c3c3`. Intended implementer: Opus. This is the master brief for all four remaining pipeline stages, not a request to repeat completed fixes. Read it fully before editing. Implement stages in order, retain evidence, and publish truthful results to FPS Lab. Do not stop after the benchmark work: the original product problem is blurred detail, especially in motion.

## Project and working contract

Reflexium is a Minecraft 26.3 Fabric client mod, Java 25, using RenderPearl Vulkan, native NVIDIA NGX DLSS and AMD FSR bridges. Optional Sodium 0.9.2 and Vitrail 0.12.0-beta integration supports Complementary Reimagined r5.9.3. Current evidence comes from Windows 11, RTX 5070, NVIDIA 617.42. Verify the live versions before collecting new evidence.

- Repository: `C:/Users/Ryan/source/repos/Reflexium`.
- Benchmark Mod and FPS Lab exporter: `C:/Users/Ryan/source/repos/Benchmark Mod`.
- Background client harness: `C:/Users/Ryan/source/repos/mc-agent-harness`.
- Frozen scene: `C:/Users/Ryan/Documents/Minecraft FPS Lab/baseline/scene`.
- Java: `C:/Program Files/GraalVM/graalvm-jdk-25.0.4+7.1`.
- Build: `$env:JAVA_HOME='C:/Program Files/GraalVM/graalvm-jdk-25.0.4+7.1'; .\gradlew.bat build --console=plain`. Build also installs into the Modrinth `26.3 Testing Instance`; pin benchmark jars before rebuilding. Use the existing native build instructions in `native/README.md` if the ABI changes.
- Main runtime flow: `MinecraftMixin` and `GameRendererMixin`, `dlss/UpscalingRenderer`, `reflex/ReflexController`, `dlss/FrameGenerationRenderer`, and Vulkan surface/queue mixins under `src/client/java/com/bambooz/reflexium/`.

Read `C:/Users/Ryan/Documents/ai-brain/INDEX.md`, preferences, project locations/infrastructure, `minecraft/fps-lab.md`, `minecraft/vulkan-frame-generation.md`, `minecraft/26x-fabric-api.md`, and `minecraft/agent-harness.md`. Read any current AGENTS.md/CLAUDE.md in every edited repository. There was no root instruction file in Reflexium at preparation time. Benchmark Mod has its own runtime-test ownership rules; follow them if changing that mod. Existing Reflexium evidence uses its isolated harness workflow, not the shared Modrinth profile.

Read these local documents before implementation:

1. `docs/validation/benchmark-gates-2026-10-15.md` and `docs/validation/shaderless-performance-2026-10-15.md`.
2. `docs/validation/fg-sustained-recovery-2026-10-15.md`, `docs/validation/frame-generation-reflex-2026-10-14.md`.
3. `UPSCALING_QUALITY_SPEC.md` and `docs/validation/upscaling-quality-2026-10-14.md`, particularly outcomes 10 and 11.
4. `docs/PERFORMANCE.md`, `docs/DEBUGGING.md`, `docs/VITRAIL.md`, `docs/benchmarks/README.md`.
5. Older `SHADERLESS_PERFORMANCE_SPEC.md` and `FRAME_GENERATION_REFLEX_SPEC.md` for evidence, not their superseded ownership instructions. This brief owns the new work.

Use ai-brain, ponytail and fabric-modding workflows where applicable. No new renderer framework, generic telemetry framework or dependencies without demonstrated need. Keep one GPU owner at a time. Do not kill another agent's client or benchmark parent. Preserve foreground focus, authenticated harness shutdown and frozen world hashes. Check the actual working tree and running task ownership before edits. No model delegation is required by this brief; if repository instructions require a particular runtime worker, use that worker and keep GPU runs sequential.

## Starting state: preserve these completed changes

| Area | Current evidence | Remaining work |
| --- | --- | --- |
| Shaderless native | `f480b8f` restores preferred eight swapchain images while respecting surface min/max. Opposite-order Quick comparisons recovered about 9.4% and 10.1%. | Full ABBA, interactive transitions, latency impact. |
| Reflex recovery | Pending sleep signals suspend pacing and can recover; permanent failures retain safe fallback. | Capture must distinguish requested, applied, suspended and recovered states throughout measurement. |
| Runner validation | `565c3c3`: protocol v2, requested/applied Reflex checks, capture-local failure logs, timeout-counter deltas, 90% coarse FG threshold, optional bounded trace. | Exact measured-pass FG/pacing accounting. A 90% whole-capture threshold is not certification. |
| FG2 wait | Earlier approximately 0.65 ms semaphore wait; same pinned jar now measured 0.172 ms. | Correlated evidence explaining variation; no established fix. |
| Motion quality | Material mip bias, DLSS jitter phases, camera metadata, history resets, opaque object/hand vectors and exact Complementary final-resolve bypass already implemented. | Early HDR reconstruction; transparent and shader-deformed surface handling. |

Pinned retained runtime: `build/perf-jars/shaderless-final.jar`, SHA-256 `CF8379169AE71666FF6796550BFB5276B664CA28CB495FD39EF70A3B96F440F6`. Immediate historical comparison `shaderless-c0.jar`: `9942B5F0DB1E0764AA50EA796D65EBFC1CC985F9602B8F08A5F2D6EABFEEA8BE`. Verify both on disk. Names alone do not identify contents.

Fresh diagnostic evidence: `docs/benchmarks/bm/validation-trace-20261015/`. Native, Transformer Performance and FG2 each retained 32 complete marker/submit/present frames. FG2 had steady generated/real ratios 1.0 and 1.0, whole-capture 0.93597, SR GPU evaluation 0.639 ms, FG GPU evaluation 1.400 ms, and 2.553 ms total CPU submit-including-wait across two invocations per real frame. These spans overlap. Transformer separately spent about 1.004 ms inside the Reflex driver call. Profiles precede the trace, so they are not correlated by frame ID. Trace submit entries count batches, not API calls. No new performance gain is established by these instrumented observations.

## Stage 1: finish benchmark validation [high priority]

### Problem and verified source locations

`tools/bm-quick-bench.ps1`, functions `FgSnapshot`, `FgDelta`, `FgAcceptance`, `ReflexAcceptance`, and the capture loop: before/after snapshots surround menu, loading, warmup and measurement. The real-present denominator therefore includes frames excluded from BM's measured intervals. Diagnostic windows use wall-clock offsets after start rather than pass boundaries. Old jars without `fg_real_presents` fall back to BM frame counts, which are only an upper-bound ratio because numerator and denominator cover different intervals.

`src/test/java/com/bambooz/reflexium/debug/BmDriver.java:18-43` starts BM through its real controller but exposes no measurement boundaries. In Benchmark Mod, `src/client/java/com/benchmarkmod/client/run/RunController.java:169-178` starts `PassCapture` at `onFrameStart`; around lines 244-247 it finishes a pass after leaving MEASURING. `client/capture/PassCapture.java:108-123` finishes the real-frame series. These are the boundary sources to inspect, not UI labels or a guessed delay.

### Implementation

1. Trace BM's actual first/last measured-frame inclusion and the corresponding present ordering. Attach minimal optional test instrumentation at these boundaries, preferably in the existing separate BM driver jar if a narrow hook is available. If a small Benchmark Mod extension is necessary, keep Reflexium optional and generic capture data unaffected. Do not poll HTTP or write per-frame logs on headline runs.
2. Record a per-pass tuple: capture label, pass index, first/last application frame IDs, real presents, successful generated presents, evaluated FG frames, expected effective multiplier, rejected/failed presents, pacing failures/suspensions/timeouts, and explicit history-cut/skip reasons. Use actual delivered/submitted counters with defined meanings, not configured multiplier times FPS. Capture boundaries must be consistent for every counter. Include partial/aborted-pass state.
3. Record coverage of all measured frames and, separately, eligible frames if cuts legitimately skip generation. Never remove skipped frames from the overall coverage number. Prevent a never-generating route from passing because its eligible denominator is zero. Account for the boundary frame in flight explicitly; do not hide off-by-one errors with a large tolerance.
4. For NVIDIA FG, require the effective generated/real ratio and both early/late coverage. Count every missing generated frame or batch under an explicit reason. Suggested default acceptance is at least 99% of expected generation on eligible measured frames plus an independently reported all-frame coverage value; establish any different threshold from repeated current A/A evidence and document it before acceptance. Do not confuse stored `fg_multiplier` with effective 2x for `frame_generation=dlss`.
5. Keep submitted generation separate from physical display delivery. FSR's proxy scheduler has different counters; do not certify it using NVIDIA's ratio. Missing telemetry must be `unverified`, never silently accepted as equivalent current evidence.
6. Extend Reflex validation to the measured span: applied state and timeout/failure transitions throughout the pass. Preserve existing requested Off/Boost handling and forced On for NVIDIA FG. Historical recovered failures must not poison unrelated later passes; a failure during the measured pass invalidates that pass even if it recovers by the end. Keep logs and counters as corroboration.
7. Store the exact instrumentation/driver jar hashes and validation schema in the protocol and exported provenance. Change the protocol version when semantics change. Refuse incompatible resumes before using cached summaries. Retain old evidence unchanged.
8. Update every consumer of validity, including FPS Lab's `tools/mctest/build_upscaling.py:25` importer and `test_upscaling.py`; unknown or incomplete coverage must not become a verified FG speedup. Preserve the importer prohibition on Quick/instrumented headline campaigns.

### Acceptance

Extend existing `-SelfCheck`/small standalone checks with zero generation, one startup generated frame, generation stopping mid-pass, wrong multiplier, counter reset, missing telemetry, rejected batch, recovered timeout, old timeout before pass, loading outside pass, multiple passes, aborted pass, and boundary-frame cases. Prove a real normal 2x and 6x route passes, and deliberate bounded diagnostic failure is rejected without weakening production behavior. Keep forced-failure hooks dev-only. Run the exporter checks. Report measured-pass coverage separately from historical coarse ratios.

## Stage 2: explain FG2 pacing before changing it [high priority]

### Problem and source locations

`reflex/ReflexController.java:399-456` calls `vkLatencySleepNV`, waits on its timeline signal and handles suspension/recovery. `vulkan/FrameTrace.java:6-12` only logs bounded events. `mixin/VulkanQueueSubmissionMixin.java:18-40` logs each VkSubmitInfo2 batch. `mixin/VulkanGpuSurfaceMixin.java:130-156` wraps physical present and metering. `debug/FrameProfiler.java`, `dlss/FrameGenerationRenderer.java`, and `vulkan/VulkanLifecycle.java` own the related spans, batches and attribution.

The same jar exhibits different FG2 semaphore-wait means, so treating 0.65 ms as an invariant regression is unjustified. The existing trace lacks sleep timestamps, completion IDs and correlated GPU work. Averages alone cannot identify the scheduling cause.

### Implementation and investigation

1. Extend the existing bounded, default-off trace rather than building another profiler. Record monotonic timestamps, application frame ID, sleep signal value, sleep call entry/exit, semaphore wait entry/exit/result, requested/applied mode and interval, queue-call identity plus batch index, submit completion identity, generated/real present role, and metering-batch boundaries. Keep CPU clock and GPU clock domains explicit; use supported calibration or a GPU trace tool for cross-domain correlation.
2. Capture current native, Transformer SR-only and FG2 on the same route, at least three fresh sessions or matched windows spanning the observed wait variation. Retain settings, jar/driver hashes, CPU/GPU clocks/load and capture ordering. Pair driver latency reports by matching frame ID with the trace, not the next HTTP read. Reject internally inconsistent timestamps. Instrumented FPS is diagnostic only.
3. Establish one sleep per real frame, correct marker ordering, number of actual submit calls versus batches, completion waits, and metered generated/real presentation ordering. Locate whether the variable wait shifts time from a later completion wait, changes total frame time, or accompanies a different workload. Do not add parent spans to child spans.
4. Inspect renderer resource reuse and FG completion dependencies end to end before proposing a change. Keep acquire semaphore completion protection, transient buffer/command-pool lifetime and pending Reflex signals intact.
5. Implement only a correction supported by this trace and the current raw Vulkan/NGX contract. Keep one-variable candidates pinned and test against the retained jar. Do not disable Reflex, move markers to hide work, shorten waits, change quality, add an arbitrary minimum interval or remove synchronization to manufacture FPS.
6. Marker layout, acquire reuse and zero-ID attribution already failed focused variants. Do not repeat them without a new falsifiable explanation. The previous combined-present-submit experiment was inconclusive and removed; it is not an accepted optimization.

### Acceptance

Trace checks must reject duplicate/missing marker pairs, unexpected sleep counts, ambiguous batch/call counts and missing frame attribution. Run existing `ReflexTimingCheck` and `FrameDeliveryCheck`; enable Vulkan core and synchronization validation for any synchronization change. Require repeatable uninstrumented improvement beyond established noise with native/SR/shader controls stable. If evidence points into opaque driver scheduling, retain the smallest reproducible case and exact unresolved question; do not claim a root cause or code fix without proof. An honest unresolved finding does not block independent motion-quality work.

## Stage 3: accept the retained fix on performance, interaction and latency [high priority]

### Implementation and procedure

1. Build and verify the final retained runtime, pin old/new hashes, and confirm the installed jar matches. Add actual created swapchain image count to existing debug status if still absent; requested count alone is not proof. Record present mode and surface bounds.
2. Run interaction smoke on the retained image-count fix and again after any runtime changes: resize, windowed/fullscreen transitions, VSync off/on, pause/menu blur with FG, world leave/rejoin, FG off/on and 2x/6x, moving hand and entities. Exercise swapchain recreation and verify no stale images, hangs, unmatched semaphores or pending-signal destruction. Preserve the GUI-blur guard. Capture logs and visual evidence.
3. Run `tools/bm-quick-bench.ps1 -SelfCheck`, then a narrow Quick pair to detect regressions. Final acceptance uses the existing full fresh-JVM ABBA method, without `-Quick`, `-Diagnostics` or `-Trace`, on both Set A (shaderless) and Set B (shader control). Include native, CNN Performance, Transformer Performance, Transformer DLAA, FSR Performance, FG2 and FG6. Supply explicit `-Modes` because default IncludeFG is not the entire requested set.
4. Example, once jars and stage-1 telemetry are ready:

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File tools/bm-quick-bench.ps1 -OldJar build/perf-jars/shaderless-c0.jar -NewJar build/perf-jars/shaderless-final.jar -Modes native,cnn-performance,tf-performance,tf-dlaa,fsr-performance,fg2,fg6 -Set A -Label pipeline-acceptance-20261015
   ```

   Run Set B sequentially with the same pinned artifacts. For later candidates compare against `shaderless-final.jar`, not c0; keep image-count acceptance and later improvements as separate comparisons. The runner appends the set suffix itself. Schedule sufficient time and retain durable state instead of terminating an active campaign at a conversational boundary.

5. Keep scene, route, distances, heap, resolution, presets, shader settings and Reflex state matched. Verify actual rendering coverage through the active renderer; empty vanilla visible-section telemetry under Sodium is not equal-scene evidence. Account for conditioning and pass-start transients. Aggregate paired real-frame results by jar SHA-256; reversed arm names invert the apparent change direction.
6. Report FPS, 1% lows, variation, pass failures and coverage. Quick average-FPS noise was 2.4%, 1%-low noise 16.8%; these are measured warnings, not blanket acceptance tolerances or substitutes for full-run uncertainty. Re-establish noise if instrumentation/protocol changes. Never compare a new instrumented arm to an old uninstrumented one.
7. Measure presentation cadence/latency separately with FrameView or another available supported tool. Verify the tool captures the actual Vulkan/FG presentation route and define each metric. Compare native/FG, Immediate/FIFO and Reflex cap state where relevant to the eight-image queue. Software driver render latency is not physical input-to-photon latency. If physical hardware is unavailable, state physical latency unmeasured and retain the software scope; do not fabricate a pass.

### Acceptance

Retained native recovery must repeat beyond noise without unexplained SR/FG/shader regressions. All interaction transitions pass with no new validation errors. Stage-1 per-pass Reflex/FG validity accompanies headline results. Latency evidence must establish whether any supported configuration regresses, or explicitly remain incomplete. Do not call the whole feature fully accepted while a required gate is unmeasured.

## Stage 4: improve motion quality [high priority, original user problem]

### A. Earlier HDR reconstruction

Verified gap: the exact Complementary adapter removes its final full-scene TAA resolve, but `UpscalingRenderer.reconstructScene` at line 616 and private `evaluate` at line 1100 still receive the finished tone-mapped pack image. Moving this call alone would leave target sizes, depth, uniforms and histories inconsistent.

Inspect `compat/ComplementaryTemporalSource.java`, `compat/VitrailCompat.java:62-120`, `mixin/compat/vitrail/ShaderPackSourceMixin.java:21-25`, `RenderScaleMixin.java:15-18`, and the installed Vitrail post-processing graph. Inspect the exact `run/shaderpacks/ComplementaryReimagined_r5.9.3.zip` and `native/deps/DLSS` guide/headers plus both native bridge create/evaluate paths. Vitrail internals are not a stable public API; verify installed signatures with javap.

1. Produce a short actual stage/attachment table: scene-linear color format and exposure, depth ownership, motion resolution/units, each temporal effect, tone mapping, bloom and other downstream passes, hand composition and HUD. Identify the supported pre-tone-map insertion point from actual code. Do not guess a composite pass number from the old TAA adapter.
2. Implement the smallest supported early-HDR route for the pinned pack/version/source hashes. Split world/input-resolution resources from display-resolution post resources. Supply correct dimensions, viewport, texel-size uniforms and histories at every affected pass. Set the vendor HDR/exposure/create flags and resource formats coherently with actual input, including feature recreation on contract changes. Audit Java/native ABI together.
3. Keep one jitter owner. Remove only the redundant full-scene temporal resolve on this supported route while preserving or providing the effect-specific accumulation needed by dithered lighting. Simply disabling all pack TAA settings and accepting grain is not completion. Prevent sharpening or tone mapping from being applied twice.
4. Preserve depth/motion alignment, dynamic resolution and display output, and pass completed post-effect color to FG. Use proper queue/resource lifetime and reset histories on format/size/pack changes. Verify the hand/HUD is composed at the intended stage.
5. Keep exact version/path/source guards and flattened-source cache invalidation. Unknown or changed packs must retain a documented safe fallback, not silently receive an unverified transform. Never rewrite the user's shader-pack archive or saved settings.

### B. Transparent and deformed geometry

Verified gap: `dlss/ObjectMotion.java:269-275` explicitly rejects blending and non-depth-writing render types. Its visible-surface replay uses opaque depth equality. Pack-only waving foliage and water deformation occur after the CPU geometry used by existing replay. Removing these guards blindly writes incorrect background velocities.

1. Map coverage separately for opaque entities, hand, cutouts, particles, transparent blocks/water, translucent entities and pack-deformed foliage/water. For each, record current depth, motion and vendor mask behavior; do not report camera-only vectors as object motion.
2. For shader deformation, compute previous/current positions using the same actual deformation, time and material parameters as the color pass, with stable previous state and the actual projections. Extend only reviewed supported shader paths. A rigid CPU transform cannot represent shader-only vertex movement.
3. For transparency, select an explicit supported composition strategy based on the vendor contract and actual renderer: e.g. separate contribution/depth/motion where available, or correct compositing/mask treatment. Do not overwrite opaque background vectors indiscriminately, pretend one velocity describes every transparent layer, or invent transparent depth from opaque depth. Document unavoidable limits for multi-layer/OIT paths.
4. FSR reactive/composition masks need matched color spaces and stages. The old pack opaque/final pair was invalid and deliberately disabled. Modern DLSS Transformer K/L/M do not accept the legacy current-color-bias mask as an equivalent reactive API. Implement each vendor's actual input contract rather than copying masks between them.
5. Preserve the existing opaque/hand vector behavior, sampler bias, jitter phases, preset policy and camera resets. Keep history matching safe under spawn/despawn, topology changes, view changes, cuts and pack reloads.

### Motion-quality acceptance

Use the existing same-frame `UpscaleCapture`, `tools/CaptureAnalysis.java`, capture scripts and authenticated harness. Extend their metadata for HDR/exposure and covered surface types. Current Screenshot readback is four-byte color only: do not silently interpret FP16 HDR or motion/depth as RGBA8. Preserve raw linear evidence or define a correctly decoded diagnostic encoding, and apply identical presentation transforms when comparing images.

Capture native, old retained integration and candidate at identical resolution, preset, sharpening, route/time/lighting and scene state, FG off. Cover stationary camera with animated subjects, camera strafe/turn, swinging hand, disocclusion, particles over detailed backgrounds, transparent water/glass, waving foliage, thin cutouts, bright/dark transitions, and pack on/off. Use persistent leaves and frozen scene content; earlier decaying-leaf captures were invalid.

Require independent numerical motion correspondences for representative surfaces plus visual temporal sequences. Measure detail retention, trailing/ghosting, shimmer and lighting noise together; Laplacian sharpness alone rewards noise. Compare matched motion, not an arbitrary still against a moving frame. Retain before/after clips or frame strips and raw metadata. No new clipping, double tone mapping, exposure pumping, HUD blur, temporal noise or material regression is acceptable. Run resize/rejoin/reload and optional-dependency fallback checks. Shaderless rendering must remain correct. Do not promise native equivalence at all input resolutions.

Build/check with `.\gradlew.bat build --console=plain`, the existing `-I tools/vitrail-check.gradle vitrailCompatCheck` integration check and `java tools/CaptureAnalysis.java self-check`. Extend existing standalone checks only where new nontrivial behavior needs coverage. Repeat uninstrumented performance acceptance for final rendering changes after quality gates pass.

## Publication, provenance and completion

FPS Lab is live at `https://mctest.bambooz.dev/#upscaling`. Existing provisional archive: `../Benchmark Mod/tools/mctest/upscaling-history/shaderless-f480b8f-quick-20261015.json`; it already includes the validation follow-up. Do not duplicate it as a new gain. Website commit at preparation: `82b7607`.

1. Write `docs/validation/pipeline-improvements-<date>.md` with each stage's implemented/verified/unresolved status, source and jar hashes, commands, protocol, raw paths, controls, numerical results, visual evidence and limitations. Keep one row per acceptance item so partial work remains visible.
2. For full paired campaigns, use existing `build_upscaling.py --import-bm <bm-bench-directory> --name <campaign-prefix>`. Verify the new telemetry is retained and invalid rows cannot chart as wins. Keep Quick, instrumented diagnostics, quality comparisons and full performance acceptance distinguishable. Never weaken full-campaign import guards to publish diagnostics.
3. Add public-safe archives to `tools/mctest/upscaling-history`, regenerate `site/upscaling.json`, run `tools/mctest/test_upscaling.py`, and preserve every prior run. Never publish harness tokens, sessions or unrelated logs.
4. Publication is authorized for these results. Use Git's scp/ssh with the `minecraft-server` alias, stage the JSON under `/srv/mctest/incoming/`, then atomically rename into `/srv/mctest/public/upscaling.json`. Do not run a broad site sync that overwrites unrelated ledger data. Fetch the public HTTPS JSON with cache busting and compare its SHA-256 to the local export. Inspect the actual displayed run, validity labels and history in the shared browser.
5. Commit finished work in each affected repo. Push project repositories only if separately requested; publishing the site is not permission to push all repositories. Update reusable ai-brain facts, commit and push that shared brain as required by global instructions.
6. Final response links the validation report and live results, states measured improvements, and lists remaining unverified gates. A spec, build, screenshot, positive FG counter or single good diagnostic window alone does not complete this pipeline.

## Reference contracts to verify during implementation

Use the pinned local SDK documentation and current official primary sources when resolving API questions: NVIDIA DLSS Programming Guide (`native/deps/DLSS/doc/` or NVIDIA/DLSS), NVIDIA Streamline Reflex guide, Khronos `VK_NV_low_latency2`/swapchain synchronization and intermediate-layer marker rules, and AMD FSR integration documentation. Prior source research and vendor links are retained in `UPSCALING_QUALITY_SPEC.md` and `SHADERLESS_PERFORMANCE_SPEC.md`. Radiance/MCVR is an architectural reference for previous geometry/deformation, not permission to copy incompatible licensed code. Validate new integration assumptions against installed source and live captures.
