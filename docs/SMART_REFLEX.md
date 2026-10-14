# Smart Reflex

Interim workaround for the high-FPS `vkLatencySleepNV` throughput cost described in
[PERFORMANCE.md](PERFORMANCE.md). This does not fix the driver scheduling issue.

`smart_reflex=true` is the default, including when an older config omits the property.
The toggle appears next to NVIDIA Reflex in vanilla Video Settings, Mod Menu's config
screen, Sodium and Reese's Sodium Options.

While Reflex is On or On + Boost, the controller averages real frame intervals over
half-second windows. Pacing pauses above 320 FPS and resumes below 280 FPS. Between
those thresholds it retains its previous state. A paused frame skips both
`vkLatencySleepNV` and its semaphore wait. Sleep mode, Boost, markers and present IDs
remain unchanged. The shared status line used by Status, F3 and BetterF3 appends
`(paused, high FPS)`.

Any selected frame generation mode prevents pausing, including during FG startup.
Explicit FPS caps and the automatic VSync/VRR cap also keep Reflex pacing active.
Disabling Smart Reflex, turning Reflex off, or destroying the swapchain resets the
sampling window and paused state.

The debug harness accepts `smart_reflex=0|1` and exposes `smart_reflex`,
`reflex_pacing_paused`, `reflex_sleeping` and `reflex_latency` in status.

## Verification, 14 October 2026

`./gradlew build` passes, including `reflexTimingCheck` assertions for the initial
half-second observation window, both hysteresis transitions, both retained states
inside the deadband, and immediate reset when pausing is disallowed.

Authenticated harness on port 25613, secondary monitor, 1920x1080, foreground HWND
unchanged on launch. In the existing smoke world with Transformer Performance:

- On at approximately 425 FPS: paused, sleeping false.
- Transformer Native AA at 3840x2160, approximately 99 FPS: resumed, sleeping true.
- Back at 1920x1080 with On + Boost, approximately 425 FPS: paused again.
- DLSS FG: paused false, sleeping true, 752 evaluations and 751 generated presents.
- Smart Reflex disabled: sleeping true; re-enabled: paused again.
- A separate two-second paused profile recorded zero samples for Reflex sleep,
  driver call and semaphore wait.

Latency markers continue working while paused: the existing implicit-attribution
diagnostic (`reflex_submission=0`) returned render latency around 4.4 to 4.8 ms in
this smoke scene. The shipped explicit attribution still reports the previously
documented inconsistent driver timestamps. That diagnostic was restored to 1 after
verification. Smart Reflex does not conceal or change the latency validity checks.
These functional samples are not the Benchmark Mod throughput result.

Pre-implementation comparison: [Reflex AntiLag](https://modrinth.com/mod/reflex-antilag)
and its [scheduler source](https://github.com/Tythee/Minecraft-Reflex/blob/multi-version/src/main/java/io/tythee/ReflexScheduler.java)
use measured timing to decide whether to sleep independently of marker collection.
Reflexium retains its existing Vulkan implementation and adds only the requested
high-FPS decision.
