# Frame generation

Windows x64 Vulkan integration, off by default. Vendor research was completed with Gemini 3.8 and
checked against the installed SDK headers before implementation. Primary sources and hardware
distinctions are in [the vendor reference](FRAME_GENERATION_REFERENCE.md). The 2026-10-23
measurements are in [the performance report](PERFORMANCE.md).

## Modes

| Setting | Output | Requirements |
| --- | --- | --- |
| DLSS Frame Generation | One generated frame plus one rendered frame, 2x | Runtime FG support, normally RTX 40/50 series; Reflex and NVIDIA present metering |
| DLSS Multi Frame Generation | Runtime-limited multiplier; tested 3x through 6x | MFG-capable runtime/GPU, normally RTX 50 series |
| FSR Frame Generation | One generated frame plus one rendered frame, 2x | Signed FSR 3.1.4 Vulkan runtime and a supported presentation route |

The multiplier counts the real frame. The current RTX 5070 / driver 617.42 / NGX 310.9.1 reports
five generated frames, hence 6x maximum. A runtime reporting one generated frame exposes standard
2x FG; it does not enable MFG. Unsupported selections show their reason in status and render normally.
This is static MFG. Streamline's fixed DLSS-G path supports 2x/3x/4x and is the default on Vulkan;
Streamline Dynamic MFG is D3D12-only at 2.14.16, so Auto remains the application policy. The raw NGX
path stays available with `-Dreflexium.streamline=false`.

Selecting a multiplier above 2x selects MFG for NVIDIA modes. Selecting standard DLSS FG resets
the multiplier to 2x. Saved NVIDIA settings are normalized on load, so stored 3x cannot silently
run standard 2x FG. Off and FSR selections are preserved when changing the multiplier.

Auto first measures rendered-only throughput for about one second. At or above 95% of the monitor's
refresh-headroom target it stays rendered-only; below 80% it picks a multiplier up to the runtime
limit. This hysteresis also applies to later workload changes. Status exposes the bypass and its
refresh target. Auto respects a lower explicit user cap, and unknown refresh with no explicit cap
leaves generation uncapped.

A fixed multiplier (`frame_generation_multiplier` 2..8) is an explicit request to present real x N.
It never receives the refresh / N budget: the old behaviour capped total presents at 223 on a 240 Hz
display, which cut real rendering to 111 at 2x and 56 at 4x. That is not how DLSS-G ships. Fixed
multipliers are uncapped unless VSync, a user FPS limit or the driver's VSync/VRR cap applies. The
NVIDIA Vulkan interval limits all presents, so any output budget is applied to total presents at
most, never divided by the multiplier. This is an application policy, not NVIDIA Streamline Dynamic
MFG; the migration plan is [STREAMLINE_MIGRATION_SPEC.md](specs/STREAMLINE_MIGRATION_SPEC.md).

DLSS FG works independently of DLSS SR, operating at native resolution when the upscaler is Off,
or with Auto or DLSS. With the upscaler Off, the renderer keeps Minecraft's window-sized colour and
depth targets, records camera and object motion into a separate render-size target and evaluates
FG on that image. No full-resolution colour or depth copy and no render-size swap runs on this path.
Selecting an explicitly mismatched backend disables FG. The renderer retains native and
cross-backend paths from earlier acceptance; the [2026-10-14 check](PERFORMANCE.md) covers the
current settings combinations. Menus, loading frames, missing world frames, camera cuts and target
recreation reset interpolation history. Generation is skipped while any GUI screen is open, not just
for the menu blur, so a still inventory or chat panel is never tagged as a game scene. FG does not
increase game ticks or input sampling. A base rate of at least 60 FPS is recommended by the vendors
for quality and latency, rather than enforced as an initialization requirement.

## Streamline DLSS-G path

Streamline is the default path for DLSS Super Resolution, Reflex/PCL and frame generation; it runs
NVIDIA Streamline 2.14.16. The raw NGX FG path above remains the fallback with
`-Dreflexium.streamline=false`.

On this path the application renders one real frame and presents it through Streamline's Vulkan
swapchain proxy. Streamline owns the generated presents, so no application-side generated colour
targets or present batches exist. For 2x, 3x and 4x, `slDLSSGSetOptions` receives
`numFramesToGenerate` 1, 2 and 3. A fixed multiplier is used directly and never reduced by an output
budget. Auto stays the application refresh-headroom policy described above; Streamline Dynamic MFG
is D3D12-only at this SDK pin, so Vulkan Auto is not `DLSSGMode::eDynamic`.

