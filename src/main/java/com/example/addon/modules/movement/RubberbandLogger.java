package com.example.addon.modules.movement;

import com.example.addon.DWAddons;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Items;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.VehicleMoveC2SPacket;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.util.math.Vec3d;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * RubberbandLogger: captures the exact state around each set-back ("rubberband") so the logs can be
 * mined for correlations.
 *
 * The hard part of catching a rubberband is that by the time you notice it, the interesting state is
 * already gone. So this keeps a rolling ring buffer of the last N ticks of motion. When the server
 * sends a forced-position / teleport packet, it:
 *   - reads the buffered state from the tick JUST BEFORE the event (the bps + height that triggered it)
 *   - measures the correction itself (how far, in which direction, the server moved you)
 *   - writes one rich row to rubberband.csv and a readable block to rubberband-log.txt
 *
 * "bps" is reported as vertical bps (yDelta * 20), horizontal bps (hSpeed * 20), and total. Height is
 * the Y at the pre-event tick. Everything else that plausibly matters is captured alongside so you
 * don't have to guess in advance which variable correlates.
 *
 * <p><b>Threading.</b> {@link PacketEvent.Receive}/{@link PacketEvent.Send} fire on the Netty I/O
 * thread, but the player state, ring buffer and file writers are only ever touched from the client
 * tick thread. The packet handlers therefore do the absolute minimum — classify the packet and hand
 * a marker across via {@link AtomicReference}/{@link AtomicInteger} — and all capture, chat and file
 * I/O happens in {@link #onTick}. (The previous version mutated plain fields and wrote files from the
 * Netty thread; those writes were not guaranteed visible to the tick loop, so nothing got logged.)
 *
 * <p>Pure observer: it never changes your movement. Run it while you trigger ascents with another
 * module; it just records what the server does back.
 *
 * <p>Yarn mappings, Minecraft 1.21.8. Packets are matched with {@code instanceof} against the real
 * mapped classes rather than by class-name substring, which also fixes the outbound move-packet
 * counter (the move packets are inner classes whose simple names don't contain "PlayerMove").
 */
public class RubberbandLogger extends Module {

    /** Ticks after the last chorus-fruit-eating tick during which set-backs are treated as chorus warps. */
    private static final int CHORUS_GRACE_TICKS = 10;

    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Integer> preBuffer = sg.add(new IntSetting.Builder()
        .name("pre-buffer")
        .description("Ticks of history kept before each event (so we have the 'just before' state).")
        .defaultValue(20).min(2).max(100).sliderRange(2, 100)
        .build());

    private final Setting<Integer> mergeWindow = sg.add(new IntSetting.Builder()
        .name("merge-window")
        .description("After an event is logged, collapse any further set-back packets within this many "
            + "ticks into the same event. Anticheats often send 2-3 position corrections for one "
            + "rubberband; this stops that becoming 3 rows. The first packet always logs.")
        .defaultValue(10).min(0).max(60).sliderRange(0, 60)
        .build());

    private final Setting<Boolean> velocityEvents = sg.add(new BoolSetting.Builder()
        .name("velocity-events")
        .description("Also log server-applied velocity (knockback/explosion) aimed at YOU. Off by default "
            + "so the file stays focused on set-backs.")
        .defaultValue(false)
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
        double x, y, z;
        double yDelta;      // blocks gained this tick
        double hSpeed;      // horizontal blocks this tick
        double velY;        // intended vertical velocity
        boolean onGround, gliding, levitation;
        int movePackets;    // outgoing move packets that tick
        int sinceLast;      // ticks since previous event
    }

    private Snap[] ring;
    private int ringPos;
    private boolean ringFilled;

    private double prevY;
    private int sinceLastEvent;
    private int eventCount;
    private int chorusGrace;            // >0 means a chorus warp is expected; suppress set-back capture

    // ---- cross-thread handoff (Netty -> tick) ----
    private final AtomicReference<String> pendingEvent = new AtomicReference<>();
    private final AtomicInteger sendMoveCount = new AtomicInteger();

    // ---- capture state (tick thread only) ----
    private boolean capturePending;     // captured last tick, correction measured this tick
    private int mergeLeft;              // debounce countdown after a logged event
    private String capType;
    private double capVBps, capHBps, capTBps, capHeight;
    private Snap capSnap;
    private Vec3d capPos;               // pre-event position

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
        sinceLastEvent = 0;
        eventCount = 0;
        capturePending = false;
        mergeLeft = 0;
        chorusGrace = 0;
        pendingEvent.set(null);
        sendMoveCount.set(0);
        prevY = mc.player != null ? mc.player.getY() : 0;
        if (csv.get()) openCsv();
        if (txt.get()) openTxt();
        info("RubberbandLogger armed; logs at %s", logDir());
    }

    @Override
    public void onDeactivate() {
        info(String.format(Locale.ROOT, "RubberbandLogger off. Captured %d event(s).", eventCount));
        closeAll();
    }

    // ── Netty thread: classify only, hand a marker to the tick loop ──────────

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        Packet<?> p = event.packet;
        // instanceof PlayerMoveC2SPacket catches all four inner subtypes (Full / PositionAndOnGround
        // / LookAndOnGround / OnGroundOnly) — their simple names don't contain "PlayerMove".
        if (p instanceof PlayerMoveC2SPacket || p instanceof VehicleMoveC2SPacket) {
            sendMoveCount.incrementAndGet();
        }
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        Packet<?> p = event.packet;
        if (p instanceof PlayerPositionLookS2CPacket) {
            // Set-back wins over any pending velocity event.
            pendingEvent.set("setback");
        } else if (p instanceof DisconnectS2CPacket) {
            pendingEvent.set("kick");
        } else if (velocityEvents.get()) {
            if (p instanceof ExplosionS2CPacket) {
                pendingEvent.compareAndSet(null, "explosion-vel");
            } else if (p instanceof EntityVelocityUpdateS2CPacket vp
                && mc.player != null && vp.getEntityId() == mc.player.getId()) {
                pendingEvent.compareAndSet(null, "applied-vel");
            }
        }
    }

    // ── Tick thread: capture, measure correction, write ──────────────────────

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        double y = mc.player.getY();
        Vec3d pos = mc.player.getPos();
        Vec3d vel = mc.player.getVelocity();
        double yDelta = y - prevY;
        double hSpeed = Math.sqrt(vel.x * vel.x + vel.z * vel.z);

        // A chorus-fruit teleport is a player-initiated warp, not an anti-cheat set-back. While the
        // player is eating a chorus fruit (and for a short grace window after, since the warp lands a
        // tick or two after consumption finishes) we mark the next set-back as expected and skip it.
        boolean eatingChorus = mc.player.isUsingItem()
            && mc.player.getActiveItem().getItem() == Items.CHORUS_FRUIT;
        if (eatingChorus) chorusGrace = CHORUS_GRACE_TICKS;

        // 1) Finalize a capture made on a PREVIOUS tick. By now the set-back has been applied and the
        // corrected position has settled, so 'pos' is where the server put us.
        if (capturePending) {
            double dx = capPos != null ? pos.x - capPos.x : 0;
            double dy = capPos != null ? pos.y - capPos.y : 0;
            double dz = capPos != null ? pos.z - capPos.z : 0;
            double pull = Math.sqrt(dx * dx + dy * dy + dz * dz);
            writeEvent(capType, capVBps, capHBps, capTBps, capHeight, capSnap, dx, dy, dz, pull);
            capturePending = false;
            mergeLeft = mergeWindow.get();
        }

        // 2) Consume a new event marker handed over from the Netty thread.
        String ev = pendingEvent.getAndSet(null);
        if (ev != null && mergeLeft == 0) {
            boolean chorusWarp = "setback".equals(ev) && chorusGrace > 0;
            if (!chorusWarp) {
                capture(ev);   // reads the tick-before snapshot (not yet overwritten below)
            }
        }

        // 3) Record this tick into the ring.
        Snap s = ring[ringPos];
        s.x = pos.x; s.y = y; s.z = pos.z;
        s.yDelta = yDelta;
        s.hSpeed = hSpeed;
        s.velY = vel.y;
        s.onGround = mc.player.isOnGround();
        s.gliding = mc.player.isGliding();
        s.levitation = mc.player.hasStatusEffect(StatusEffects.LEVITATION);
        s.movePackets = sendMoveCount.getAndSet(0);
        s.sinceLast = sinceLastEvent;

        ringPos = (ringPos + 1) % ring.length;
        if (ringPos == 0) ringFilled = true;

        if (mergeLeft > 0) mergeLeft--;
        if (chorusGrace > 0) chorusGrace--;
        prevY = y;
        sinceLastEvent++;
    }

    /** Reads the pre-event snapshot and arms finalize for the next tick. */
    private void capture(String type) {
        eventCount++;
        Snap before = previousSnap();      // tick just before the event
        capSnap = before;
        capPos = before != null ? new Vec3d(before.x, before.y, before.z) : mc.player.getPos();
        capVBps = before != null ? before.yDelta * 20.0 : 0;
        capHBps = before != null ? before.hSpeed * 20.0 : 0;
        capTBps = Math.sqrt(capVBps * capVBps + capHBps * capHBps);
        capHeight = before != null ? before.y : mc.player.getY();
        capType = type;
        capturePending = true;
        sinceLastEvent = 0;
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
    private Path logDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    private void openCsv() {
        try {
            Path f = logDir().resolve("rubberband.csv");
            boolean fresh = !Files.exists(f) || Files.size(f) == 0;
            csvW = Files.newBufferedWriter(f, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (fresh) {
                csvW.write("time,event,n,vBps,hBps,totalBps,height,velY,onGround,gliding,levitation,"
                    + "movePackets,sinceLast,pullX,pullY,pullZ,pullDist\n");
                csvW.flush();
            }
        } catch (IOException e) { csvW = null; warning("rubberband.csv open failed: " + e.getMessage()); }
    }

    private void openTxt() {
        try {
            Path f = logDir().resolve("rubberband-log.txt");
            txtW = Files.newBufferedWriter(f, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            txtW.write("\n=== session " + stamp.format(new Date()) + " ===\n");
            txtW.flush();
        } catch (IOException e) { txtW = null; warning("rubberband-log.txt open failed: " + e.getMessage()); }
    }

    private void writeEvent(String type, double vBps, double hBps, double tBps, double height,
                            Snap s, double dx, double dy, double dz, double pull) {
        String time = stamp.format(new Date());

        if (chat.get()) {
            info(String.format(Locale.ROOT,
                "[%s] #%d vBps=%.1f hBps=%.1f Y=%.1f pulled=%.2f", type, eventCount, vBps, hBps, height, pull));
        }
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
            } catch (IOException e) { warning("rubberband.csv write failed: " + e.getMessage()); }
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
            } catch (IOException e) { warning("rubberband-log.txt write failed: " + e.getMessage()); }
        }
    }

    private void closeAll() {
        if (csvW != null) { try { csvW.flush(); csvW.close(); } catch (IOException ignored) {} csvW = null; }
        if (txtW != null) { try { txtW.flush(); txtW.close(); } catch (IOException ignored) {} txtW = null; }
    }
}
