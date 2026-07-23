/*
 * This file is part of the Meteor Seed Explorer Addon distribution (https://github.com/SeedExplorer/meteor-seed-explorer).
 * Copyright (c) SeedExplorer Team.
 */

package me.seedexplorer.addon.mixin;

import me.seedexplorer.addon.commands.PredictedMineChatBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ChatScreen.class, priority = 1500)
public abstract class ChatScreenPredictedMineMixin {
    @Inject(method = "handleChatInput", at = @At("HEAD"), cancellable = true)
    private void seedExplorer$onHandleChatInput(String message, boolean addToRecent, CallbackInfo ci) {
        if (!PredictedMineChatBridge.isSeedExplorerMineCommand(message)) return;

        Minecraft minecraft = Minecraft.getInstance();
        if (addToRecent && minecraft != null && minecraft.gui != null) {
            minecraft.gui.getChat().addRecentChat(message);
        }

        PredictedMineChatBridge.handleBaritoneStyleMine(message);
        ci.cancel();
    }
}
