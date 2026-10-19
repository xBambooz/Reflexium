# Frame generation investigation, 2026-10-19

Status: PARTIAL. Multiplier configuration, rendered-only refresh fallback, and avoidable presentation
serialization are corrected. Sustained generated submissions and distinct generated images are verified.
Uniform physical scanout, first-party motion quality, and synchronization-layer acceptance remain open.
No merge to main. Base: `92c928c`; branch: `t3/fix-frame-generation-pacing`.

## Environment and ownership

Windows 11, RTX 5070, NVIDIA 617.42, GraalVM Java 25, Minecraft 26.3, experimental Iris 1.12.0,
BSL v10.1.8, Sodium 0.9.2. This is raw NGX 310.9.1 Vulkan, not Streamline proxy integration.
NGX reports five generated frames maximum, six total. Production runtime, existing project ID and
native ABI unchanged. Streamline development overlay/state APIs do not apply to this raw NGX route.

Only harness port **25621** was used. Reflexium debug port: 25620. The run directory was copied from
the main checkout into this worktree's `run`. Ports 25601 and 25611 were not contacted. Every shutdown
checked authenticated PID/game directory and the exact Windows command line for harness jar and run
directory, then requested `quit` with `exit=true`. No process-name termination was used.

Initial tests: secondary XF250Q, 1920x1080, 239.76 Hz. Following the owner's update, tests moved to the
primary PG27AQDP, 2560x1440, 479.99 Hz, through the harness window endpoint with `behind=true`.
OS input-focus and foreground flags remained false. The owner's black overlay was left alone.
The harness `placement` string retained the original launch description after moving; actual window
coordinates, dimensions and the mod's new refresh target confirmed the move.

GPU contention was explicitly allowed by the owner. All FPS and timing numbers below are **indicative**,
not controlled benchmark results. Load varied substantially during this session. No publication claim.

## Root causes and implementation

1. Saved `frame_generation=dlss`, `frame_generation_multiplier=3` requests standard FG in the old
   renderer. That branch always evaluates one generated frame. The multiplier only affected
   `dlss_multi`. The copied run reproduced exactly this mismatch. NVIDIA-mode multiplier changes
   now choose standard FG at 2x and MFG above 2x. Selecting standard FG resets the multiplier to 2x.
   Load normalizes old inconsistent NVIDIA settings according to the stored multiplier. Off/FSR
   stay unchanged. A fresh JVM with deliberately restored `dlss` plus `3` reported `dlss_multi`,
   requested generated count 2. No NGX multiplier capability bug was found.

2. `VK_NV_low_latency2.minimumIntervalUs` caps real plus generated presents on this stack. Applying
   the automatic refresh cap while generating consumes the real-frame budget. The baseline light
   scene reported roughly 104 real / 208 submitted FPS; FG off reached 228 real FPS. The application
   now starts NVIDIA FG with rendered-only measurement. Above 95% of the refresh-headroom target
   it remains rendered-only, below 80% it enables the chosen static multiplier. One-second windows
   and hysteresis avoid ordinary cap jitter. No synthetic base-FPS multiplication is used.
   During active generation the automatic total-present cap is removed, so a lighter scene can
   reach the bypass threshold without the cap itself preventing detection. Explicit user caps remain
   total-present caps. Active output can exceed monitor refresh. This intentionally prioritizes real
   throughput; it is not an output-budget optimizer or NVIDIA Dynamic MFG. Bypass, target and observed
   real throughput are exposed in status; the tooltip explains the policy.
   A lower explicit user cap also lowers the bypass target, preventing the same loss at a user limit.

3. Each replacement blit rotated Minecraft's main two-submit command pool. A 3x diagnostic recorded
   3 submits per real frame, averaging 3474.7 us per submit scope, about 10.4 ms per real frame.
   Reflex sleep averaged only 16.7 us. Dedicated persistent encoders per replacement slot keep
   RenderPearl's existing pool-reset/completion rules per real frame. They use the same graphics
   queue and survive mode changes because acquire fences retain their timeline references.

