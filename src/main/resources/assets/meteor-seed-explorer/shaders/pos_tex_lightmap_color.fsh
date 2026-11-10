#version 330 core

out vec4 color;

uniform sampler2D u_Texture;
uniform sampler2D u_Lightmap;

in vec2 v_TexCoord;
in vec2 v_LightCoord;
in vec4 v_Color;

void main() {
    color = texture(u_Texture, v_TexCoord)
        * texture(u_Lightmap, clamp(v_LightCoord, vec2(0.0), vec2(1.0)))
        * v_Color;
}
