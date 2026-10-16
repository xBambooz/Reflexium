# Distant Horizons upscaling integration

Targets DH `3.3.5-dev` and the supported Iris `1.12.0-snapshot+mc26.3` Vulkan build.
Both integrations use reflection and optional mixins, without a DH or Iris compile dependency.
Unknown DH versions or invalid captured resources disable upscaling with a log message.

## Inputs

- Native DH contributes its D32 depth view and the exact `dhMvmProjMatrix` uploaded by
  `BlazeDhTerrainRenderer`. Its API AA override is false while reconstruction is active,
  removing DH's extra jitter and TAA. The saved setting is unchanged. A prior API override
  is restored on exit, unless another API owner replaced Reflexium in the meantime.
- Iris contributes `DHCompatInternal.getDepthTexture(false)`, projection and modelview.
  The projection is converted from pack GL clip coordinates to the reverse-Z Vulkan
  coordinates written by Iris' `DepthTransformer`. Shadow draws and early skipped terrain
  draws do not publish depth. `DHCompat.getProjection(float,float)` receives Reflexium's
  current jitter once, after Iris rebuilds the matrix.
- Capture is cleared every frame. No DH draw means no reuse of the preceding frame's depth.
  Texture dimensions and format must match the current input frame.

The motion pass reconstructs DH depth through its actual inverse view-projection and compares
it with main depth in the main projection, whose reverse-Z ordering is monotonic in view
distance. It retains negative main-projection depths for LODs beyond Minecraft's far plane.
The vendor input uses the main near plane and a far plane at least twice DH's far plane.
An affine clip-Z remap re-encodes both sources into that range; sky alone stays zero.
DH camera vectors use its previous unjittered view-projection, camera translation and the
current inverse projection. Motion remains previous minus current in render pixels.

The hand mask takes priority. Object velocity visibility uses the merged depth and remapped
raster projection; its unjittered motion matrices and the hand animation override are retained.
FSR and frame generation receive the extended depth metadata. Display-depth copies undo the
remap for vanilla post effects, which still use Minecraft's original clip range.

## Verification

`./gradlew.bat build -PreflexiumInstallDir=build/dh-install` builds without deploying to a
shared instance. `distantHorizonsCheck` runs depth re-encoding and ordering checks, stationary
jitter cancellation, camera rotation/translation, GLSL-to-SPIR-V compilation and optional ABI
checks against the installed DH and Iris jars. Existing object/hand motion checks also run.

References: the installed DH 3.3.5-dev and Iris local jars, and the
[Iris DH projection implementation](https://github.com/IrisShaders/Iris/blob/26.3/common/src/main/java/net/irisshaders/iris/compat/dh/DHCompat.java).

In-game mixin application, AA restoration across toggles, LOD seams, distant water,
camera pans, resize/reload, and DLSS/FSR/FG image quality remain unverified. Shader-pack
deformation or custom depth writes need pack-specific validation. BSL additionally needs
the separate main-branch change that removes its own TAA and jitter; it is not included here.