4. The two binary acquire semaphores then forced mid-batch completion of the world's submission.
   Encoder-only testing moved roughly 5.7 ms per real frame into acquire-reuse waits. Metered surfaces
   now reserve 16 acquire semaphores for two real frames of up to eight physical presents. Every reuse
   still waits on the actual consuming submission's fence. Partial allocation failure destroys newly
   allocated handles and preserves the native ring. Native destruction iterates the expanded array.
   This is not a ring-size-only safety argument. Combined diagnostics reduced acquire-reuse mean to
   0.11 us and submit scopes to about 4.6 ms per real frame, although GPU contention varied.

No resource-safety wait was deleted. No per-frame device-idle call was added. Device idle remains a
feature/resource lifecycle operation. The first generated image stays on the main encoder; replacement
slots use their own encoders. Queue order and the existing image barriers order reads before later writes.

## Engine profile and NVIDIA checklist

Backend: RenderPearl Vulkan. App owns swapchain, acquire and presentation. Static NGX outputs are
evaluated on the graphics queue before GUI replay. First present starts one `VkSetPresentConfigNV`
batch, then generated outputs precede the real frame. Batch limit remains eight. Real simulation IDs
and generated out-of-band markers are unchanged. FSR's proxy route bypasses this replacement loop.

Required depth and motion are produced by the existing world/upscaling path, with current camera
constants and frame ID. This patch does not expand motion coverage. HUDless scene interpolation plus
prepared GUI replay preserves the hand, hotbar and destination-dependent crosshair. Existing gaps in
animated/translucent content remain outside this investigation. Menus, history cuts, resize and missing
Reflex pacing retain the existing fallback logic. Missing-runtime/unsupported-GPU fallback was reviewed
but not retested by deleting installed production DLLs.

References read: shared brain FG/harness/preferences, `docs/FRAME_GENERATION.md`,
`docs/FRAME_GENERATION_REFERENCE.md`, `FG_SUSTAINED_RECOVERY_SPEC.md`,
`FRAME_GENERATION_REFLEX_SPEC.md`, NVIDIA FG skill integration/validation checklists, and local
`gpu-sdk-reference/streamline/docs/ProgrammingGuideDLSS_G.md` including its execution-cost and queue
parallelism guidance. The vendor table has no RTX 5070 FG row; do not treat another GPU's cost as a
measured acceptance threshold for this machine. No Streamline-only queue option was invented for NGX.

## Functional and performance evidence

Ignored raw artifacts: `build/fg-overnight/`. Status files include full requested/effective settings,
real/generated counters, metering feedback, failure and sleep health.

- Secondary light scene after the policy change: about 220 real FPS with rendered-only bypass,
  target 223, rather than allocating half that output budget to generated frames.
- BSL Quality, render distance 24, forest camera `(-24.5, 65, 11.5), yaw=0, pitch=0`: FG off initially
  about 157 real FPS. Policy-only 3x about 76 real / 229 submitted; combined encoders/ring sample
  about 95 real / 285 submitted. Later FG-off samples rose to 269, illustrating changing GPU load.
- Primary BSL Quality, render 1707x960 to 2560x1440, distance 24: FG off sample about 203 real FPS;
  3x samples ranged about 96 to 118 real and 289 to 355 submitted. The refresh target changed to 415.
- Primary steady 3x counter window: 2436 real, 4872 generated, exactly 2 generated per real.
  Primary 6x window: 467 real, 2335 generated, exactly 5 per real. No metering rejections or FG latch.
- A final same-view JVM sample retained 1315 FG3 frame intervals: 64.05 real FPS, mean 15.613 ms,
  p99 26.352 ms. FG off retained 1613 intervals: 100.211 real FPS, mean 9.979 ms, p99 14.475 ms.
  Steady 3x submission therefore increases output operations while reducing real throughput by
  roughly 36% in this sample. These samples are indicative, not a controlled timing/latency result.
- One Reflex sleep timeout during the longer run recovered. It remains in status rather than being
  hidden. No persistent pacing failure or NGX evaluation failure occurred.

Runtime exercised standard 2x, 3x, 6x, Off/On toggles, switching multiplier, primary-monitor move,
1080p/1440p swapchain recreation and a fresh-JVM world join. Resize produced a handled out-of-date
surface warning and recovered. Final migration load and joined-world smoke passed. FSR/AMD hardware,
device loss, core/synchronization validation and a broad game-content quality matrix were not run.
The `VULKAN_SDK` environment variable points to a deleted historical worktree; no usable validation
layer was installed in this session. The encoder/ring change still requires synchronization validation.

