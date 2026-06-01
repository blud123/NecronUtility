package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.VehicleMoveC2SPacket;
import net.minecraft.util.math.Vec3d;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Locale;

/**
 * MovementProbe: instruments the connection so you can reverse-engineer when and why the server
 * reacts to a movement method, instead of guessing.
 *
 * Per tick it records your motion (yDelta, horizontal speed, velocity, on-ground, gliding) and how
 * many movement packets you sent (the MorePackets-relevant rate). On the incoming side it classifies
 * the server's reactions:
 *   - a forced position packet  = the set-back / "moved too quickly" punishment
 *   - an entity-velocity packet  = server-APPLIED (trusted) velocity, e.g. knockback/launch
 *   - an explosion packet        = another applied-velocity source
 *   - a disconnect packet        = you got kicked
 *
 * When a set-back fires it snapshots the motion from the preceding ticks, so over many samples you
 * can read off the real per-move vertical cap, the speed cap, how far you can drift before the server
 * forces you back, and which methods produce trusted velocity vs. rejected client claims. Toggle the
 * CSV on and analyse it offline; the chat log is for live observation.
 *
 * Yarn mappings.
 */
public class MovementProbe extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> logChat = sgGeneral.add(new BoolSetting.Builder()
        .name("log-chat")
        .description("Print a line to chat on every set-back / applied-velocity / kick.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> logFile = sgGeneral.add(new BoolSetting.Builder()
        .name("log-file")
        .description("Write per-tick telemetry to movement-probe.csv in your game folder.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> trackVehicle = sgGeneral.add(new BoolSetting.Builder()
        .name("track-vehicle")
        .description("Also count vehicle movement packets in the rate.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> window = sgGeneral.add(new IntSetting.Builder()
        .name("window")
        .description("How many recent ticks of motion to snapshot when a set-back fires.")
        .defaultValue(10)
        .min(2).max(60)
        .sliderRange(2, 60)
        .build());

    // --- live state ---
    private double prevY;
    private Vec3d lastForced;          // last position the server forced us to (set-back parking spot)
    private int movePackets;           // movement packets sent during the current tick
    private int ticksSinceSetback;
    private long tick;

    // counters
    private int setbacks, serverVel, explosions, kicks;

    // empirical extremes (only sampled on clean ticks, so they reflect what the server tolerated)
    private double peakYDeltaNoSetback;
    private double peakSpeedNoSetback;
    private double maxDrift;

    // ring buffers for the pre-set-back snapshot
    private double[] yHist, spdHist;
    private int histIdx;

    // flags / io
    private boolean updateForcedNextTick;
    private boolean setbackThisTick;
    private BufferedWriter writer;

    public MovementProbe() {
        super(AddonTemplate.CATEGORY, "movement-probe",
            "Instruments packets and per-tick motion to reverse-engineer when and why the server reacts.");
    }

    @Override
    public void onActivate() {
        tick = 0;
        movePackets = 0;
        ticksSinceSetback = 0;
        setbacks = serverVel = explosions = kicks = 0;
        peakYDeltaNoSetback = 0;
        peakSpeedNoSetback = 0;
        maxDrift = 0;
        updateForcedNextTick = false;
        setbackThisTick = false;

        yHist = new double[window.get()];
        spdHist = new double[window.get()];
        histIdx = 0;

        if (mc.player != null) {
            prevY = mc.player.getY();
            lastForced = mc.player.getPos();
        } else {
            prevY = 0;
            lastForced = Vec3d.ZERO;
        }

        if (logFile.get()) openCsv();
    }

    @Override
    public void onDeactivate() {
        printSummary();
        closeCsv();
    }

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        if (event.packet instanceof PlayerMoveC2SPacket) movePackets++;
        else if (trackVehicle.get() && event.packet instanceof VehicleMoveC2SPacket) movePackets++;
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        // Match by simple class name so the probe survives the packet renames between MC versions.
        String name = event.packet.getClass().getSimpleName();

        if (name.contains("PlayerPosition") || name.contains("Teleport")) {
            onSetback();                       // server forced our position = the punishment
        } else if (name.contains("EntityVelocityUpdate")) {
            serverVel++;                       // note: fired for any entity, not just us
            if (logChat.get()) info(String.format(Locale.ROOT, "applied velocity packet (#%d)", serverVel));
        } else if (name.contains("Explosion")) {
            explosions++;
            if (logChat.get()) info(String.format(Locale.ROOT, "explosion velocity (#%d)", explosions));
        } else if (name.contains("Disconnect")) {
            kicks++;
            if (logChat.get()) info(String.format(Locale.ROOT, "DISCONNECT received (#%d) - likely kicked", kicks));
        }
    }

    private void onSetback() {
        setbacks++;
        setbackThisTick = true;
        updateForcedNextTick = true;

        // What did the motion look like in the ticks just before the punishment?
        double peakY = 0, peakSpd = 0;
        for (int i = 0; i < yHist.length; i++) {
            peakY = Math.max(peakY, Math.abs(yHist[i]));
            peakSpd = Math.max(peakSpd, spdHist[i]);
        }
        double drift = mc.player != null ? mc.player.getPos().distanceTo(lastForced) : 0;

        if (logChat.get()) {
            info(String.format(Locale.ROOT,
                "SET-BACK #%d | %d ticks since last | peak yDelta=%.3f | peak hSpeed=%.3f | drift=%.2f",
                setbacks, ticksSinceSetback, peakY, peakSpd, drift));
        }

        ticksSinceSetback = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        tick++;

        if (updateForcedNextTick) {
            // The teleport has been applied by now; this is where the server actually parked us.
            lastForced = mc.player.getPos();
            updateForcedNextTick = false;
        }

        double y = mc.player.getY();
        double yDelta = y - prevY;

        Vec3d vel = mc.player.getVelocity();
        Vec3d pos = mc.player.getPos();
        double hSpeed = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        double drift = pos.distanceTo(lastForced);

        boolean onGround = mc.player.isOnGround();
        boolean gliding = mc.player.isGliding();

        if (!setbackThisTick) {
            peakYDeltaNoSetback = Math.max(peakYDeltaNoSetback, Math.abs(yDelta));
            peakSpeedNoSetback = Math.max(peakSpeedNoSetback, hSpeed);
            maxDrift = Math.max(maxDrift, drift);
        }

        yHist[histIdx] = yDelta;
        spdHist[histIdx] = hSpeed;
        histIdx = (histIdx + 1) % yHist.length;

        if (logFile.get() && writer != null) {
            writeCsv(tick, pos, yDelta, hSpeed, vel.y, onGround, gliding, movePackets, ticksSinceSetback, drift, setbackThisTick);
        }

        prevY = y;
        movePackets = 0;
        ticksSinceSetback++;
        setbackThisTick = false;
    }

    @Override
    public String getInfoString() {
        return String.format(Locale.ROOT, "%d set-backs", setbacks);
    }

    // --- csv ---
    private void openCsv() {
        try {
            File f = new File(mc.runDirectory, "movement-probe.csv");
            writer = new BufferedWriter(new FileWriter(f, false));
            writer.write("tick,x,y,z,yDelta,hSpeed,velY,onGround,gliding,movePackets,ticksSinceSetback,drift,setback\n");
            writer.flush();
        } catch (IOException e) {
            writer = null;
            warning(String.format(Locale.ROOT, "Could not open movement-probe.csv: %s", e.getMessage()));
        }
    }

    private void writeCsv(long t, Vec3d pos, double yDelta, double hSpeed, double velY,
                          boolean onGround, boolean gliding, int packets, int tss, double drift, boolean setback) {
        try {
            writer.write(String.format(Locale.ROOT, "%d,%.3f,%.3f,%.3f,%.4f,%.4f,%.4f,%d,%d,%d,%d,%.3f,%d\n",
                t, pos.x, pos.y, pos.z, yDelta, hSpeed, velY,
                onGround ? 1 : 0, gliding ? 1 : 0, packets, tss, drift, setback ? 1 : 0));
            if (t % 20 == 0) writer.flush();
        } catch (IOException e) {
            warning(String.format(Locale.ROOT, "CSV write failed, disabling file log: %s", e.getMessage()));
            closeCsv();
        }
    }

    private void closeCsv() {
        if (writer != null) {
            try { writer.flush(); writer.close(); } catch (IOException ignored) {}
            writer = null;
        }
    }

    private void printSummary() {
        info("=== MovementProbe summary ===");
        info(String.format(Locale.ROOT, "ticks=%d  set-backs=%d  appliedVel=%d  explosions=%d  kicks=%d",
            tick, setbacks, serverVel, explosions, kicks));
        info(String.format(Locale.ROOT, "max tolerated yDelta=%.3f/tick  |  max tolerated hSpeed=%.3f/tick",
            peakYDeltaNoSetback, peakSpeedNoSetback));
        info(String.format(Locale.ROOT, "max drift before a forced reset=%.2f blocks", maxDrift));
    }
}
