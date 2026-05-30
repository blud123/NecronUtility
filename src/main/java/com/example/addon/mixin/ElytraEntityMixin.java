package com.example.addon.mixin;

import com.example.addon.modules.ElytraBouncePlus;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
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
    private void onGetPose(CallbackInfoReturnable<Pose> cir) {
        if (efly != null && efly.enabled() && mc.player != null && uuid.equals(mc.player.getUUID())) {
            cir.setReturnValue(Pose.FALL_FLYING);
        }
    }

    @Inject(method = "isSprinting", at = @At("HEAD"), cancellable = true)
    private void onIsSprinting(CallbackInfoReturnable<Boolean> cir) {
        if (efly != null && efly.enabled() && mc.player != null && uuid.equals(mc.player.getUUID())) {
            cir.setReturnValue(true);
        }
    }

    // Mojang: Entity.push(Entity) — Yarn: pushAwayFrom(Entity)
    // require=0 so the build succeeds if the method name differs in this MC version
    @Inject(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void onPush(Entity entity, CallbackInfo ci) {
        if (mc.player != null
                && uuid.equals(mc.player.getUUID())
                && efly != null && efly.enabled()
                && !entity.getUUID().equals(uuid)) {
            ci.cancel();
        }
    }
}
