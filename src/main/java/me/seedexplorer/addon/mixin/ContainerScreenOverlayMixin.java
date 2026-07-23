package me.seedexplorer.addon.mixin;

import me.seedexplorer.addon.loot.PredictedChestOverlay;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the predicted-contents panel after the container screen finishes rendering, so it sits on top
 * of the chest GUI. Rendering through Meteor's Render2DEvent draws at the HUD layer (before screens
 * paint), which left the panel behind the chest GUI with its left half cut off.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class ContainerScreenOverlayMixin {
    @Shadow
    protected int leftPos;
    @Shadow
    protected int topPos;
    @Shadow
    protected int imageWidth;
    @Shadow
    protected int imageHeight;

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void seedExplorer$renderOverlay(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        PredictedChestOverlay.get().renderOnScreen(
            graphics, graphics.guiWidth(), graphics.guiHeight(),
            leftPos, topPos, imageWidth, imageHeight);
    }
}
