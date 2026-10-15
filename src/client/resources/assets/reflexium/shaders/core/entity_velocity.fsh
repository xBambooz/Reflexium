#version 330
#extension GL_ARB_separate_shader_objects : require

// Velocity of moving entity surfaces: previous screen position minus current screen position (jitter
// removed), in render pixels, the same convention as dlss_motion.fsh. A fragment only writes when its
// quad is the visible surface: its depth equals the depth already in the frame, which is what the level
// pass wrote for this exact geometry. Cutout holes and hidden faces fail the test and keep their
// camera-only vector.
//
// TargetSize.z selects the surface the quads must match. World objects match the world depth and never
// write where the first-person hand is. Hand quads (eye-space vertices, hand projection) match the
// hand-only depth, so the swinging or equipping hand gets its own vector instead of zero.

uniform sampler2D DepthSampler;     // world depth before the hand was drawn
uniform sampler2D HandDepthSampler; // depth containing only the hand (0 = no hand)

layout(std140) uniform ReflexiumObjectMotion {
    mat4 CurrentJitteredViewProjection;
    mat4 CurrentViewProjection;
    mat4 PreviousViewProjection;
    vec4 TargetSize;                    // xy: render size in pixels, z: 1 for the hand pass
};

layout(location = 0) in vec4 currentClip;
layout(location = 1) in vec4 previousClip;
layout(location = 2) flat in float cullFace;
layout(location = 0) out vec4 fragColor;

void main() {
    if (cullFace > 0.5 && !gl_FrontFacing) discard;
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    float handDepth = texelFetch(HandDepthSampler, pixel, 0).r;
    float surfaceDepth;
    if (TargetSize.z > 0.5) {
        if (handDepth == 0.0) discard;
        surfaceDepth = handDepth;
    } else {
        if (handDepth != 0.0) discard;
        surfaceDepth = texelFetch(DepthSampler, pixel, 0).r;
        if (surfaceDepth == 0.0) discard;
    }
    // Relative tolerance: the same triangle rasterized from a pre-multiplied matrix differs from the
    // level pass by a few ulps, while a different surface is further apart than this by orders of magnitude.
    if (abs(gl_FragCoord.z - surfaceDepth) > max(surfaceDepth * 2.0e-5, 2.0e-8)) {
        discard;
    }
    if (currentClip.w <= 0.0 || previousClip.w <= 0.0) {
        discard;
    }

    vec2 currentNdc = currentClip.xy / currentClip.w;
    vec2 previousNdc = previousClip.xy / previousClip.w;
    fragColor = vec4((previousNdc - currentNdc) * 0.5 * TargetSize.xy, 0.0, 1.0);
}
