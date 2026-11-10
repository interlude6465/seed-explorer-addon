package me.seedexplorer.standalone.mixin;

import me.seedexplorer.standalone.StandaloneAncillaryRuntime;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ClientPacketListener.class, priority = 1200)
public abstract class StandaloneClientPacketListenerMixin {
    @Inject(method = "sendChat", at = @At("HEAD"), cancellable = true)
    private void seedExplorer$command(String message, CallbackInfo callback) {
        StandaloneAncillaryRuntime runtime = StandaloneAncillaryRuntime.get();
        if (runtime != null && runtime.handleChat(message)) callback.cancel();
    }
}
