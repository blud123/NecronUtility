package com.example.addon.mixin;

import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PlayerMoveC2SPacket.class)
public interface IServerboundMovePlayerPacket {
    @Accessor("x") double getX();
    @Accessor("y") double getY();
    @Accessor("z") double getZ();
    @Accessor("onGround") boolean getOnGround();
    @Accessor("x") void setX(double x);
    @Accessor("y") void setY(double y);
    @Accessor("z") void setZ(double z);
    @Accessor("onGround") void setOnGround(boolean onGround);
}
