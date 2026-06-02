package com.example.addon.modules.movement;

import com.example.addon.DWAddons;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;

/**
 * Shulker Y-axis exploit: floods upward position packets each tick and walks the client's
 * server-side Y up with them, launching the player into the sky. Optionally gated to only run
 * while standing on an open shulker box.
 */
public class VerticalYBoost extends Module {

    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Double> bps = sg.add(new DoubleSetting.Builder()
        .name("blocks-per-second")
        .description("Upward speed in blocks/sec.")
        .defaultValue(1000000)
        .min(1.0)
        .sliderMax(1000000000.0)
        .build());

    private final Setting<Integer> packetsPerTick = sg.add(new IntSetting.Builder()
        .name("packets-per-tick")
        .description("Position packets flooded upward each tick.")
        .defaultValue(10)
        .min(1).max(20)
        .sliderRange(1, 20)
        .build());

    private final Setting<Boolean> requireOpenShulker = sg.add(new BoolSetting.Builder()
        .name("require-open-shulker")
        .description("Only boost while standing on an open shulker box.")
        .defaultValue(true)
        .build());

    private double serverY;

    public VerticalYBoost() {
        super(DWAddons.CATEGORY, "vertical-y-boost",
            "Shulker Y-axis exploit: floods upward position packets and launches the client into the sky.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null) return;
        serverY = mc.player.getY();
    }

    @Override
    public void onDeactivate() {
        if (mc.player == null) return;
        mc.player.setNoGravity(false);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        if (requireOpenShulker.get() && !isOnOpenShulker()) return;

        mc.player.setNoGravity(true);

        double yPerTick   = bps.get() / 20.0;
        double yPerPacket = yPerTick / packetsPerTick.get();

        for (int i = 1; i <= packetsPerTick.get(); i++) {
            mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.Full(
                mc.player.getX(),
                serverY + yPerPacket * i,
                mc.player.getZ(),
                mc.player.getYaw(),
                mc.player.getPitch(),
                false,
                false
            ));
        }

        serverY += yPerTick;

        mc.player.setPosition(mc.player.getX(), serverY, mc.player.getZ());
        mc.player.setVelocity(
            mc.player.getVelocity().x,
            0.0,
            mc.player.getVelocity().z
        );
        mc.player.fallDistance = 0.0f;
    }

    private boolean isOnOpenShulker() {
        if (mc.player == null || mc.world == null) return false;
        BlockPos below = BlockPos.ofFloored(mc.player.getX(), mc.player.getY() - 0.1, mc.player.getZ());
        if (!(mc.world.getBlockState(below).getBlock() instanceof ShulkerBoxBlock)) return false;
        if (mc.world.getBlockEntity(below) instanceof ShulkerBoxBlockEntity shulker) {
            return shulker.getAnimationProgress(1f) > 0f;
        }
        return false;
    }
}
