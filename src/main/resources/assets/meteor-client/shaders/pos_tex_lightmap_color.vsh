#version 330 core

layout (location = 0) in vec3 Position;
layout (location = 1) in vec2 UV0;
layout (location = 2) in ivec2 UV2;
layout (location = 3) in vec4 Color;

layout (std140) uniform MeshData {
    mat4 u_Proj;
    mat4 u_ModelView;
};

out vec2 v_TexCoord;
out vec2 v_LightCoord;
out vec4 v_Color;

void main() {
    gl_Position = u_Proj * u_ModelView * vec4(Position, 1.0);
    v_TexCoord = UV0;
    v_LightCoord = vec2(UV2) / 256.0 + vec2(0.5 / 16.0);
    v_Color = Color;
}
