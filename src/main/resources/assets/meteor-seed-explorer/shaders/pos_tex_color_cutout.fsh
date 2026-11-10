#version 330
#extension GL_ARB_separate_shader_objects : require

layout (location = 0) out vec4 color;

uniform sampler2D u_Texture;

layout (location = 0) in vec2 v_TexCoord;
layout (location = 1) in vec4 v_Color;

void main() {
    vec4 sampled = texture(u_Texture, v_TexCoord) * v_Color;
    if (sampled.a < 0.1) discard;
    color = sampled;
}
