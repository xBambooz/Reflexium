#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D TextLayer;
layout(location = 0) out vec4 fragColor;

#ifdef PACK_LEVELS
uniform sampler2D SceneColor;
vec3 linearRgb(vec3 c) {
    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), greaterThan(c, vec3(0.04045)));
}
vec3 displayRgb(vec3 c) {
    return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, greaterThan(c, vec3(0.0031308)));
}
#endif

void main() {
    fragColor = texelFetch(TextLayer, ivec2(gl_FragCoord.xy), 0);
#ifdef PACK_LEVELS
    if (fragColor.a <= 0.0) return;
    ivec2 size = textureSize(SceneColor, 0);
    float radius = round(8.0 * float(size.y) / 1080.0);
    vec3 local = vec3(0.0);
    // Sixteen fixed box-stratified taps. Decode each sample before averaging.
    for (int y = 0; y < 4; ++y) for (int x = 0; x < 4; ++x) {
        vec2 offset = (vec2(x, y) * 0.5 - 0.75) * radius;
        ivec2 p = clamp(ivec2(gl_FragCoord.xy + offset), ivec2(0), size - 1);
        local += linearRgb(texelFetch(SceneColor, p, 0).rgb);
    }
    float Y = clamp(dot(local / 16.0, vec3(0.2126, 0.7152, 0.0722)), 0.0, 0.3464359241);
    float lift = 0.384083 * Y - 0.554335 * Y * Y;
    vec3 T = linearRgb(clamp(fragColor.rgb / fragColor.a, 0.0, 1.0));
    fragColor.rgb = displayRgb(clamp((1.0 - T) * lift + 0.707341 * T, 0.0, 1.0)) * fragColor.a;
#endif
}
