package me.seedexplorer.addon.mixin;

import me.seedexplorer.addon.enchant.EnchantingPlayerRng;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.EnchantmentScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.EnchantmentMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observes the vanilla enchanting-button request for player-RNG recovery. */
@Mixin(EnchantmentScreen.class)
public abstract class EnchantmentScreenRngMixin extends AbstractContainerScreen<EnchantmentMenu> {
    protected EnchantmentScreenRngMixin(EnchantmentMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Inject(method = "mouseClicked", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;handleInventoryButtonClick(II)V"))
    private void seedExplorer$onEnchantmentRequested(CallbackInfoReturnable<Boolean> cir) {
        EnchantingPlayerRng.onEnchantmentRequested();
    }
}
