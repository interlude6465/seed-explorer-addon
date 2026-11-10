package me.seedexplorer.standalone.mixin;

import me.seedexplorer.standalone.StandaloneOreEspRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.gizmos.Gizmos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds ore boxes to Minecraft's native per-frame gizmo collector. */
@Mixin(LevelRenderer.class)
public abstract class StandaloneOreEspLevelRendererMixin {
    @Inject(method = "submitFeatures", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/LevelRenderer;finalizeGizmoCollection()V"))
    private void seedexplorer$submitOreEsp(LevelRenderState levelRenderState,
                                           SubmitNodeCollector output,
                                           boolean renderBlockOutline,
                                           CallbackInfo info) {
        LevelRenderer renderer = (LevelRenderer) (Object) this;
        try (Gizmos.TemporaryCollection ignored = renderer.collectPerFrameRenderThreadGizmos()) {
            StandaloneOreEspRenderer.renderGizmos();
        }
    }
}
