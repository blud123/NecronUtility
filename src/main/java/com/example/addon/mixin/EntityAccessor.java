package com.example.addon.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Entity.class)
public interface EntityAccessor {

    @Accessor("onGround")
    boolean isOnGroundAccessor();

    @Accessor("yRot")
    void setYRot(float yaw);

    @Accessor("xRot")
    void setXRot(float pitch);

    @Accessor("yRotO")
    void setYRotO(float yaw);

    @Accessor("xRotO")
    void setXRotO(float pitch);
}
