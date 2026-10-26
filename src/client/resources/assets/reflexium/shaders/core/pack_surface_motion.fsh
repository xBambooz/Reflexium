#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D BaseMotion;
uniform sampler2D BaseDepth;
uniform sampler2D HandDepth;
uniform sampler2D OpaqueSurface;
uniform sampler2D TransparentSurface;
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 velocity;
layout(location = 1) out vec4 composition;
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    float depth = texelFetch(BaseDepth, p, 0).r;
    velocity = texelFetch(BaseMotion, p, 0);
    composition = vec4(0.0);
    gl_FragDepth = depth;
    if (texelFetch(HandDepth, p, 0).r != 0.0) return;
    vec4 opaque = texelFetch(OpaqueSurface, p, 0);
    // Surface depth is stored in binary16, with 10 fraction bits and subnormal steps of 2^-24.
    // Comparing it to D32 at float precision rejects the very surface that produced the sample.
    float tolerance = depth * 0.0005 + 6e-8;
    if (opaque.a > 0.99999 && abs(opaque.z - depth) <= tolerance) velocity.xy = opaque.xy;
    vec4 layer = texelFetch(TransparentSurface, p, 0);
    if (layer.a <= 0.0 || layer.z + tolerance < depth) return;
    // Only a fully opaque contribution has one unambiguous visible velocity/depth. Blended layers retain
    // background motion and supply FSR's actual reactive/composition inputs. DLSS has no equivalent mask API.
    if (layer.a > 0.99999) {
        velocity.xy = layer.xy;
        gl_FragDepth = layer.z;
    } else {
        // Pack scene depth already contains this blended layer, so the base vector reprojects the water/glass
        // plane, not the background. Behind it, the pack's own opaque surface (reverse depth: smaller is
        // farther) carries the visible background's camera and vertex motion.
        if (opaque.a > 0.99999 && opaque.z < layer.z - tolerance) {
            velocity.xy = opaque.xy;
            gl_FragDepth = opaque.z;
        }
        composition = vec4(layer.a);
    }
}
