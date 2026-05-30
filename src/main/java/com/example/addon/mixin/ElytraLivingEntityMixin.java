package com.example.addon.mixin;

import com.example.addon.modules.ElytraBouncePlus;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static meteordevelopment.meteorclient.MeteorClient.mc;

@Mixin(LivingEntity.class)
public abstract class ElytraLivingEntityMixin {

    // Mojang: noJumpDelay — Yarn: jumpingCooldown
    // If this field name causes a compile error, check the Mojang-mapped LivingEntity source.
    @Shadow private int noJumpDelay;

    private ElytraBouncePlus efly;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void initEfly(CallbackInfo ci) {
        try {
            efly = Modules.get().get(ElytraBouncePlus.class);
        } catch (Exception ignored) {}
    }

    private boolean isLocalPlayer() {
        return mc.player != null && mc.player.getUUID().equals(((Entity) (Object) this).getUUID());
    }

    // Mojang: aiStep — Yarn: tickMovement
    @Inject(method = "aiStep", at = @At("HEAD"))
    private void onAiStep(CallbackInfo ci) {
        if (isLocalPlayer() && efly != null && efly.enabled()) {
            this.noJumpDelay = 0;
        }
    }

    // Mojang: isFallFlying — Yarn: isGliding
    // Critical: forces client physics into elytra aerodynamics every tick, preventing
    // ground friction from bleeding horizontal speed between bounces.
    @Inject(method = "isFallFlying", at = @At("HEAD"), cancellable = true)
    private void onIsFallFlying(CallbackInfoReturnable<Boolean> cir) {
        if (isLocalPlayer() && efly != null && efly.enabled()) {
            cir.setReturnValue(true);
        }
    }
}
