#version 330
#extension GL_ARB_separate_shader_objects : require

// Robust contrast adaptive sharpening after DLSS, ported from AMD FidelityFX RCAS (ffx_fsr1.h, MIT).
// DLSS 2.5.1 and later ship without built-in sharpening; this is the same filter FSR applies through its
// own sharpness setting, so one slider means the same thing for both upscalers. The lobe is limited so
// no output leaves the local min/max of its cross neighbourhood, and flat noise is attenuated.

uniform sampler2D Source;

layout(std140) uniform ReflexiumSharpen {
    vec4 Params; // x: RCAS sharpness as exp2(-stops), 0 is no sharpening
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

const float RCAS_LIMIT = 0.25 - 1.0 / 16.0;

float luma2(vec3 c) {
    return c.b * 0.5 + (c.r * 0.5 + c.g);
}

void main() {
    ivec2 limit = textureSize(Source, 0) - ivec2(1);
    ivec2 p = ivec2(gl_FragCoord.xy);
    //    b
    //  d e f
    //    h
    vec4 eSample = texelFetch(Source, p, 0);
    vec3 b = texelFetch(Source, clamp(p + ivec2(0, -1), ivec2(0), limit), 0).rgb;
    vec3 d = texelFetch(Source, clamp(p + ivec2(-1, 0), ivec2(0), limit), 0).rgb;
    vec3 e = eSample.rgb;
    vec3 f = texelFetch(Source, clamp(p + ivec2(1, 0), ivec2(0), limit), 0).rgb;
    vec3 h = texelFetch(Source, clamp(p + ivec2(0, 1), ivec2(0), limit), 0).rgb;

    float bL = luma2(b);
    float dL = luma2(d);
    float eL = luma2(e);
    float fL = luma2(f);
    float hL = luma2(h);

    // Noise detection: high-pass relative to the local luma range.
    float nz = 0.25 * (bL + dL + fL + hL) - eL;
    float range = max(max(max(bL, dL), max(eL, fL)), hL) - min(min(min(bL, dL), min(eL, fL)), hL);
    nz = clamp(abs(nz) / max(range, 1.0 / 4096.0), 0.0, 1.0);
    nz = -0.5 * nz + 1.0;

    vec3 mn4 = min(min(b, d), min(f, h));
    vec3 mx4 = max(max(b, d), max(f, h));
    // Largest negative lobe that keeps the result inside [min, max] of the ring.
    vec3 hitMin = mn4 / max(4.0 * mx4, vec3(1.0 / 4096.0));
    vec3 hitMax = (1.0 - mx4) / (4.0 * mn4 - 4.0);
    vec3 lobeRGB = max(-hitMin, hitMax);
    float lobe = max(-RCAS_LIMIT, min(max(max(lobeRGB.r, lobeRGB.g), lobeRGB.b), 0.0)) * Params.x;
    lobe *= nz;

    float rcpL = 1.0 / (4.0 * lobe + 1.0);
    vec3 color = (lobe * (b + d + f + h) + e) * rcpL;
    fragColor = vec4(clamp(color, 0.0, 1.0), eSample.a);
}
