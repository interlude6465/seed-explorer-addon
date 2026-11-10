package me.seedexplorer.standalone.mixin;

import me.seedexplorer.standalone.StandaloneAncillaryRuntime;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.EnchantmentScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.EnchantmentMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EnchantmentScreen.class)
public abstract class StandaloneEnchantmentRequestMixin extends AbstractContainerScreen<EnchantmentMenu> {
    protected StandaloneEnchantmentRequestMixin(EnchantmentMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Inject(method = "mouseClicked", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;handleInventoryButtonClick(II)V"))
    private void seedExplorer$request(CallbackInfoReturnable<Boolean> callback) {
        StandaloneAncillaryRuntime runtime = StandaloneAncillaryRuntime.get();
        if (runtime != null) runtime.enchantingRng().enchantmentRequested();
    }
}