Each real frame tags display-resolution hudless colour (after post effects and scene particles,
before the GUI), render-resolution depth and motion vectors, and a UI alpha buffer rendered by
replaying the GUI draw list into a cleared transparent target. UI alpha is preferred over UI colour
plus alpha because the plugin derives UI colour from final and hudless, which keeps invert blends
such as the vanilla crosshair correct on generated frames. Menus, pause, loading and missing world
frames set DLSS-G off; the options use `eRetainResourcesWhenOff` so a pause does not reallocate.

Minecraft's final swapchain blit mirrors Y. DLSS-G composes straight into that presented backbuffer,
so the tagged hudless, depth, motion and UI alpha images are mirrored into the same orientation,
motion-vector Y is normalized with a negative scale, jitter Y is negated, and the clip transforms
use the `F * M * F` conjugation with `F = diag(1, -1, 1, 1)`. Passing unmirrored inputs would
misalign every tagged resource against the backbuffer.

The reported generated-frame count comes from `slDLSSGGetState().numFramesActuallyPresented`, not
from a nominal multiplier, and status shows the Streamline state, maximum multiplier, runtime status
and accumulated generated presents. `slDLSSGGetState` also reports the runtime status; a non-OK
status turns generation off and surfaces the code. DLSS-G pauses interpolation while the window is
unfocused; status says so explicitly instead of claiming a 2x output. An unactivated window, such as
the agent harness test window, is reported the same way even when Windows returns it as the
foreground window; generated presents cannot be measured there. Round 6 confirmed zero generated
presents with effective multipliers 2, 3 and 4 in that window, so generated-frame counts and
banding still need a normally launched client.

Streamline allows one common-constants block per frame. DLSS-G needs the mirrored backbuffer
orientation while DLSS SR needs the unmirrored scene, so when Streamline FG is active the SR feature
stays on the raw NGX path; with FG off SR uses Streamline. The presented swapchain selects
`VK_FORMAT_A2B10G10R10_UNORM_PACK32` when Streamline is initialized and an NVIDIA FG mode is
selected, so the pack dither survives an 8-bit to 10-bit presentation. DLSS-G still does not accept
FP16 output.

## Presentation and GUI

On supported NVIDIA devices, `VK_NV_present_metering` paces a batch of generated frames followed
by the real frame. The extension and feature are queried before device creation. Only the first
present in each batch carries `VkSetPresentConfigNV`. The driver feedback is checked on return;
if feedback is rejected or present fails, the batch is abandoned cleanly and normal real delivery
is restored. Swapchain recreation queries physical surface capabilities and bounds image counts
between `minImageCount` and `maxImageCount` (allowing unbounded when max is 0).
VSync must be off for this route. DLSS FG enables Reflex while requested, preserving the saved
Reflex preference. Real presents retain simulation-frame IDs; generated presents use out-of-band
markers (`VK_LATENCY_MARKER_OUT_OF_BAND_PRESENT_START_NV` / `END_NV`) and do not consume the real present ID.

Vulkan presentation surfaces track binary acquire semaphores with completion fences (`reflexium$acquireCompletion`)
tied to the command submission that consumes each signal. Before any binary acquire semaphore is reused,
its prior consuming submission is guaranteed complete, replacing arbitrary ring sizing with an explicit
GPU synchronization invariant.

Metered surfaces reserve 16 acquire semaphores for two real frames of up to eight presents, with
the same completion checks before reuse. Replacement presents use a persistent encoder per output
slot on the existing graphics queue. Each encoder retains RenderPearl's two-submit completion and
pool-reset rules, preventing replacement blits from rotating the world's two-submit pool. Encoders
remain alive across FG toggles because surface acquire fences can still reference their timelines.

On other supported GPUs, FSR uses AMD's signed Vulkan swapchain scheduler. Three additional
queues are reserved only when the physical family has room. SDK presentation threads use those
private queues. All swapchain operations go through the SDK; its intermediate images finish in
GENERAL layout. Interpolation runs on the app's graphics queue, followed by a native scheduler
callback that copies the already generated image into the SDK's interpolation target, including
Minecraft's presentation Y flip and format conversion. The native callback receives frame-specific
user context (`ScheduledFrame`) with frame ID verification and pending-present drainage, with errors
propagated across wait/pause/destroy. Async interpolation is disabled. The SDK owns real/generated
presentation pacing. Insufficient queue or surface support disables this route. Reflex never receives
an opaque FSR proxy handle.

