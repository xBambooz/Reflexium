#version 330
#extension GL_ARB_separate_shader_objects : require

// Debug view of the DLSS motion vectors (RG16F, render pixels): hue is the direction of motion,
// brightness is its magnitude (full brightness at 4 pixels, square-root scaled so sub-pixel motion
// is still visible). Static pixels and the first-person hand are black.

uniform sampler2D MotionSampler;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

vec3 hsv(float h, float s, float v) {
    vec3 k = clamp(abs(fract(vec3(h) + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0) - 1.0, 0.0, 1.0);
    return v * mix(vec3(1.0), k, s);
}

void main() {
    vec2 motion = texture(MotionSampler, texCoord).rg;
    float magnitude = length(motion);
    if (magnitude < 0.0001) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    float hue = atan(motion.y, motion.x) / 6.2831853 + 0.5;
    float value = clamp(sqrt(magnitude / 4.0), 0.0, 1.0);
    fragColor = vec4(hsv(hue, 1.0, value), 1.0);
}
