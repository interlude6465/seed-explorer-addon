package me.seedexplorer.addon.mixin;

import me.seedexplorer.addon.enchant.EnchantingTableOverlay;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.EnchantmentScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.EnchantmentMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Positions the vanilla table beside the predictor as a single centered group. */
@Mixin(EnchantmentScreen.class)
public abstract class EnchantmentScreenLayoutMixin extends AbstractContainerScreen<EnchantmentMenu> {
    protected EnchantmentScreenLayoutMixin(EnchantmentMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void seedExplorer$positionTable(CallbackInfo ci) {
        leftPos += EnchantingTableOverlay.guiShift(width, imageWidth);
    }

    /* EnchantmentScreen computes its offer hit boxes from the centered x rather than leftPos. */
    @ModifyVariable(method = "mouseClicked", at = @At(value = "STORE"), index = 3)
    private int seedExplorer$shiftOfferHitbox(int centeredX) {
        return centeredX + EnchantingTableOverlay.guiShift(width, imageWidth);
    }

    /* The table background and book use the same centered x calculation. */
    @ModifyVariable(method = "extractBackground", at = @At(value = "STORE"), index = 5)
    private int seedExplorer$shiftBackground(int centeredX) {
        return centeredX + EnchantingTableOverlay.guiShift(width, imageWidth);
    }
}
