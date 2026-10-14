// Packed float-only ABI shared with FrameGenerationConstants.java (99 floats).
#pragma once
#include <cstdint>
struct RxFrameConstants {
    float viewToClip[16], clipToView[16], clipToLens[16], clipToPrevious[16], previousToClip[16];
    float jitter[2]; // render pixels, as supplied to the upscaler
    float position[3], up[3], right[3], forward[3];
    float nearPlane, farPlane, verticalFov, aspect, frameTimeMs;
};
static_assert(sizeof(RxFrameConstants) == 99 * sizeof(float));
