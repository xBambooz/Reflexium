# Shader loader direction (2026-10-16)

Ryan's decision: Reflexium targets the experimental Iris Vulkan build (1.12.0-snapshot+mc26.3) from now on. Vitrail is retired.

- Dev run (`run/mods`) uses Iris with ComplementaryReimagined_r5.9.3. Vitrail 0.12.0 sits in `run/mods-off` for reference only.
- New shader-pack work goes to the Iris adapter (`compat/IrisCompat`, `mixin/compat/iris`). Do not add Vitrail features.
- Still Vitrail-only and to be ported to Iris: the opt-in pack HDR boundary (`ComplementaryHdr`, `PackPassMixin`) and pack surface motion for water/glass (`ComplementaryMotionSource`, `PackValuesMixin`, `pack_surface_motion.fsh`). Remove the Vitrail mixins once those are ported.
- `docs/VITRAIL.md` is historical.
