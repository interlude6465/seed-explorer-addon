package me.seedexplorer.addon.render;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.renderer.ExtendedRenderPipelineBuilder;

final class SeedExplorerRenderPipelines {
    private static final RenderPipeline.Snippet MESH_UNIFORMS = RenderPipeline.builder()
        .withUniform("MeshData", UniformType.UNIFORM_BUFFER)
        .buildSnippet();

    static final RenderPipeline WORLD_TEXTURED = new ExtendedRenderPipelineBuilder(MESH_UNIFORMS)
        .withLocation(MeteorClient.identifier("pipeline/seed_explorer_world_textured"))
        .withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.TRIANGLES)
        .withVertexShader(MeteorClient.identifier("shaders/pos_tex_color.vert"))
        .withFragmentShader(MeteorClient.identifier("shaders/pos_tex_color.frag"))
        .withSampler("u_Texture")
        .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true))
        .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
        .withCull(false)
        .build();

    static final RenderPipeline WORLD_TEXTURED_TRANSLUCENT = new ExtendedRenderPipelineBuilder(MESH_UNIFORMS)
        .withLocation(MeteorClient.identifier("pipeline/seed_explorer_world_textured_translucent"))
        .withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.TRIANGLES)
        .withVertexShader(MeteorClient.identifier("shaders/pos_tex_color.vert"))
        .withFragmentShader(MeteorClient.identifier("shaders/pos_tex_color.frag"))
        .withSampler("u_Texture")
        .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
        .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
        .withCull(false)
        .build();

    private SeedExplorerRenderPipelines() {
    }
}
