# Release objectives (hard requirements)

Set by Ryan on 2026-10-24. Every change and every validation report must be checked against these.
A release is blocked while any of them is missing, broken or only partly met. Report a miss
honestly with measurements; never redefine an objective to make it pass.

1. **DLSS beats native, including without shaders.** DLSS Quality, Balanced and Performance must give
   more FPS than native in normal gameplay scenes, with shaders and with the vanilla renderer. DLAA is the
   only mode allowed to cost FPS. Measure at the user's real resolutions (1080p, 1440p, 4K), not only at
   one CPU-bound scene. If a scene is truly CPU bound, upscaling must not lose meaningful FPS: its fixed
   overhead (evaluation, copies, syncs, extra passes) must be cut until it is a negligible cost.
2. **Proper implementation, no bloat or band-aids.** Integrate the way an NVIDIA or Mojang engineer
   would ship it, following NVIDIA's official guidance (Streamline programming guides and
   https://github.com/NVIDIA/skills). No heuristic patch layers like the removed Smart Reflex, no Auto
   probes that guess around bugs, and no dead options. Fix root causes in the inputs (motion vectors,
   jitter, depth, matrices, pacing).
3. **No refresh-rate lock.** Frame generation and Reflex never cap output to the monitor refresh rate by
   default. A refresh-based cap applies only when the user runs G-Sync/VRR with VSync (the NVIDIA-standard
   behaviour), when they set their own FPS limit, or in an explicit Auto mode.
4. **Reflex works properly in CPU-bound play, like Valorant.** In CPU-bound scenes Reflex On must cut
   system latency (render queue near zero) without a meaningful FPS loss, and Boost must behave as NVIDIA
   documents. Validate with Reflex/PCL latency markers, not FPS alone.
5. **Upscaled image as close to 1:1 with native as possible.** No added blur, smear, ghosting, extra
   pixelation, shimmer or detail loss compared with native, at rest, while walking (view bob on), during
   camera pans, and on moving mobs. Distant detail is checked on the test range (see
   ai-brain minecraft/agent-harness.md). Correct mip/LOD bias, jitter, exposure and motion vectors are
   prerequisites.

6. **Every NVIDIA integration rule works as shipped games do, including ones not listed here.** Agents must
   find and apply the official rules from the Streamline programming guides, the NVIDIA skills checklists and
   the DLSS/Reflex UI guidelines, not just the items Ryan named. A minimum checklist, each verified in game:
   - Frame generation requires Reflex. When FG is on, Reflex is forced On (Off greyed out with an explanation)
     and turning FG off restores the user's Reflex choice.
   - Feature gating by hardware and driver: FG only on GPUs/drivers that support it (RTX 40 and newer), MFG
     multipliers only where supported (RTX 50), DLSS only on RTX. Unsupported options are hidden or greyed
     out with a reason, never silently doing nothing.
   - VSync with FG follows NVIDIA guidance (in-game VSync off under FG; G-Sync/VRR + driver VSync gets the
     NVIDIA-standard cap just under refresh). No refresh cap otherwise.
   - FG is paused and history reset on menus, pause, loading, world changes, teleports, dimension changes,
     resize and camera cuts. The HUD/UI is never interpolated.
   - DLSS SR modes use NVIDIA's scale ratios (DLAA 1.0, Quality 0.667, Balanced 0.58, Performance 0.5, Ultra
     Performance 0.333), the official preset/model selection, no legacy sharpening, the correct exposure
     handling, mip bias per the guide, and resets on camera cuts.
   - Reflex: Off/On/On+Boost per the Reflex UI guidelines, latency markers placed per the guide, and the PCL
     latency flash/stats behaviour where applicable.
   - Settings persist, apply live without a restart where the SDK allows, and failures fall back gracefully
     with a clear in-game message and log line (no crash, no black screen).
   - Works in windowed, borderless and fullscreen, survives alt-tab and minimize, and handles driver/device
     loss.

## Measurement validity (every FPS or image result must pass these, or it is discarded)

A number only counts if the same sample also proves the scene was really rendering the intended way:

- `/status`: `world_loaded` true, `screen` none, not paused, camera at the documented pose (y 131 platform,
  151 range, forest pose), render distance as documented.
- Mode proof: `upscaler_effective`, `status_line` and `render_size` match the requested mode (for example
  DLSS Quality at 1440p reports 1707x960 -> 2560x1440), the evaluated-frame counter rises during the window,
  and for FG the generated-present counter rises. Native rows prove `upscaling_active` false.
- Content proof: a screenshot taken inside the window is not black or blank (mean luma and variance above
  a set floor, a non-trivial edge count) and matches the reference view (same pose, normalized correlation
  with the native shot).
- Load proof: the title-screen idle CPU/GPU is flat before the run; no background world/LOD generation;
  shader pack reload settled (9 s or more); every pair repeated, accepted only if repeats agree within 3%.
- Backend proof: Vulkan backend, the pack named in `pack`, and no `last_error`.
- Any reading where these checks fail is reported as invalid, never as a gain.

## Decision log

- 2026-10-24 (Ryan): Objective 1 for shaderless scenes is met if, after all overhead is cut, DLSS
  Quality/Balanced/Performance cost no more than about 2 percent versus native (measured and shown, not
  hidden), the in-game tooltip recommends Off/Auto without shaders, and with shaders DLSS clearly wins.
- 2026-10-24 (Ryan): Agents may focus the test window on the secondary monitor overnight for frame
  generation tests while the PC is unattended. Restore the previous foreground window afterwards.
- 2026-10-24 (Ryan): Final acceptance also runs in Ryan's real Modrinth profile
  (`%APPDATA%\ModrinthApp\profiles\26.3 Testing Instance`, the build's installReflexium target), not
  only the dev harness: verify the installed jar hash, launch, join a world, check status and visuals.
  During unattended runs, cover the primary OLED monitor with `tools\oled-overlay.ps1` (black, never
  takes focus, click it and press Esc to close).
- 2026-10-24 (Ryan): When verified, commit to a new branch (not main) and push for review. Rounds stay on
  DeepSeek V4.1 Flash (max).

- 2026-10-24: Migrate DLSS SR, DLSS-G/MFG and Reflex/PCL to NVIDIA Streamline (plan:
  [specs/STREAMLINE_MIGRATION_SPEC.md](specs/STREAMLINE_MIGRATION_SPEC.md)) because objective 2 requires
  NVIDIA's officially supported integration path. Keep the working raw NGX path as the fallback until
  the Streamline path matches or beats it on every objective, then delete the old path. FSR stays separate.
- 2026-10-24 (round 10): Streamline SR, DLSS-G and Reflex/PCL are the only NVIDIA paths. The raw NGX
  bridge, the raw `VK_NV_low_latency2` integration, the `-Dreflexium.streamline` switch and the
  present-metering path are deleted. FSR remains separate. The DLSS-G bit-1 transition allowance is
  removed; Reflex sleeps for two completed frames before DLSS-G options are enabled.
