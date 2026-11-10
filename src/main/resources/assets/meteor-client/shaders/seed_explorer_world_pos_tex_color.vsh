#version 330
#extension GL_ARB_separate_shader_objects : require

layout (location = 0) in vec3 Position;
layout (location = 1) in vec2 UV0;
layout (location = 2) in vec4 Color;

layout (std140) uniform MeshData {
    mat4 u_Proj;
    mat4 u_ModelView;
};

layout (location = 0) out vec2 v_TexCoord;
layout (location = 1) out vec4 v_Color;

void main() {
    gl_Position = u_Proj * u_ModelView * vec4(Position, 1.0);
    v_TexCoord = UV0;
    v_Color = Color;
}
