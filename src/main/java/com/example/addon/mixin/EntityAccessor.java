package com.example.addon.mixin;

import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Entity.class)
public interface EntityAccessor {

    @Accessor("onGround")
    boolean isOnGroundAccessor();

    @Invoker("setYaw")
    void invokeSetYRot(float yaw);

    @Invoker("setPitch")
    void invokeSetXRot(float pitch);
}
