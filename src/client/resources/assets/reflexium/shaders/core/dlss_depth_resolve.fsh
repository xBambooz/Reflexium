#version 330
#extension GL_ARB_separate_shader_objects : require

// Nearest-neighbour copy of the render-resolution scene depth (world plus hand, reverse-Z) into a
// display-resolution depth attachment, so post effects that sample the main target's depth after the
// upscaler still read the scene they would have seen natively. Nearest sampling never invents a depth
// that does not belong to a surface.

uniform sampler2D SourceDepth;

layout(std140) uniform ReflexiumDepthResolve {
    vec4 Sizes; // xy: source size in pixels, zw: destination size in pixels
    vec4 DepthEncoding;
};

layout(location = 0) in vec2 texCoord;

void main() {
    ivec2 destination = ivec2(gl_FragCoord.xy);
    vec2 uv = (vec2(destination) + 0.5) / Sizes.zw;
    ivec2 source = min(ivec2(uv * Sizes.xy), ivec2(Sizes.xy) - ivec2(1));
    float depth = texelFetch(SourceDepth, source, 0).r;
    // Post effects retain Minecraft's original clip range; only the vendor input uses the extended one.
    gl_FragDepth = max(0.0, (depth - DepthEncoding.y) / DepthEncoding.x);
}