The world is interpolated before the GUI. `GuiRenderer` prepares/uploads once, then replays its
draw list on each generated target before discarding the list. This preserves text, hotbars and
destination-dependent blending such as the crosshair. It costs additional GUI draws per output.
Screenshots of the main target continue to contain the rendered world and GUI. Minecraft's internal
simulation and frame counting (`Minecraft.getFps`) remain unmutated so other mods' timing logic is
unaffected. Reflexium's debug HUD and F3 lines append the real rendered frame rate alongside submitted
generation rate (`FpsText.withRendered`), reporting measured submitted presentation operations over a
matching 1-second window rather than an unverified multiplier or scanout claim.

Native contexts and image resources are independent of upscaler contexts. Changes flush submitted
work and wait before releasing GPU resources. The AMD scheduler is disabled and drained before
its referenced FG resources are released. Mode changes can retry failures; an interpolation failure
returns to ordinary rendered frames and reports the reason.

Missing NVIDIA SR/FG production DLLs are downloaded from pinned NVIDIA commit
`374959484e79a640feaba44c93ac8cfb0a03f5b5`, hash checked and loaded after the download completes.
FG SHA-256: `ff6e90eb78b827927dff5b4ecc6b1c870c2e9bca29ed9f48c7d348cc9e170b82`.
Existing DLLs are preserved. Failure to download optional FG does not disable an installed SR runtime.

## Verification, 2026-10-23

On the smoke platform at 1920x1080 with BSL, DLSS Performance and Reflex On, fixed multipliers are
now uncapped: FG off 462.8 real FPS; 2x 289 real and 578 submitted presents; 3x 233 real and 699
submitted; 4x 185 real and 739 submitted. Output follows real x N exactly and is not held at the
223 FPS refresh-headroom target. Auto with a 462 FPS scene correctly stays rendered-only (no 2x fits
under the budget) and is also uncapped. Counters and the raw table are in
[the October 23 pack and frame generation report](validation/pack-fg-bob-2026-10-23.md). An open
creative inventory still stops evaluations until it closes.

## Validation, 2026-10-13

Host: Windows 11, RTX 5070, NVIDIA 617.42, Java 25, Minecraft 26.3. Vitrail 0.12.0-beta+mc26.3,
Sodium 0.9.2 and Complementary Reimagined r5.9.3 were loaded. No real Minecraft process was closed;
debug sessions were identified by PID, injected harness jar and exact game directory.

- Minecraft's actual Vulkan device reports FG available, no driver update needed, maximum five generated frames.
- DLSS 2x, DLSS MFG 3x/4x/5x/6x, FSR 2x each passed with Auto/DLSS, FSR and native rendering (18 combinations).
  Each steady sample advanced evaluation and generated-present counts by the expected 1, 2, 3, 4 or 5 frames per rendered frame.
- Real and generated image readbacks contain the scene, first-person hand and full-resolution GUI.
- The AMD scheduler can be forced on NVIDIA for integration tests with `-Dreflexium.fsr.forceSwapchain=true``.
  Its context, swapchain creation, native interpolation, 960x540/1280x720 resize, and world quit/rejoin were exercised.
  All three upscaler pairings passed; steady samples recorded 359/361/361 SDK presents for 179/180/179 evaluations.
  Actual AMD/Intel hardware is untested.
- Native builds and Java checks cover typed FFM calls, row-vector camera matrix ABI, resource JSON,
  reprojection/jitter, object history and hash-checked runtime installation.

Repeat with a joined debug world on the authenticated harness at port 25601:

```powershell
.\\tools\\check-frame-generation.ps1
```

The script verifies process identity, checks supported mode/upscaler combinations, writes ignored
JSON evidence under `build/`, and restores the debug settings. It does not close processes.
The optional legacy debug server can read a generated target via
`/screenshot?fg_index=0&gui=1&path=screenshots/generated.png` (zero-based index).

### Remaining acceptance

Context creation, successful dispatch, image readback and present submission do not prove smooth
physical scanout. Displayed-frame cadence and input latency need a suitable FrameView capture;
the bundled local PresentMon CLI exited without producing a capture in this session. Do not use
submission timestamps alone to certify NVIDIA hardware metering. High multiplier output can also
diverge from uniform frame delivery under GPU saturation.
