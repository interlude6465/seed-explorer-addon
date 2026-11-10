package me.seedexplorer.standalone.mixin;

import me.seedexplorer.standalone.StandaloneEnchantingOverlay;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.EnchantmentScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.EnchantmentMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EnchantmentScreen.class)
public abstract class StandaloneEnchantmentLayoutMixin extends AbstractContainerScreen<EnchantmentMenu> {
    protected StandaloneEnchantmentLayoutMixin(EnchantmentMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void seedExplorer$layout(CallbackInfo callback) {
        leftPos += StandaloneEnchantingOverlay.guiShift(width, imageWidth);
    }

    @ModifyVariable(method = "mouseClicked", at = @At(value = "STORE"), index = 3)
    private int seedExplorer$hitbox(int centeredX) {
        return centeredX + StandaloneEnchantingOverlay.guiShift(width, imageWidth);
    }

    @ModifyVariable(method = "extractBackground", at = @At(value = "STORE"), index = 5)
    private int seedExplorer$background(int centeredX) {
        return centeredX + StandaloneEnchantingOverlay.guiShift(width, imageWidth);
    }
}
