#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D BeforeSampler;
uniform sampler2D AfterSampler;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    float before = texelFetch(BeforeSampler, pixel, 0).r;
    float after = texelFetch(AfterSampler, pixel, 0).r;
    if (after == 0.0 || before == after) discard;
    gl_FragDepth = after;
}
