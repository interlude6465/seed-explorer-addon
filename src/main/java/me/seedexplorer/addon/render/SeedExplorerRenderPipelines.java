package me.seedexplorer.addon.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.*;
import net.minecraft.resources.Identifier;

final class SeedExplorerRenderPipelines {
    private static final RenderPipeline.Snippet MESH_UNIFORMS = RenderPipeline.builder()
        .withBindGroupLayout(BindGroupLayout.builder()
            .withUniform("MeshData", UniformType.UNIFORM_BUFFER)
            .build())
        .buildSnippet();

    static final RenderPipeline WORLD_TEXTURED = RenderPipeline.builder(MESH_UNIFORMS)
        .withLocation(id("pipeline/seed_explorer_world_textured"))
        .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR).withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .withVertexShader(id("seed_explorer_world_pos_tex_color"))
        .withFragmentShader(id("pos_tex_color"))
        .withBindGroupLayout(BindGroupLayout.builder()
            .withUniform("u_Texture", UniformType.COMBINED_IMAGE_SAMPLER)
            .build())
        .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true))
        .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
        .withCull(false)
        .build();

    static final RenderPipeline WORLD_TEXTURED_TRANSLUCENT = RenderPipeline.builder(MESH_UNIFORMS)
        .withLocation(id("pipeline/seed_explorer_world_textured_translucent"))
        .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR).withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .withVertexShader(id("seed_explorer_world_pos_tex_color"))
        .withFragmentShader(id("pos_tex_color"))
        .withBindGroupLayout(BindGroupLayout.builder()
            .withUniform("u_Texture", UniformType.COMBINED_IMAGE_SAMPLER)
            .build())
        .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
        .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
        .withCull(false)
        .build();

    /** Same vertex layout, but samples Minecraft's live block atlas directly. */
    static final RenderPipeline WORLD_VANILLA_TEXTURED = RenderPipeline.builder(MESH_UNIFORMS)
        .withLocation(id("pipeline/seed_explorer_vanilla_textured"))
        .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR).withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .withVertexShader(id("seed_explorer_world_pos_tex_color"))
        .withFragmentShader(id("pos_tex_color"))
        .withBindGroupLayout(BindGroupLayout.builder()
            .withUniform("u_Texture", UniformType.COMBINED_IMAGE_SAMPLER)
            .build())
        .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true))
        .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
        .withCull(false)
        .build();

    /** Vanilla block-model vertex layout with packed UV2 light coordinates. */
    static final RenderPipeline WORLD_VANILLA_LIGHTMAP = RenderPipeline.builder(MESH_UNIFORMS)
        .withLocation(id("pipeline/seed_explorer_vanilla_lightmap"))
        .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR).withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .withVertexShader(id("pos_tex_lightmap_color"))
        .withFragmentShader(id("pos_tex_lightmap_color"))
        .withBindGroupLayout(BindGroupLayout.builder()
            .withUniform("u_Texture", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("u_Lightmap", UniformType.COMBINED_IMAGE_SAMPLER)
            .build())
        .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true))
        .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
        .withCull(false)
        .build();

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("meteor-seed-explorer", path);
    }

    private SeedExplorerRenderPipelines() {
    }
}
