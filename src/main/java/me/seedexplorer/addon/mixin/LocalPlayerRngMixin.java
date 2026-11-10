package me.seedexplorer.addon.mixin;

import me.seedexplorer.addon.enchant.EnchantingPlayerRng;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps the planner's Java LCG aligned with vanilla item drops. */
@Mixin(MultiPlayerGameMode.class)
public abstract class LocalPlayerRngMixin {
    @Inject(method = "dropItem", at = @At("HEAD"))
    private void seedExplorer$onDrop(LocalPlayer player, boolean dropAll, CallbackInfo ci) {
        if (player != null && !player.getInventory().getSelectedItem().isEmpty()) {
            EnchantingPlayerRng.onItemDropped();
        }
    }

    /** Creative inventory drops use a separate packet path and never call dropItem. */
    @Inject(method = "handleCreativeModeItemDrop", at = @At("HEAD"))
    private void seedExplorer$onCreativeDrop(ItemStack stack, CallbackInfo ci) {
        if (stack != null && !stack.isEmpty()) {
            EnchantingPlayerRng.onItemDropped();
        }
    }
}
