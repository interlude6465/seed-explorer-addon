package me.seedexplorer.addon.mixin;

import me.seedexplorer.addon.render.MapLoadingMode;
import meteordevelopment.meteorclient.utils.render.postprocess.PostProcessShaders;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Leaves only the GUI progress pass active during dedicated map generation. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMapLoadingMixin {
    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
        at = @At("HEAD"), cancellable = true)
    private void seedexplorer$skipWorldPass(DeltaTracker deltaTracker, CallbackInfo info) {
        if (!MapLoadingMode.isActive()) return;

        // Meteor's entity outline provider normally ends its BufferBuilder from
        // LevelRenderer's main pass. Cancelling the entire world method skips that
        // point, allowing an old outline batch to grow across loading frames until
        // Minecraft throws its 16,777,215-vertex limit. Flush before cancellation
        // so the blank loading screen remains compatible with Meteor's renderer.
        PostProcessShaders.submitEntityVertices();
        info.cancel();
    }
}
