package me.seedexplorer.addon.mixin;

import me.seedexplorer.addon.enchant.EnchantingPlayerRng;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import com.mojang.authlib.GameProfile;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps the planner's Java LCG aligned with vanilla item drops. */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerRngMixin extends AbstractClientPlayer {
    protected LocalPlayerRngMixin(ClientLevel level, GameProfile profile) {
        super(level, profile);
    }

    @Inject(method = "drop", at = @At("HEAD"))
    private void seedExplorer$onDrop(boolean dropAll, CallbackInfoReturnable<ItemEntity> cir) {
        if (!getInventory().getSelectedItem().isEmpty()) {
            EnchantingPlayerRng.onItemDropped();
        }
    }

    /** Creative inventory drops use a separate packet path and never call drop(boolean). */
    @Inject(method = "handleCreativeModeItemDrop", at = @At("HEAD"))
    private void seedExplorer$onCreativeDrop(ItemStack stack, CallbackInfo ci) {
        if (stack != null && !stack.isEmpty()) {
            EnchantingPlayerRng.onItemDropped();
        }
    }
}
