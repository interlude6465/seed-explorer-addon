package me.seedexplorer.standalone.mixin;

import me.seedexplorer.standalone.StandaloneAncillaryRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ChatScreen.class, priority = 1500)
public abstract class StandaloneChatScreenMixin {
    @Inject(method = "handleChatInput", at = @At("HEAD"), cancellable = true)
    private void seedExplorer$command(String message, boolean addToRecent, CallbackInfo callback) {
        StandaloneAncillaryRuntime runtime = StandaloneAncillaryRuntime.get();
        if (runtime == null || !runtime.handleChat(message)) return;
        Minecraft client = Minecraft.getInstance();
        if (addToRecent && client.gui != null) client.gui.hud.getChat().addRecentChat(message);
        callback.cancel();
    }
}
