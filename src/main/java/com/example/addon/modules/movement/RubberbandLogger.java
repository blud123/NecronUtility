package com.example.addon.modules.movement;

import com.example.addon.DWAddons;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.Vec3d;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * RubberbandLogger: focused on capturing the exact state around each set-back ("rubberband") so the
 * logs can be mined for correlations.
 *
 * The hard part of catching a rubberband is that by the time you notice it, the interesting state is
 * already gone. So this keeps a rolling ring buffer of the last N ticks of motion. When the server
 * sends a forced-position / teleport packet, it:
 *   - reads the buffered state from the tick JUST BEFORE the event (the bps + height that triggered it)
 *   - measures the correction itself (how far, in which direction, the server moved you)
 *   - watches the following few ticks to see how recovery behaves
 *   - writes one rich row to rubberband.csv and a readable block to rubberband-log.txt
 *
 * "bps" is reported as vertical bps (yDelta * 20), horizontal bps (hSpeed * 20), and total. Height is
 * the Y at the pre-event tick. Everything else that plausibly matters is captured alongside so you
 * don't have to guess in advance which variable correlates.
 *
 * Pure observer: it never changes your movement. Run it while you trigger ascents with another
 * module; it just records what the server does back.
 *
 * Yarn mappings, Minecraft 1.21.8.
 */
public class RubberbandLogger extends Module {

    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Integer> preBuffer = sg.add(new IntSetting.Builder()
        .name("pre-buffer")
        .description("Ticks of history kept before each event (so we have the 'just before' state).")
        .defaultValue(20).min(2).max(100).sliderRange(2, 100)
        .build());

    private final Setting<Integer> postWindow = sg.add(new IntSetting.Builder()
        .name("post-window")
        .description("Ticks tracked after an event to measure recovery.")
        .defaultValue(15).min(1).max(60).sliderRange(1, 60)
        .build());

