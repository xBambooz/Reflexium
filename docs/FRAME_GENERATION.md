# Frame generation

Experimental Windows x64 Vulkan integration, off by default. Vendor research was completed with
Gemini 3.8 and checked against the installed SDK headers before implementation. Primary sources
and hardware distinctions are in [the vendor reference](FRAME_GENERATION_REFERENCE.md).

## Modes

| Setting | Output | Requirements |
| --- | --- | --- |
| DLSS Frame Generation | One generated frame plus one rendered frame, 2x | Runtime FG support, normally RTX 40/50 series; Reflex and NVIDIA present metering |
| DLSS Multi Frame Generation | Runtime-limited multiplier; tested 3x through 6x | MFG-capable runtime/GPU, normally RTX 50 series |
| FSR Frame Generation | One generated frame plus one rendered frame, 2x | Signed FSR 3.1.4 Vulkan runtime and a supported presentation route |

The multiplier counts the real frame. The current RTX 5070 / driver 617.42 / NGX 310.9.1 reports
five generated frames, hence 6x maximum. A runtime reporting one generated frame exposes standard
2x FG; it does not enable MFG. Unsupported selections show their reason in status and render normally.
This is static MFG. Streamline's dynamic multiplier selection is not integrated.

Current settings require Auto or the matching upscaler. Selecting Off or an explicitly mismatched
backend disables FG. The renderer retains native and cross-backend paths from earlier acceptance;
the [2026-10-14 check](PERFORMANCE.md) covers the current settings combinations.
Menus, missing world frames, camera cuts and target recreation reset interpolation history.
FG does not increase game ticks or input sampling. A base rate of at least 60 FPS is recommended
by the vendors for quality and latency, rather than enforced as an initialization requirement.

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
