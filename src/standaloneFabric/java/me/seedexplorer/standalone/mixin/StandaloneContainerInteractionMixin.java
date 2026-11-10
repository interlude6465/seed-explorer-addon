package me.seedexplorer.standalone.mixin;

import me.seedexplorer.standalone.StandaloneAncillaryRuntime;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public abstract class StandaloneContainerInteractionMixin {
    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void seedExplorer$blockContainer(LocalPlayer player, InteractionHand hand,
                                             BlockHitResult hit, CallbackInfoReturnable<?> callback) {
        StandaloneAncillaryRuntime runtime = StandaloneAncillaryRuntime.get();
        if (runtime != null && hit != null) runtime.onContainerInteraction(hit.getBlockPos());
    }

    @Inject(method = "interact", at = @At("HEAD"))
    private void seedExplorer$entityContainer(Player player, Entity entity, EntityHitResult hit,
                                              InteractionHand hand, CallbackInfoReturnable<?> callback) {
        StandaloneAncillaryRuntime runtime = StandaloneAncillaryRuntime.get();
        if (runtime != null && entity != null) runtime.onContainerInteraction(entity.blockPosition());
    }
}
