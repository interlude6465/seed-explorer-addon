package me.seedexplorer.standalone.mixin;

import me.seedexplorer.standalone.StandaloneEnchantingOverlay;
import me.seedexplorer.standalone.StandalonePredictedChestOverlay;
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

@Mixin(AbstractContainerScreen.class)
public abstract class StandaloneContainerOverlayMixin {
    @Shadow protected int leftPos;
    @Shadow protected int topPos;
    @Shadow protected int imageWidth;
    @Shadow protected int imageHeight;

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void seedExplorer$overlay(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                      float partialTick, CallbackInfo callback) {
        StandalonePredictedChestOverlay.get().render(graphics, leftPos, topPos, imageWidth, imageHeight);
        if ((Object) this instanceof EnchantmentScreen screen) {
            StandaloneEnchantingOverlay.get().render(graphics, screen, leftPos, topPos, imageWidth, imageHeight);
        }
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void seedExplorer$click(MouseButtonEvent click, boolean doubled,
                                    CallbackInfoReturnable<Boolean> callback) {
        if ((Object) this instanceof EnchantmentScreen screen
            && StandaloneEnchantingOverlay.get().click(screen, click)) callback.setReturnValue(true);
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void seedExplorer$scroll(double mouseX, double mouseY, double horizontal, double vertical,
                                     CallbackInfoReturnable<Boolean> callback) {
        if ((Object) this instanceof EnchantmentScreen
            && StandaloneEnchantingOverlay.get().scroll(mouseX, mouseY, vertical)) callback.setReturnValue(true);
    }
}
