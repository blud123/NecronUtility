package com.example.addon.modules.movement;

import java.util.concurrent.atomic.AtomicInteger;
import com.example.addon.DWAddons;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.*;
import net.minecraft.network.packet.s2c.play.*;
import net.minecraft.util.math.Vec3d;


public class PacketTracker extends Module {

    // ── Settings ─────────────────────────────────────────────────────────────

    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Double> rubberbandThreshold = sg.add(new DoubleSetting.Builder()
        .name("rubberband-threshold")
        .description("Minimum distance (blocks) from a position packet to be flagged as a rubberband.")
        .defaultValue(0.5)
        .min(0.0).max(20.0)
        .sliderRange(0.1, 5.0)
        .build());

    private final Setting<Integer> oversendLimit = sg.add(new IntSetting.Builder()
        .name("oversend-limit")
        .description("Move packets/sec above this value trigger an oversend warning.")
        .defaultValue(20)
        .min(1).max(100)
        .sliderRange(10, 40)
        .build());

    private final Setting<Double> chunkStallSpeedThreshold = sg.add(new DoubleSetting.Builder()
        .name("chunk-stall-speed")
        .description("Horizontal speed (blocks/tick) above which a chunk-rate drop is considered a stall.")
        .defaultValue(0.5)
        .min(0.0).max(5.0)
        .sliderRange(0.1, 2.0)
        .build());

    // ── Public metrics (read by PacketTrackerHud) ─────────────────────────────

    public static volatile int movePacketsPerSec   = 0;
    public static volatile int chunksPerSec        = 0;
    public static volatile int positionOverrides   = 0;

    // ── Internal accumulators ─────────────────────────────────────────────────

    private int movePacketAccum  = 0;
    private int chunkAccum       = 0;
    private int tickCounter      = 0;

    // State for stall detection
    private int lastChunksPerSec = 0;

    public PacketTracker() {
        super(DWAddons.CATEGORY, "packet-tracker",
            "Monitors S2C/C2S packets to diagnose high-speed movement drops and anti-cheat corrections.");
    }

    @Override
    public void onActivate() {
        movePacketAccum = 0;
        chunkAccum      = 0;
        tickCounter     = 0;
        movePacketsPerSec  = 0;
        chunksPerSec       = 0;
        positionOverrides  = 0;
    }

    // ── Tick: flush per-second counters every 20 ticks ────────────────────────

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        tickCounter++;
        if (tickCounter < 20) return;
        tickCounter = 0;

        movePacketsPerSec = movePacketAccum;
        lastChunksPerSec  = chunksPerSec;
        chunksPerSec      = chunkAccum;
        movePacketAccum   = 0;
        chunkAccum        = 0;

        // Oversend warning: timer module pushes >20 move packets/sec
        if (movePacketsPerSec > oversendLimit.get()) {
            boolean timerActive = Modules.get().isActive(
                meteordevelopment.meteorclient.systems.modules.world.Timer.class);
            if (timerActive) {
                info("Client over-sending movement packets: %d/sec (Timer module may not be throttling properly)",
                    movePacketsPerSec);
            }
        }
    }

    // ── Incoming packets ──────────────────────────────────────────────────────

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        // 1. Rubberband / position override
        if (event.packet instanceof PlayerPositionLookS2CPacket pkt) {
            handlePositionPacket(pkt);
            return;
        }

        // 2. Chunk delivery counter
        if (event.packet instanceof ChunkDataS2CPacket) {
            chunkAccum++;
        }
    }

    private void handlePositionPacket(PlayerPositionLookS2CPacket pkt) {
        positionOverrides++;

        if (mc.player == null) return;

        Vec3d serverPos = pkt.change().position();
        Vec3d clientPos = mc.player.getPos();
        double distance = serverPos.distanceTo(clientPos);

        if (distance >= rubberbandThreshold.get()) {
            info("Rubberband / Anti-Cheat Rejection — server forced (%.2f, %.2f, %.2f), " +
                 "client was at (%.2f, %.2f, %.2f), delta=%.2f blocks",
                serverPos.x, serverPos.y, serverPos.z,
                clientPos.x, clientPos.y, clientPos.z,
                distance);
        }

        // Stall correlation: was this override preceded by a chunk-rate collapse?
        double horizontalSpeed = Math.sqrt(
            mc.player.getVelocity().x * mc.player.getVelocity().x +
            mc.player.getVelocity().z * mc.player.getVelocity().z);

        if (horizontalSpeed >= chunkStallSpeedThreshold.get() && lastChunksPerSec <= 1) {
            info("Velocity killed due to Chunk Generation Stall " +
                 "(chunks/sec last interval=%d, speed=%.2f b/t)",
                lastChunksPerSec, horizontalSpeed);
        }
    }

    // ── Outgoing packets ──────────────────────────────────────────────────────

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        if (event.packet instanceof PlayerMoveC2SPacket) {
            movePacketAccum++;
        }
    }

    // ── HUD helper ────────────────────────────────────────────────────────────

    public static boolean isTracking() {
        return Modules.get().isActive(PacketTracker.class);
    }

    public static String hudText() {
        return String.format("Moves/s: %d  |  Chunks/s: %d  |  Overrides: %d",
            movePacketsPerSec, chunksPerSec, positionOverrides);
    }

    @Override
    public String getInfoString() {
        return String.format("%d/%d/%d", movePacketsPerSec, chunksPerSec, positionOverrides);
    }
}