    private final Setting<Boolean> csv = sg.add(new BoolSetting.Builder()
        .name("write-csv")
        .description("Write machine-readable rows to rubberband.csv.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> txt = sg.add(new BoolSetting.Builder()
        .name("write-txt")
        .description("Write a human-readable block per event to rubberband-log.txt.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> chat = sg.add(new BoolSetting.Builder()
        .name("chat")
        .description("Print a one-line summary in chat on each event.")
        .defaultValue(true)
        .build());

    // ---- rolling per-tick history ----
    private static final class Snap {
        long tick;
        double x, y, z;
        double yDelta;      // blocks gained this tick
        double hSpeed;      // horizontal blocks this tick
        double velY;        // intended vertical velocity
        boolean onGround, gliding, inWeb, levitation;
        int movePackets;    // outgoing move packets that tick
        int sinceLast;      // ticks since previous event
    }

    private Snap[] ring;
    private int ringPos;
    private boolean ringFilled;

    private double prevY;
    private int movePacketsThisTick;
    private long tick;
    private int sinceLastEvent;
    private int eventCount;

    // post-event recovery tracking
    private boolean awaitingRecovery;
    private int recoveryTick;
    private Vec3d eventPos;          // where we were when the set-back hit (pre-event position)
    private Vec3d correctionPos;     // where the server put us
    private Snap eventSnap;

    private BufferedWriter csvW, txtW;
    private final SimpleDateFormat stamp = new SimpleDateFormat("HH:mm:ss");

    public RubberbandLogger() {
        super(DWAddons.CATEGORY, "rubberband-logger",
            "Captures bps + height + full state at each set-back into shareable logs for correlation.");
    }

    @Override
    public void onActivate() {
        ring = new Snap[Math.max(2, preBuffer.get())];
        for (int i = 0; i < ring.length; i++) ring[i] = new Snap();
        ringPos = 0;
        ringFilled = false;
        tick = 0;
        sinceLastEvent = 0;
        eventCount = 0;
        movePacketsThisTick = 0;
        awaitingRecovery = false;
        prevY = mc.player != null ? mc.player.getY() : 0;
        if (csv.get()) openCsv();
        if (txt.get()) openTxt();
        info("RubberbandLogger armed. Trigger your ascents; set-backs will be logged.");
    }

    @Override
    public void onDeactivate() {
        info(String.format(Locale.ROOT, "RubberbandLogger off. Captured %d event(s).", eventCount));
        closeAll();
    }

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        String n = event.packet.getClass().getSimpleName();
        if (n.contains("PlayerMove") || n.contains("VehicleMove")) movePacketsThisTick++;
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        String n = event.packet.getClass().getSimpleName();
        if (mc.player == null) return;

        boolean setback = n.contains("PlayerPosition") || n.contains("Teleport");
        if (setback) {
            captureEvent("setback");
        } else if (n.contains("Explosion") || n.contains("EntityVelocityUpdate")) {
            // Not a rubberband, but log it: distinguishes server-APPLIED velocity from punishment.
            captureEvent(n.contains("Explosion") ? "explosion-vel" : "applied-vel");
        } else if (n.contains("Disconnect")) {
            captureEvent("kick");
        }
    }

    /** Reads the pre-event snapshot and arms recovery measurement. */
    private void captureEvent(String type) {
        eventCount++;
        Snap before = previousSnap();      // tick just before the event
        eventSnap = before;
        eventPos = before != null ? new Vec3d(before.x, before.y, before.z) : mc.player.getPos();
        correctionPos = null;              // filled next tick once the teleport is applied
        awaitingRecovery = true;
        recoveryTick = 0;

        double vBps = before != null ? before.yDelta * 20.0 : 0;
        double hBps = before != null ? before.hSpeed * 20.0 : 0;
        double tBps = Math.sqrt(vBps * vBps + hBps * hBps);
        double height = before != null ? before.y : mc.player.getY();

        if (chat.get()) {
            info(String.format(Locale.ROOT,
                "[%s] #%d vBps=%.1f hBps=%.1f Y=%.1f sinceLast=%d",
                type, eventCount, vBps, hBps, height, before != null ? before.sinceLast : -1));
        }

        // stash type + derived values to write once the correction vector is known
        pendingType = type;
        pendingVBps = vBps;
        pendingHBps = hBps;
        pendingTBps = tBps;
        pendingHeight = height;

        sinceLastEvent = 0;
    }

    // pending event fields (written after correction measured)
    private String pendingType;
    private double pendingVBps, pendingHBps, pendingTBps, pendingHeight;

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        tick++;

        double y = mc.player.getY();
        Vec3d pos = mc.player.getPos();
        Vec3d vel = mc.player.getVelocity();
        double yDelta = y - prevY;
        double hSpeed = Math.sqrt(vel.x * vel.x + vel.z * vel.z);

        // If an event just fired, the correction position is known now -> finalize the record.
        if (awaitingRecovery && correctionPos == null) {
            correctionPos = pos;
            double dx = eventPos != null ? correctionPos.x - eventPos.x : 0;
            double dy = eventPos != null ? correctionPos.y - eventPos.y : 0;
            double dz = eventPos != null ? correctionPos.z - eventPos.z : 0;
            double pullDist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            writeEvent(pendingType, pendingVBps, pendingHBps, pendingTBps, pendingHeight,
                eventSnap, dx, dy, dz, pullDist);
        }

        // record this tick into the ring
        Snap s = ring[ringPos];
        s.tick = tick;
        s.x = pos.x; s.y = y; s.z = pos.z;
        s.yDelta = yDelta;
        s.hSpeed = hSpeed;
        s.velY = vel.y;
        s.onGround = mc.player.isOnGround();
        s.gliding = mc.player.isGliding();
        s.inWeb = false; // placeholder; wire to status effects if needed
        s.levitation = mc.player.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.LEVITATION);
        s.movePackets = movePacketsThisTick;
        s.sinceLast = sinceLastEvent;

