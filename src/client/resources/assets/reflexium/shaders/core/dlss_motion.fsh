#version 330
#extension GL_ARB_separate_shader_objects : require

// DLSS motion vectors from depth reprojection: previous-frame pixel position minus current pixel
// position, in render pixels. Covers camera rotation and translation; ObjectVelocityPass overwrites
// visible moving-object surfaces. Pixels covered by the first-person hand get zero motion.

uniform sampler2D DepthSampler;     // world depth before the hand was drawn
uniform sampler2D HandDepthSampler; // depth containing only the hand (0 = no hand)
uniform sampler2D DhDepthSampler;

layout(std140) uniform ReflexiumMotion {
    mat4 CurrentToPrevious; // previous unjittered view-projection * camera delta * inverse(current jittered view-projection)
    mat4 DhToMain;
    mat4 DhToPrevious;
    vec4 JitterNdcAndSize;  // xy: current jitter in NDC, zw: render size in pixels
    vec4 DepthEncoding;    // xy: main depth scale/offset into the extended range, z: DH available
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    float handDepth = texelFetch(HandDepthSampler, pixel, 0).r;
    if (handDepth != 0.0) {
        gl_FragDepth = handDepth * DepthEncoding.x + DepthEncoding.y;
        fragColor = vec4(0.0);
        return;
    }

    float depth = texelFetch(DepthSampler, pixel, 0).r;
    vec2 ndc = texCoord * 2.0 - 1.0;
    vec4 previous = CurrentToPrevious * vec4(ndc, depth, 1.0);
    bool surface = depth > 0.0;
    if (DepthEncoding.z > 0.5) {
        float dhDepth = texelFetch(DhDepthSampler, pixel, 0).r;
        vec4 dhClip = vec4(ndc, dhDepth, 1.0);
        vec4 mainClip = DhToMain * dhClip;
        // Compare in the same projection: reverse-Z is monotonic in view distance.
        // A valid LOD beyond Minecraft's far plane has negative main depth, not sky.
        float mainDepth = mainClip.z / mainClip.w;
        if (dhDepth > 0.0 && mainClip.w > 0.0 && (!surface || mainDepth > depth)) {
            depth = mainDepth;
            surface = true;
            previous = DhToPrevious * dhClip;
        }
    }
    gl_FragDepth = surface ? depth * DepthEncoding.x + DepthEncoding.y : 0.0;
    vec2 previousNdc = previous.w != 0.0 ? previous.xy / previous.w : ndc;
    vec2 deltaNdc = previousNdc - (ndc - JitterNdcAndSize.xy);

    // The Vulkan viewport is positive-height. Minecraft flips Y only in its final swapchain blit.
    fragColor = vec4(deltaNdc * 0.5 * JitterNdcAndSize.zw, 0.0, 1.0);
}
