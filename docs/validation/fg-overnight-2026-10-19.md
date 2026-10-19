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
