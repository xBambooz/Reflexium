#version 330
#extension GL_ARB_separate_shader_objects : require

// Velocity of moving entity surfaces: previous screen position minus current screen position (jitter
// removed), in render pixels, the same convention as dlss_motion.fsh. A fragment only writes when its
// quad is the visible surface: its depth equals the world depth already in the frame, which is what
// the level pass wrote for this exact geometry. Cutout holes, hidden faces and the first-person hand
// fail the test and keep their camera-only vector.

uniform sampler2D DepthSampler;     // world depth before the hand was drawn
uniform sampler2D HandDepthSampler; // depth containing only the hand (0 = no hand)

layout(std140) uniform ReflexiumObjectMotion {
    mat4 CurrentJitteredViewProjection;
    mat4 CurrentViewProjection;
    mat4 PreviousViewProjection;
    vec4 TargetSize;
};

layout(location = 0) in vec4 currentClip;
layout(location = 1) in vec4 previousClip;
layout(location = 2) flat in float cullFace;
layout(location = 0) out vec4 fragColor;

void main() {
    if (cullFace > 0.5 && !gl_FrontFacing) discard;
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    if (texelFetch(HandDepthSampler, pixel, 0).r != 0.0) {
        discard;
    }
    float sceneDepth = texelFetch(DepthSampler, pixel, 0).r;
    // Relative tolerance: the same triangle rasterized from a pre-multiplied matrix differs from the
    // level pass by a few ulps, while a different surface is further apart than this by orders of magnitude.
    if (sceneDepth == 0.0 || abs(gl_FragCoord.z - sceneDepth) > max(sceneDepth * 2.0e-5, 2.0e-8)) {
        discard;
    }
    if (currentClip.w <= 0.0 || previousClip.w <= 0.0) {
        discard;
    }

    vec2 currentNdc = currentClip.xy / currentClip.w;
    vec2 previousNdc = previousClip.xy / previousClip.w;
    fragColor = vec4((previousNdc - currentNdc) * 0.5 * TargetSize.xy, 0.0, 1.0);
}
