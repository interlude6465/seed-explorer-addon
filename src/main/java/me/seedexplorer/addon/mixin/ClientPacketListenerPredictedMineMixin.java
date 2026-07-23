/*
 * This file is part of the Meteor Seed Explorer Addon distribution (https://github.com/SeedExplorer/meteor-seed-explorer).
 * Copyright (c) SeedExplorer Team.
 */

package me.seedexplorer.addon.mixin;

import me.seedexplorer.addon.commands.PredictedMineChatBridge;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ClientPacketListener.class, priority = 1200)
public class ClientPacketListenerPredictedMineMixin {
    @Inject(method = "sendChat", at = @At("HEAD"), cancellable = true)
    private void seedExplorer$onSendChat(String message, CallbackInfo ci) {
        if (PredictedMineChatBridge.handleBaritoneStyleMine(message)) ci.cancel();
    }
}