## Image and cadence evidence

The new opt-in harness plugin request `reflexium?fg_capture=1&dir=<absolute directory>` reads the real
target and every requested generated target at one render-thread boundary, with metadata. It does not
compare unrelated screenshot requests. `tools/CompareFgCapture.java` checks scene pixels above the HUD.

Inspected `panbatch`, `primary-panbatch` and `primary-motionbatch` images. In the primary moving-camera
batch, generated0 differed from real in 26.7623% of scene pixels (mean RGB error 0.393764/255),
generated1 in 12.2581% (0.175089/255), and the two generated images differed in 21.6343% (0.227380/255).
The generated images contain terrain, hand and legible HUD. This rejects exact scene duplication.
The later generated output is closer to the current real image, consistent with interpolation order.
It does not independently certify optical-flow correctness, exact interpolation fractions, all moving
objects or disocclusion quality. A previous-real/current-real/generated capture with motion analysis
would strengthen that acceptance.

Bundled NVIDIA FrameViewSDK PresentMon exited without output. Standalone official Intel PresentMon
2.6.0 produced CSVs. SHA-256: `b2a706bc6ad475749e3b7e3409263aa1e6906d45bdcf993f6dbc0f660188f1af`.
Downloaded from the GameTechDev/PresentMon v2.6.0 release into this worktree's ignored artifact folder.
The capture filter was the exact owned game PID and a unique ETW session name, never a process name.

Secondary `fg3-display.csv`: 2240 records, 2060 display changes, mean 4.8485 ms, median 4.1358 ms,
p95 11.8468 ms, p99 14.6069 ms. Combined `ring-fg3-display.csv`: 2828 records, 2707 display changes,
mean 3.6852 ms, median 3.5149 ms, p95 7.8218 ms, p99 9.6984 ms. This is uneven cadence, not a pass.
IMMEDIATE tearing-mode flip changes can exceed panel refresh; they are not full-panel unique frames.
GPU contention and different workloads prevent attributing the difference solely to this patch.

Primary captures behind the owner's overlay contained very few or no display records, so they cannot
validate panel cadence. The overlay was not closed. Uniform pacing, dropped/repeated displayed content,
physical scanout and input-to-photon latency remain unresolved. Do not describe this as official-title
smoothness or reference-quality integration yet. Integration quality remains an experimental candidate.

## Repeat commands

All project paths below resolve inside this worktree. Launch uses the authorized external harness.

```powershell
$root = 'C:\Users\Ryan\.t3\worktrees\Reflexium\t3-766c9320'
& "$root\gradlew.bat" -p $root build --console=plain
powershell.exe -NoProfile -ExecutionPolicy Bypass -File C:\Users\Ryan\source\repos\mc-agent-harness\tools\launch.ps1 -Project $root -Port 25621 -Monitor primary -Width 2560 -Height 1440 -RunDirectory "$root\run" -GradlePropertiesJson '{"rxDebug":"25620"}'
# Use authenticated harness requests, with the token read privately from instances/25621/session.json.
# POST join?world=Reflexium%20Vitrail%20Smoke
# POST reflexium?key=frame_generation&value=off
# POST reflexium?key=frame_generation&value=dlss_multi
# POST reflexium?key=fg_multiplier&value=3
# POST turn?dyaw=120&ticks=600&async=1
# POST reflexium?fg_capture=1&dir=<absolute worktree artifact path>
& 'C:\Program Files\GraalVM\graalvm-jdk-25.0.4+7.1\bin\java.exe' "$root\tools\CompareFgCapture.java" "$root\build\fg-overnight\primary-motionbatch"
& "$root\build\fg-overnight\PresentMon.exe" --process_id <owned-pid> --timed 10 --terminate_after_timed --session_name ReflexiumFG25621 --output_file "$root\build\fg-overnight\capture.csv" --no_console_stats
# Verify shutdown ownership, then POST quit?exit=true and wait for that PID to exit.
```

