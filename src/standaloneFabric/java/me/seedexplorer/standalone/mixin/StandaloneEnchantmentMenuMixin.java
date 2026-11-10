package me.seedexplorer.standalone.mixin;

import me.seedexplorer.standalone.StandaloneAncillaryRuntime;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerMenu.class)
public abstract class StandaloneEnchantmentMenuMixin {
    @Inject(method = "setData", at = @At("TAIL"))
    private void seedExplorer$seedUpdate(int id, int value, CallbackInfo callback) {
        StandaloneAncillaryRuntime runtime = StandaloneAncillaryRuntime.get();
        if (runtime != null && (Object) this instanceof EnchantmentMenu && id == 3) {
            runtime.enchantingRng().serverXpSeedUpdate(value);
        }
    }
}
