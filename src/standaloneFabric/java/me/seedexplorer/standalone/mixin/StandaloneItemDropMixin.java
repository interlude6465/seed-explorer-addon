package me.seedexplorer.standalone.mixin;

import me.seedexplorer.standalone.StandaloneAncillaryRuntime;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiPlayerGameMode.class)
public abstract class StandaloneItemDropMixin {
    @Inject(method = "dropItem", at = @At("HEAD"))
    private void seedExplorer$drop(LocalPlayer player, boolean dropAll, CallbackInfo callback) {
        StandaloneAncillaryRuntime runtime = StandaloneAncillaryRuntime.get();
        if (runtime != null && player != null && !player.getInventory().getSelectedItem().isEmpty()) {
            runtime.enchantingRng().itemDropped();
        }
    }

    @Inject(method = "handleCreativeModeItemDrop", at = @At("HEAD"))
    private void seedExplorer$creativeDrop(ItemStack stack, CallbackInfo callback) {
        StandaloneAncillaryRuntime runtime = StandaloneAncillaryRuntime.get();
        if (runtime != null && stack != null && !stack.isEmpty()) runtime.enchantingRng().itemDropped();
    }
}