        ringPos = (ringPos + 1) % ring.length;
        if (ringPos == 0) ringFilled = true;

        if (awaitingRecovery) {
            recoveryTick++;
            if (recoveryTick >= postWindow.get()) awaitingRecovery = false;
        }

        prevY = y;
        movePacketsThisTick = 0;
        sinceLastEvent++;
    }

    /** The snapshot from the tick immediately before the current one (the 'just before' state). */
    private Snap previousSnap() {
        if (!ringFilled && ringPos == 0) return null;
        int idx = (ringPos - 1 + ring.length) % ring.length;
        return ring[idx];
    }

    @Override
    public String getInfoString() {
        return String.format(Locale.ROOT, "%d events", eventCount);
    }

    // ---------- io ----------
    private void openCsv() {
        try {
            File f = new File(mc.runDirectory, "rubberband.csv");
            csvW = new BufferedWriter(new FileWriter(f, true)); // append across sessions
            if (f.length() == 0) {
                csvW.write("time,event,n,vBps,hBps,totalBps,height,velY,onGround,gliding,levitation,"
                    + "movePackets,sinceLast,pullX,pullY,pullZ,pullDist\n");
            }
            csvW.flush();
        } catch (IOException e) { csvW = null; warning("rubberband.csv open failed: " + e.getMessage()); }
    }

    private void openTxt() {
        try {
            File f = new File(mc.runDirectory, "rubberband-log.txt");
            txtW = new BufferedWriter(new FileWriter(f, true));
            txtW.write("\n=== session " + stamp.format(new Date()) + " ===\n");
            txtW.flush();
        } catch (IOException e) { txtW = null; warning("rubberband-log.txt open failed: " + e.getMessage()); }
    }

    private void writeEvent(String type, double vBps, double hBps, double tBps, double height,
                            Snap s, double dx, double dy, double dz, double pull) {
        String time = stamp.format(new Date());
        if (csvW != null) {
            try {
                csvW.write(String.format(Locale.ROOT,
                    "%s,%s,%d,%.2f,%.2f,%.2f,%.2f,%.4f,%d,%d,%d,%d,%d,%.3f,%.3f,%.3f,%.3f\n",
                    time, type, eventCount, vBps, hBps, tBps, height,
                    s != null ? s.velY : 0,
                    s != null && s.onGround ? 1 : 0,
                    s != null && s.gliding ? 1 : 0,
                    s != null && s.levitation ? 1 : 0,
                    s != null ? s.movePackets : -1,
                    s != null ? s.sinceLast : -1,
                    dx, dy, dz, pull));
                csvW.flush();
            } catch (IOException e) { closeAll(); }
        }
        if (txtW != null) {
            try {
                txtW.write(String.format(Locale.ROOT,
                    "[%s] %s #%d%n  just-before: vBps=%.1f hBps=%.1f totalBps=%.1f Y=%.1f velY=%.3f%n"
                    + "  flags: onGround=%b gliding=%b levitation=%b movePackets=%d sinceLast=%d%n"
                    + "  correction: dx=%.2f dy=%.2f dz=%.2f pulled=%.2f blocks%n",
                    time, type, eventCount, vBps, hBps, tBps, height,
                    s != null ? s.velY : 0,
                    s != null && s.onGround, s != null && s.gliding, s != null && s.levitation,
                    s != null ? s.movePackets : -1, s != null ? s.sinceLast : -1,
                    dx, dy, dz, pull));
                txtW.flush();
            } catch (IOException e) { closeAll(); }
        }
    }

    private void closeAll() {
        if (csvW != null) { try { csvW.flush(); csvW.close(); } catch (IOException ignored) {} csvW = null; }
        if (txtW != null) { try { txtW.flush(); txtW.close(); } catch (IOException ignored) {} txtW = null; }
    }
}
