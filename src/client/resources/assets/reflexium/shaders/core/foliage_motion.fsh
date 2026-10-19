#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D Surface;
uniform sampler2D SceneDepth;
uniform sampler2D HandDepth;
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec4 surface = texelFetch(Surface, pixel, 0);
    float depth = texelFetch(SceneDepth, pixel, 0).r;
    if (surface.a < 0.5 || texelFetch(HandDepth, pixel, 0).r != 0.0 || depth == 0.0) discard;
    if (abs(surface.z - depth) > max(depth * 2e-5, 2e-8)) discard;
    fragColor = vec4(surface.xy * vec2(textureSize(Surface, 0)), 0.0, 1.0);
}
