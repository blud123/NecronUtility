package com.example.addon.mixin;

import com.example.addon.modules.ElytraBouncePlus;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

import static meteordevelopment.meteorclient.MeteorClient.mc;

@Mixin(Entity.class)
public abstract class ElytraEntityMixin {

    @Shadow protected UUID uuid;

    private ElytraBouncePlus efly;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void initEfly(CallbackInfo ci) {
        try {
            efly = Modules.get().get(ElytraBouncePlus.class);
        } catch (Exception ignored) {}
    }

    @Inject(method = "getPose", at = @At("HEAD"), cancellable = true)
    private void onGetPose(CallbackInfoReturnable<EntityPose> cir) {
        if (efly != null && efly.enabled() && mc.player != null && uuid.equals(mc.player.getUuid())) {
            cir.setReturnValue(EntityPose.GLIDING);
        }
    }

    @Inject(method = "isSprinting", at = @At("HEAD"), cancellable = true)
    private void onIsSprinting(CallbackInfoReturnable<Boolean> cir) {
        if (efly != null && efly.enabled() && mc.player != null && uuid.equals(mc.player.getUuid())) {
            cir.setReturnValue(true);
        }
    }

    // Yarn: Entity.pushAwayFrom(Entity)
    // require=0 so the build succeeds if the method name differs in this MC version
    @Inject(method = "pushAwayFrom(Lnet/minecraft/entity/Entity;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void onPush(Entity entity, CallbackInfo ci) {
        if (mc.player != null
                && uuid.equals(mc.player.getUuid())
                && efly != null && efly.enabled()
                && !entity.getUuid().equals(uuid)) {
            ci.cancel();
        }
    }
}
