package me.seedexplorer.addon.mixin;

import me.seedexplorer.addon.enchant.EnchantingTableOverlay;
import me.seedexplorer.addon.loot.PredictedChestOverlay;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.EnchantmentScreen;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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
        if ((Object) this instanceof EnchantmentScreen) {
            EnchantingTableOverlay.get().renderOnScreen(
                graphics, graphics.guiWidth(), graphics.guiHeight(),
                leftPos, topPos, imageWidth, imageHeight);
        }
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void seedExplorer$enchantPanelClick(MouseButtonEvent click, boolean doubled,
                                                CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof EnchantmentScreen screen
            && EnchantingTableOverlay.get().mouseClicked(screen, click)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void seedExplorer$enchantPanelScroll(double mouseX, double mouseY, double scrollX, double scrollY,
                                                 CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof EnchantmentScreen screen
            && EnchantingTableOverlay.get().mouseScrolled(screen, mouseX, mouseY, scrollY)) {
            cir.setReturnValue(true);
        }
    }
}