`gradlew.bat build --console=plain` passes all existing standalone checks plus the added multiplier and
refresh-policy checks (240/480 Hz, heavy/light transitions, unknown refresh). `git diff --check` passes.
No native ABI or DLL change was needed. Real GPU synchronization validation and unobscured scanout
measurement are required follow-ups before claiming the user's full smoothness goal is achieved.

The final review additionally canonicalized a driver clamp down to 2x back to standard FG and made
selecting MFG from standard FG start at 3x. Explicit-cap bypass received a deterministic 120 FPS check.
These final settings changes passed the build/check suite; the GPU run above precedes those two
settings-edge adjustments. The ordinary 3x render/present implementation is the same tested path.
The owned client was shut down with `exit=true`, and its exact PID exited before the final build.
Final JAR SHA-256: `3d66bbcf0e25884ae322e1d138ead4a06b093a01219d09e29a91cd314f8754bc`.
Pinned copy: `build/fg-overnight/final-candidate.jar`; build log: `build/fg-overnight/final-build.log`.

## Round 2

Base: `7cb1ebc`. Worktree: `C:\Users\Ryan\.t3\worktrees\Reflexium\t3-5c4a8eee`.
Only harness port 25622 and debug port 25623 were used. Copied the main checkout's run directory.
Primary PG27AQDP, 2560x1440, 479.99 Hz, non-minimized, no input focus or foreground ownership.
The owner's overlay and port 25612 client were untouched. Each client was authenticated and its exact
PID, injected harness JAR and game-directory command line checked before `quit?exit=true`.
Ignored evidence: `build/fg-round2/`. No merge to main.

### Policy correction

Round 1's uncapped active-generation policy is superseded. `minimumIntervalUs` always retains the
lower of refresh headroom and an explicit user limit while NVIDIA FG is requested. At 479.99 Hz,
the policy target is 415 FPS and the rounded 2410 us driver interval reports approximately 414 FPS.
The interval is never divided by the multiplier. This limits total presents, not just rendered frames.

Auto is the default multiplier for newly selected NVIDIA FG and missing multiplier configuration.
Stored fixed multipliers remain fixed. Config uses 0 for Auto; the debug API accepts `fg_multiplier=auto`
or 0, and both vanilla/custom and Sodium options expose Auto alongside 2x through the driver maximum.
Sodium's integer control uses its first position for Auto and maps it to config 0.
Effective multiplier and sampling state are separately exposed as `fg_effective_multiplier` and
`fg_policy_probing`. Raw NGX still receives ordinary static counts; this is application selection,
not Streamline Dynamic MFG support.

Auto chooses the largest multiplier fitting the sampled real FPS into the target, clamped to driver
support. Downshifts happen when the budget is exceeded; upshifts require 5% spare budget. Below 2x,
it renders alone. Thus a scene at 85% of target still correctly selects rendered-only under the
requested integer-multiplier formula: even 2x would exceed the budget.

Sampling deliberately uses rendered-only windows, not capped FG FPS times the multiplier. It samples
one second on startup and after five seconds of the selected mode. The output cap remains enabled
during sampling. When rendering can fit 2x, that rendered-only sample is below the cap and measures
uncapped throughput; when it hits the cap, it already proves 2x cannot fit. Unknown refresh/target
selects rendered-only Auto. Fixed multipliers bypass sampling and stay fixed under the cap.
Limitation: these periodic probes temporarily stop generation and reset FG history. This can be
visible and is not seamless official-title Dynamic MFG. A calibrated render-cost measurement that
excludes limiter waits and FG work is needed to remove those interruptions without circular feedback.

### Cadence investigation

`VkSetPresentConfigNV` usage matches the specification: one config on the first present, complete
generated-first/real-last batch, count at most eight. No extra CPU spacing was added on top of the
driver's metering. However, RenderPearl `VulkanCommandEncoder.submit()` queues current work and then
waits for its previous submission before returning. Replacement encoders performed that wait between
metered presents. Their previous-submit fences are now waited before the first present in the batch.
Native submit wait/reset and acquire-completion tracking remain intact. Encoders/fences survive FG
toggles and close at device teardown. No per-frame device-idle call was introduced.

