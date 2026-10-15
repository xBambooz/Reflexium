#version 330
#extension GL_ARB_separate_shader_objects : require

// Debug capture only. Screenshot readback assumes four 8-bit channels, so the RG16F motion vectors and the
// D32 scene depth the upscaler consumed are packed into RGBA8 and decoded by tools/CaptureAnalysis.java.
//   mode 0: motion, render pixels, two 12-bit fixed point values (1/32 px, +-64 px, clamped), because screenshot
//           readback forces alpha opaque: R = x[11:4], G = x[3:0] << 4 | y[11:8], B = y[7:0]
//   mode 1: depth, 24-bit fixed point in R,G,B (alpha 255)

uniform sampler2D MotionSampler;
uniform sampler2D DepthSampler;

layout(std140) uniform ReflexiumCaptureEncode {
    vec4 Mode; // x: 0 motion, 1 depth
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    if (Mode.x < 0.5) {
        vec2 motion = texelFetch(MotionSampler, pixel, 0).rg;
        uvec2 q = uvec2(clamp((motion + 64.0) * 32.0, 0.0, 4095.0) + 0.5);
        fragColor = vec4(float(q.x >> 4), float(((q.x & 15u) << 4) | (q.y >> 8)), float(q.y & 255u), 255.0) / 255.0;
    } else {
        float depth = texelFetch(DepthSampler, pixel, 0).r;
        uint d = uint(clamp(depth, 0.0, 1.0) * 16777215.0 + 0.5);
        fragColor = vec4(float((d >> 16) & 255u), float((d >> 8) & 255u), float(d & 255u), 255.0) / 255.0;
    }
}
