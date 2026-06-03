package com.example.addon.modules.movement;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
    // Published so the HUD's oversend warn-colour follows the configurable limit, not a magic number.
    public static volatile int oversendThreshold   = 20;

    // ── Internal accumulators (incremented on the Netty thread, drained on the client thread) ──

    private final AtomicInteger movePacketAccum = new AtomicInteger();
    private final AtomicInteger chunkAccum      = new AtomicInteger();
    private final AtomicInteger overrideAccum   = new AtomicInteger();
    // Latest server-forced position, handed from the Netty thread to the tick loop.
    private final AtomicReference<Vec3d> pendingSetbackPos = new AtomicReference<>();

    private int tickCounter      = 0;

    // State for stall detection
    private int lastChunksPerSec = 0;

    // Previous-tick client state, so a set-back is compared against where we were before it landed.
    private Vec3d prevClientPos;
    private double prevHSpeed;

    public PacketTracker() {
        super(DWAddons.CATEGORY, "packet-tracker",
            "Monitors S2C/C2S packets to diagnose high-speed movement drops and anti-cheat corrections.");
    }

    @Override
    public void onActivate() {
        movePacketAccum.set(0);
        chunkAccum.set(0);
        overrideAccum.set(0);
        pendingSetbackPos.set(null);
        tickCounter        = 0;
        lastChunksPerSec   = 0;
        movePacketsPerSec  = 0;
        chunksPerSec       = 0;
        positionOverrides  = 0;
        oversendThreshold  = oversendLimit.get();
        prevClientPos      = mc.player != null ? mc.player.getPos() : null;
        prevHSpeed         = 0;
    }

    // ── Tick: flush per-second counters every 20 ticks ────────────────────────

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        // Publish the cumulative override count and handle the latest set-back here on the client
        // thread, comparing the server's forced position to where we were the previous tick (before
        // the correction landed) — both reads that used to happen unsafely on the Netty thread.
        positionOverrides += overrideAccum.getAndSet(0);
        Vec3d setbackPos = pendingSetbackPos.getAndSet(null);
        if (setbackPos != null && prevClientPos != null) {
            double distance = setbackPos.distanceTo(prevClientPos);
            if (distance >= rubberbandThreshold.get()) {
                info("Rubberband / Anti-Cheat Rejection — server forced (%.2f, %.2f, %.2f), " +
                     "client was at (%.2f, %.2f, %.2f), delta=%.2f blocks",
                    setbackPos.x, setbackPos.y, setbackPos.z,
                    prevClientPos.x, prevClientPos.y, prevClientPos.z, distance);
            }
            if (prevHSpeed >= chunkStallSpeedThreshold.get() && lastChunksPerSec <= 1) {
                info("Velocity killed due to Chunk Generation Stall " +
                     "(chunks/sec last interval=%d, speed=%.2f b/t)", lastChunksPerSec, prevHSpeed);
            }
        }

        tickCounter++;
        if (tickCounter >= 20) {
            tickCounter = 0;
            movePacketsPerSec = movePacketAccum.getAndSet(0);
            lastChunksPerSec  = chunksPerSec;
            chunksPerSec      = chunkAccum.getAndSet(0);
            oversendThreshold = oversendLimit.get();

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

        Vec3d vel = mc.player.getVelocity();
        prevHSpeed = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        prevClientPos = mc.player.getPos();
    }

    // ── Incoming packets (Netty thread: classify + count only, no chat/world reads) ────────────

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (event.packet instanceof PlayerPositionLookS2CPacket pkt) {
            overrideAccum.incrementAndGet();
            pendingSetbackPos.set(pkt.change().position());
        } else if (event.packet instanceof ChunkDataS2CPacket) {
            chunkAccum.incrementAndGet();
        }
    }

    // ── Outgoing packets ──────────────────────────────────────────────────────

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        if (event.packet instanceof PlayerMoveC2SPacket) {
            movePacketAccum.incrementAndGet();
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
