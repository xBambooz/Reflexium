# All-off DLSS-G lifecycle correction

Status: source correction only, runtime and performance unverified. Baseline: d8cdf7b,
Minecraft 26.3, Streamline 2.14.16. No builds, tests, game launches, installs or live
queries were performed because another agent owns the running FPS benchmarks.

## Finding

The disabled path retained DLSS-G because an earlier unload/recreation crashed.
The actual unload callback was injected at `destroySwapchain` HEAD. Minecraft's
26.3 `VulkanGpuSurface.destroySwapchain` subsequently waits the graphics queue,
calls `vkDestroySwapchainKHR`, destroys present semaphores and clears the handle.
Thus the plugin was unloaded before the engine destroyed its proxy, contrary to
the callback's documented contract. This is a verified source ordering defect,
not proof that it accounts for every previous crash or the reported FPS loss.

NVIDIA's bundled `native/streamline/ProgrammingGuideDLSS_G.md`, section 18,
requires unloading and swapchain recreation to avoid the loaded-but-off copy
and synchronization overhead. `sl_core_api.h` also requires a flushed pipeline
before `slSetFeatureLoaded`.

## Change

- Apply queued feature transitions at destruction TAIL, after the old proxy has
  been destroyed using its existing hooks. Drain the device before changing hooks.
- Queue the saved feature state during device setup, so an Off launch unloads
  DLSS-G before the first swapchain. Keep adapter capability data for the UI.
- Recreate the swapchain for both On and Off transitions. Replace or cancel
  pending requests when settings change; failed transitions do not retry each frame.
- Hardware support no longer depends on whether the plugin is currently loaded.
  Load failures still prevent activation and report a failure.
- Clear old swapchain status/counts and keep the simulated load-failure path's
  reported state consistent with the actual plugin state.
- Preserve Reflex sleep/PCL markers and semaphore reuse protection.

No native ABI or DLL changes are needed. The existing native bridge already
exposes feature load/unload and device-idle calls.

## Deferred verification

`StreamlineBridgeCheck.swapchainUnloadOrder` inspects the compiled mixin and fails
if the SDK unload callback is attached before swapchain destruction. It is part
of the existing `./gradlew streamlineBridgeCheck` task. Added but NOT RUN.

After GPU ownership is released: build, run the existing checks, then verify a
cold all-off launch, Off -> On -> Off, repeated toggles, resize, minimize, and
the forced load-failure/retry path. Confirm FG stays selectable while unloaded,
generation resumes when enabled, and Off uses an unloaded plugin. Compare FPS
and lows against the same no-mod scene. No performance recovery is claimed yet.

The concurrent `docs/validation/dlss-cost-2026-10-26/` artifacts belong to the
benchmark agent and were not modified or included in this change.

## Runtime result (2026-10-26, superseded)

A cold launch with FG Off using this change crashed (mclo.gs 131Nh78, WER APPCRASH
javaw.exe, jvm.dll c0000005, no hs_err). The log reaches "DLSS-G feature loaded=false
requested; recreating the swapchain" but never the "between swapchains" line, so the
process died inside the post-destruction device drain or `slSetFeatureLoaded(DLSS-G, false)`.
Moving the unload to TAIL did not fix the earlier unload crash. The Off path again keeps
the plugin loaded with options off; `requestFrameGenerationFeature(false)` only cancels a
pending transition. Loading at TAIL is unchanged. The FG proxy overhead while Off remains.

## Correction (2026-10-26, 20:43)

The crash above was misattributed. The same `jvm.dll+0xa88377` fault (no hs_err) also hit the
fixed jar (20:37) and a 20:24 launch, and matches the known stack-probe failure from the missing
`-XX:StackShadowPages=32` (see the shared brain, agent-harness round 17). Adding that flag to the
Fabric 26.3 Modrinth instance args fixed the launch with this jar (FG Off, DLSS-G loaded).
Whether unloading DLSS-G is actually safe remains untested; 1fbfeec's keep-loaded Off path stays
until an unload test runs with the flag present.
