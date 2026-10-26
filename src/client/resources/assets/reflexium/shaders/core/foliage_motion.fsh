#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D Surface;
uniform sampler2D SceneDepth;
uniform sampler2D HandDepth;
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    // The diagnostic display can have a different extent from the input.
#ifdef RX_DEPTH_DEBUG
    pixel = clamp(ivec2(texCoord * vec2(textureSize(Surface, 0))), ivec2(0), textureSize(Surface, 0) - 1);
#endif
    vec4 surface = texelFetch(Surface, pixel, 0);
    float depth = texelFetch(SceneDepth, pixel, 0).r;
    bool match = surface.a >= 0.5 && texelFetch(HandDepth, pixel, 0).r == 0.0 && depth != 0.0
        && abs(surface.z - depth) <= max(depth * 2e-5, 2e-8);
#ifdef RX_DEPTH_DEBUG
    if (surface.a < 0.5) { fragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }
    fragColor = match ? vec4(0.0, 1.0, 0.0, 1.0) : vec4(1.0, 0.0, 0.0, 1.0);
#else
    if (!match) discard;
    fragColor = vec4(surface.xy * vec2(textureSize(Surface, 0)), 0.0, 1.0);
#endif
}