`normal-trace.log` preserves 32 traced frames. For example, frame 51571 completes the replacement
reuse waits before `metering batch begin presents=3`; both replacement submit-tail waits report their
target index already completed. All three presents succeed, feedback 0x1, batch completed 3/3.
This verifies CPU wait placement only. Metering controls driver presentation, so rapid CPU calls are
not evidence of uneven scanout. There is no matched, unobscured display capture in this round.

### Vulkan validation

The existing VULKAN_SDK path was stale. An Unreal-bundled 1.4.304 layer was found, but rejects newer
feature structures and prevents device creation. Its reports are preserved as `old-layer.log`.
Downloaded the official LunarG 1.4.363.0 SDK archive and extracted it privately with 7-Zip, without
running the installer. SDK SHA-256:
`94a82d378f7a5e3e54c9db7d2fb7016af136e14ac0a18dbf0f2f67a36352d141`.
The loader ignores VK_LAYER_PATH for this elevated harness. A temporary HKLM ExplicitLayers entry
pointed to the private manifest; it was removed in `finally` after startup. Loader logs confirm both
instance and device validation layers loaded. Launch environment:

```text
VK_INSTANCE_LAYERS=VK_LAYER_KHRONOS_validation
VK_LAYER_VALIDATE_SYNC=1
VK_LAYER_ENABLES=VK_VALIDATION_FEATURE_ENABLE_SYNCHRONIZATION_VALIDATION_EXT
VK_LOADER_DEBUG=error,warn,layer
```

Real application error fixed: NGX's `vkAllocateMemory` uses `VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT`
without the logical device's core bufferDeviceAddress feature. Validation emitted
`VUID-VkMemoryAllocateInfo-flags-03331` and NGX creation failed with `0xbad00002`.
Enabling the legacy EXT feature alone did not fix this core allocation flag. The final implementation
enables supported `VkPhysicalDeviceVulkan12Features.bufferDeviceAddress` and removes the conflicting
`VK_EXT_buffer_device_address` extension. DLSS SR and FG then create and evaluate under validation.
The allocation VUID disappears. See `sync363-before.log`, `sync363-ext-feature.log`, and
`sync363-final.log`. No native bridge ABI or DLL changes were needed.

Synchronization validation is **not clean**. It reports WRITE_AFTER_WRITE hazards inside the
proprietary NGX `nv.ngx.dlssg.Evaluate` command range: image layout transitions followed by clears
without transfer-write destination access, and repeated `vkCmdFillBuffer` writes without an internal
dependency. These are internal `nv.ngx.dlssg.resource` objects. The caller already issues an
ALL_COMMANDS/MEMORY_READ|MEMORY_WRITE barrier before and between NGX evaluations; that cannot insert
a dependency between commands inside one vendor call. Reports were not suppressed or classified as
harmless. Vendor investigation or a corrected NGX runtime remains necessary. No acquire/present
semaphore reuse or application image-lifetime hazard was reported in this exercised path.

### Evidence and acceptance

- Build/static: PASS, `gradlew.bat build --console=plain`, including the updated standalone policy
  checks (budget selection, up/down hysteresis, unknown refresh, probes, fixed multiplier).
- Normal Vulkan fixed 3x: about 114 real / 343 submitted FPS, target 415 retained, zero metering
  rejections. These are submitted operations, not displayed frames; contention was uncontrolled.
- Auto normal Quality: rendered-only sample 218.63 FPS, effective 1x at target 415. Native-AA sampling
  also exercised the heavy-scene path. With validation overhead, Auto sampled 31.67 then 32.14 FPS
  and selected supported maximum 6x. Sampling and active states alternate as documented.
- Validation fixed 6x steady counter window: 111 real, 555 generated, 111 completed batches, exactly
  five generated per real. Target 415 and approximately 414 FPS driver cap remain configured.
- Validation exercised joined gameplay with depth/motion, scripted camera turn, fixed 2x/3x/6x,
  Auto, FG off/on, resize to 1920x1080 and back to 2560x1440, and orderly shutdown. Final status
  retained no NGX failure and zero metering rejections. Frame delivery remains functional.
- Inspected generated_0 in `motionbatch`: visible terrain, hand and HUD, no blank/corrupt frame.
  Same-boundary comparison: real/generated0 changed 16.8311% of scene pixels; real/generated1
  3.1998%; generated0/generated1 15.2605%. The capture was nearly static, so these differences
  establish distinct images, not correct interpolation fractions or motion-quality acceptance.
