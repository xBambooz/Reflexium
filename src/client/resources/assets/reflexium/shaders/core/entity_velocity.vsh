#version 330
#extension GL_ARB_separate_shader_objects : require

// Object velocity pass (see ObjectMotion): each vertex of a moving entity quad carries its position in
// this frame (relative to this frame's camera) and in the previous frame (relative to the previous
// frame's camera). Clip positions are passed on unnormalized so the fragment shader can divide per pixel.

layout(location = 0) in vec3 Position;
layout(location = 1) in vec3 PreviousPosition;
layout(location = 2) in float CullFace;

layout(std140) uniform ReflexiumObjectMotion {
    mat4 CurrentJitteredViewProjection; // rasterization, must match the level pass so depth matches
    mat4 CurrentViewProjection;         // same without jitter
    mat4 PreviousViewProjection;        // previous frame, unjittered
    vec4 TargetSize;                    // xy: render size in pixels
};

layout(location = 0) out vec4 currentClip;
layout(location = 1) out vec4 previousClip;
layout(location = 2) flat out float cullFace;

void main() {
    vec4 position = vec4(Position, 1.0);
    gl_Position = CurrentJitteredViewProjection * position;
    currentClip = CurrentViewProjection * position;
    previousClip = PreviousViewProjection * vec4(PreviousPosition, 1.0);
    cullFace = CullFace;
}
