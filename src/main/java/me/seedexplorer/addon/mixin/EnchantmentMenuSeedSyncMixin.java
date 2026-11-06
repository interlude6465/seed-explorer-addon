package me.seedexplorer.addon.mixin;

import me.seedexplorer.addon.enchant.EnchantingPlayerRng;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Receives the server-authoritative enchanting seed after a container-data update. */
@Mixin(AbstractContainerMenu.class)
public abstract class EnchantmentMenuSeedSyncMixin {
    @Inject(method = "setData", at = @At("TAIL"))
    private void seedExplorer$onServerData(int id, int value, CallbackInfo ci) {
        // EnchantmentMenu registers costs at data ids 0..2 and the hidden XP seed at id 3.
        if ((Object) this instanceof EnchantmentMenu && id == 3) {
            EnchantingPlayerRng.onServerXpSeedUpdate(value);
        }
    }
}