- SDK/core application allocation defect: FIXED. Synchronization acceptance: FAIL due to the
  retained internal NGX hazards. Display cadence and latency: NOT PROVEN behind the owner's overlay.
  Missing-runtime/unsupported-hardware, moving-object quality matrix, AMD/FSR and device loss were
  not rerun. Existing input coverage, GUI replay, project ID and production NGX runtime are unchanged.

Overall: PARTIAL, experimental candidate. Remaining: remove Auto probe interruptions with valid
uncapped-cost telemetry, investigate vendor internal hazards, and measure unobscured displayed-frame
cadence and input latency. Do not infer official-title smoothness from these counter/CPU traces.

References: [Khronos metering contract](https://docs.vulkan.org/refpages/latest/refpages/source/VkSetPresentConfigNV.html),
[device feature constraints](https://registry.khronos.org/vulkan/specs/latest/man/html/VkDeviceCreateInfo.html),
[LunarG SDK](https://vulkan.lunarg.com/sdk/home/), and
[Super Resolution FG source](https://github.com/IReallyWantToSleep/superresolution/blob/dev/modules/common/src/main/java/io/homo/superresolution/common/framegeneration/FrameGeneration.java)
(found via Modrinth, reviewed for provider/presentation separation; no source copied).

Round 2 implementation commit: `ddf06eb`. Final build and diff checks pass.
Final candidate: `build/fg-round2/final-candidate.jar`; SHA-256
`9dbc9be4bde15eb34fef9558700355050f9787a46b3525df878a1bda1694b4dd`.

## Round 3

Base: `ac289bf`. Branch `t3/smooth-auto-fg-continuity`, worktree
`C:\Users\Ryan\.t3\worktrees\Reflexium\t3-aee2c8e4`. Harness port 25623, debug port 25624.
Copied the main checkout's run directory. All captures used the primary PG27AQDP at 2560x1440,
479.99 Hz, visible and without OS focus. The owner's overlay and port 25612 client were untouched.
Raw artifacts are ignored under `build/fg-round3/`. No merge to main.

### Auto continuity

Removed startup and periodic rendered-only throughput probes. Auto now consumes the existing sparse
`VK_NV_low_latency2` timing query, using `gpuRenderEndTimeUs - simStartTimeUs`. Simulation-start is
emitted after Reflex sleep, so the estimate excludes that limiter sleep. The existing validity,
frame-ID and one-second stale-report checks remain in force. Missing/invalid timing holds the last
selection; before the first valid report Auto remains rendered-only. Fixed multipliers do not request
extra timing queries. No GPU query wait or new profiling dependency was added.

The reciprocal render-cost estimate is smoothed with a 0.1 exponential weight. Selection retains
the 5% upshift headroom and driver maximum, and is reconsidered at most once every five seconds.
Non-game frames preserve the selection while the renderer's existing gameplay/history gates still
apply. Settings/cap changes reset the policy. A sustained light scene may legitimately select 1x;
sampling itself never forces a transition to 1x. The refresh/explicit-user total-present cap remains
unchanged, 415 target / approximately 414 FPS rounded driver interval on this display.

This estimate includes FG GPU work, queue delay and waits inside submission. It is conservative,
not independently measured FG-off FPS. Implicit driver attribution also limits its precision.
`fg_policy_real_fps` is now an estimate; `fg_policy_estimate_source` identifies its provenance and
`fg_policy_probing` remains false for compatibility. This is application selection of static NGX
counts, not vendor Dynamic MFG. No claim of optimal multiplier or exact uncapped throughput is made.

`auto-60s.json`: 61 authenticated snapshots spanning 60.72894 seconds, DLSS Native AA with shaders,
Auto requested (config 0), effective 6x throughout. Real presents rose 10993 to 14032 (3039), generated
presents 6230 to 21425 (15195), exactly five per real. Expected-generated delta was also 15195.
Every one of the 60 adjacent snapshot intervals had exactly five generated presents per real,
no probe, no multiplier transition and no metering rejection. Thus no generated-present shortfall
or periodic probe interruption occurred in this window. This proves submitted-generation continuity,
not uniform physical display cadence. The capture was a fixed camera with animated scene content;
the validation run separately exercised a 30-degree camera turn and fixed 3x.

Inspected `motionbatch/generated_0.png`: nonblank terrain, foliage, sky, hand and HUD. All five
generated targets and the real target were captured at one boundary. This is a visual smoke check,
not an interpolation-quality matrix. Physical scanout and input latency remain unmeasurable behind
the retained overlay. GPU contention was uncontrolled; do not treat this as a throughput benchmark.

### Synchronization hazard attribution

Fresh-JVM synchronization validation used the same private LunarG 1.4.363 layer as Round 2, copied
into this worktree. `sync-loader.log` records the loaded DLL path. The temporary ExplicitLayers
registry entry was removed after startup. `sync-final.log` preserves the unsuppressed reports.

The reports reproduce inside `nv.ngx.dlssg.Evaluate`, not the caller's before/after dependencies:

- Image `0x4f980000004f98[nv.ngx.dlssg.resource]`: NGX's image-layout transition precedes its
  `vkCmdClearColorImage`. Its dependency permits shader read/storage write but omits transfer write
  at CLEAR. The validator explicitly names the earlier transition's NGX evaluation label.
- Buffer `0x4f810000004f81[nv.ngx.dlssg.resource]`: `vkCmdFillBuffer` writes the same 7,372,800-byte
  region after another fill inside the NGX evaluation label, without the required write dependency.
  The bridge supplies only image-view resources to `NGX_VK_EVALUATE_DLSSG`; no application buffer
  is passed to this FG call. The buffer and its fill sequence are vendor-owned.

The report contains six image-clear and four buffer-fill message occurrences (including repeated
validation logging), all WRITE_AFTER_WRITE. No other VUID or synchronization hazard category was
reported in this exercised run. The Java caller issues the existing RenderPearl all-command memory
barrier before, between and after NGX evaluations. The native bridge records the vendor call directly;
it records neither the reported clears nor buffer fills. A caller barrier cannot order two commands
inside a single opaque vendor evaluation. Changing caller input/output layouts cannot repair the
vendor buffer-fill dependency. No barrier workaround or suppression was added.

Conclusion: these specific reports are internal NGX synchronization defects according to validation,
not evidence of a missing caller boundary barrier. This does not establish that the defects are
harmless or that every application synchronization path is correct. Vendor remediation remains open.
Auto 6x and fixed 3x continued generating under validation, with no FG failure or metering rejection.
Synchronization acceptance remains FAIL; functional generation and Auto continuity pass this scope.

### Checks and remaining work

`gradlew.bat build --console=plain` and `git diff --check` pass. The standalone policy checks cover
60-second continuity, missing/NaN timing, menus, five-second dwell, sustained-light bypass, cap
selection, hysteresis and fixed multipliers. The initial build lacked the untracked Iris test JAR;
copying the required run assets resolved it. No application code workaround was needed for that.

Candidate: `build/fg-round3/final-candidate.jar`, SHA-256
`0ff189f7b719c3e3c04bc93979ade569f287140d6b64d3fdf0c9a856ff04da89`.
Build log: `build/fg-round3/final-build.log`. Each owned JVM was shut down with authenticated
`quit?exit=true` after checking its exact PID, harness JAR and game-directory command line.

Remaining: vendor-internal synchronization remediation, calibration of the conservative cost against
independent real-render timestamps, unobscured display cadence/latency, broad moving-object quality,
AMD hardware and device-loss coverage. Overall remains an experimental candidate.

Timing-field semantics: [Khronos latency report](https://docs.vulkan.org/refpages/latest/refpages/source/VkLatencyTimingsFrameReportNV.html).
The [Super Resolution provider source](https://github.com/IReallyWantToSleep/superresolution/blob/dev/modules/common/src/main/java/io/homo/superresolution/common/framegeneration/FrameGeneration.java)
was revisited for provider/presentation separation; no source or new library was added.

Round 3 implementation: `51ddbff`. A final fresh normal-Vulkan JVM loaded the built source,
rejoined gameplay and selected Auto 4x by the end of its smoke run, with no FG failure.
`final-smoke.json` confirms the estimate-source field and 9973 accumulated generated presents.
This separate variable-workload smoke is not the stable 6x continuity capture above.
