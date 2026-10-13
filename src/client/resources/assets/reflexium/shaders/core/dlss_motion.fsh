#version 330
#extension GL_ARB_separate_shader_objects : require

// DLSS motion vectors from depth reprojection: previous-frame pixel position minus current pixel
// position, in render pixels. Covers camera rotation and translation; moving entities are treated as
// static. Pixels covered by the first-person hand get zero motion (the hand moves with the camera).

uniform sampler2D DepthSampler;     // world depth before the hand was drawn
uniform sampler2D HandDepthSampler; // depth containing only the hand (0 = no hand)

layout(std140) uniform ReflexiumMotion {
    mat4 CurrentToPrevious; // previous unjittered view-projection * camera delta * inverse(current jittered view-projection)
    vec4 JitterNdcAndSize;  // xy: current jitter in NDC, zw: render size in pixels
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    float handDepth = texelFetch(HandDepthSampler, pixel, 0).r;
    if (handDepth != 0.0) {
        gl_FragDepth = handDepth;
        fragColor = vec4(0.0);
        return;
    }

    float depth = texelFetch(DepthSampler, pixel, 0).r;
    gl_FragDepth = depth;
    vec2 ndc = texCoord * 2.0 - 1.0;
    vec4 previous = CurrentToPrevious * vec4(ndc, depth, 1.0);
    vec2 previousNdc = previous.w != 0.0 ? previous.xy / previous.w : ndc;
    vec2 deltaNdc = previousNdc - (ndc - JitterNdcAndSize.xy);

    // The Vulkan viewport is positive-height. Minecraft flips Y only in its final swapchain blit.
    // ponytail: camera-only vectors treat moving entities as static; add per-object velocities to fix their trails.
    fragColor = vec4(deltaNdc * 0.5 * JitterNdcAndSize.zw, 0.0, 1.0);
}
