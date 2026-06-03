package com.example.addon.modules.movement;

import com.example.addon.DWAddons;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Items;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.common.CommonPongC2SPacket;
import net.minecraft.network.packet.c2s.play.*;
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
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-packet-type outbound logger (reference: Shoreline {@code PacketLoggerModule}). Unlike the
 * sibling {@link PacketTracker} — which is a throughput/rubberband <em>diagnostic</em> — this logs
 * individual C2S packets to chat, a file, and an in-memory ring buffer that is dumped on disconnect
 * so you can see exactly what was sent right before a kick.
 *
 * <p>Each listed packet type has its own toggle (all off by default). Only enabled types are
 * logged. Sinks are independent: {@code log-file}, {@code log-chat} (throttled to avoid flooding
 * chat with movement packets), and {@code log-disconnect} (the ring-buffer dump).
 *
 * <p><b>Rubberband capture.</b> When {@code log-rubberband} is on it also folds in the logic from
 * {@link RubberbandLogger}: a rolling ring buffer of recent motion is kept, and whenever the server
 * sends a forced-position / teleport packet it captures the "just before" state plus the size of the
 * correction and writes a row to {@code necron/packets/rubberband.csv} and a readable block to
 * {@code necron/packets/rubberband-log.txt}. Chorus-fruit teleports are filtered out (a player-
 * initiated chorus warp is not an anticheat set-back).
 *
 * <p><b>Threading.</b> {@link PacketEvent.Receive}/{@link PacketEvent.Send} fire on the Netty I/O
 * thread, but the rubberband ring buffer, capture state and file writers are only ever touched from
 * the client tick thread. The packet handlers therefore do the minimum — classify and hand a marker
 * across via {@link AtomicReference}/{@link AtomicInteger} — and all capture and file I/O happens in
 * {@link #onTick}.
 */
public class PacketLogger extends Module {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final DateTimeFormatter RB_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** Ticks after the last chorus-fruit-eating tick during which set-backs are treated as chorus warps. */
    private static final int CHORUS_GRACE_TICKS = 10;

    // ── Packet-type toggles ──────────────────────────────────────────────
    private final SettingGroup sgTypes      = settings.getDefaultGroup();
    private final SettingGroup sgOutput     = settings.createGroup("Output");
    private final SettingGroup sgRubberband = settings.createGroup("Rubberband");

    private final Setting<Boolean> movePos        = type("move-pos", "ServerboundMovePlayerPacket.Pos");
    private final Setting<Boolean> movePosRot     = type("move-pos-rot", "ServerboundMovePlayerPacket.PosRot");
    private final Setting<Boolean> moveRot        = type("move-rot", "ServerboundMovePlayerPacket.Rot");
    private final Setting<Boolean> moveStatusOnly = type("move-status-only", "ServerboundMovePlayerPacket.StatusOnly");
    private final Setting<Boolean> moveVehicle    = type("move-vehicle", "ServerboundMoveVehiclePacket");
    private final Setting<Boolean> playerAction   = type("player-action", "ServerboundPlayerActionPacket");
    private final Setting<Boolean> setCarriedItem = type("set-carried-item", "ServerboundSetCarriedItemPacket");
    private final Setting<Boolean> containerClick = type("container-click", "ServerboundContainerClickPacket");
    private final Setting<Boolean> pickFromBlock  = type("pick-from-block", "ServerboundPickItemFromBlockPacket");
    private final Setting<Boolean> pickFromEntity = type("pick-from-entity", "ServerboundPickItemFromEntityPacket");
    private final Setting<Boolean> swing          = type("swing", "ServerboundSwingPacket");
    private final Setting<Boolean> interact       = type("interact", "ServerboundInteractPacket");
    private final Setting<Boolean> useItemOn      = type("use-item-on", "ServerboundUseItemOnPacket");
    private final Setting<Boolean> useItem        = type("use-item", "ServerboundUseItemPacket");
    private final Setting<Boolean> playerCommand  = type("player-command", "ServerboundPlayerCommandPacket");
    private final Setting<Boolean> clientCommand  = type("client-command", "ServerboundClientCommandPacket");
    private final Setting<Boolean> containerClose = type("container-close", "ServerboundContainerClosePacket");
    private final Setting<Boolean> acceptTeleport = type("accept-teleport", "ServerboundAcceptTeleportationPacket");
    private final Setting<Boolean> pong           = type("pong", "ServerboundPongPacket");

    private Setting<Boolean> type(String name, String desc) {
        return sgTypes.add(new BoolSetting.Builder().name(name).description("Log " + desc + ".").defaultValue(false).build());
    }

    // ── Output sinks ─────────────────────────────────────────────────────
    private final Setting<Boolean> logFile = sgOutput.add(new BoolSetting.Builder()
        .name("log-file")
        .description("Append every logged packet to necron/packets/packets.log.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> logChat = sgOutput.add(new BoolSetting.Builder()
        .name("log-chat")
        .description("Print logged packets to chat (rate-limited so movement packets don't flood it).")
        .defaultValue(false)
        .build());

    private final Setting<Integer> chatThrottleMs = sgOutput.add(new IntSetting.Builder()
        .name("chat-throttle-ms")
        .description("Minimum milliseconds between chat lines.")
        .defaultValue(250)
        .min(0).max(2000)
        .sliderRange(0, 2000)
        .visible(logChat::get)
        .build());

    private final Setting<Boolean> logDisconnect = sgOutput.add(new BoolSetting.Builder()
        .name("log-disconnect")
        .description("Keep a ring buffer of recent packets and dump it to a file on disconnect (see what was sent before a kick).")
        .defaultValue(true)
        .build());

    private final Setting<Integer> bufferSize = sgOutput.add(new IntSetting.Builder()
        .name("buffer-size")
        .description("Max packets kept in the disconnect ring buffer.")
        .defaultValue(500)
        .min(50).max(5000)
        .sliderRange(50, 5000)
        .visible(logDisconnect::get)
        .build());

    // ── Rubberband capture ───────────────────────────────────────────────
    private final Setting<Boolean> logRubberband = sgRubberband.add(new BoolSetting.Builder()
        .name("log-rubberband")
        .description("Capture bps + height + full state at each anti-cheat set-back into shareable logs.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> preBuffer = sgRubberband.add(new IntSetting.Builder()
        .name("pre-buffer")
        .description("Ticks of history kept before each event (so we have the 'just before' state).")
        .defaultValue(20).min(2).max(100).sliderRange(2, 100)
        .visible(logRubberband::get)
        .build());

    private final Setting<Integer> mergeWindow = sgRubberband.add(new IntSetting.Builder()
        .name("merge-window")
        .description("After an event is logged, collapse any further set-back packets within this many "
            + "ticks into the same event. Anticheats often send 2-3 position corrections for one "
            + "rubberband; this stops that becoming 3 rows. The first packet always logs.")
        .defaultValue(10).min(0).max(60).sliderRange(0, 60)
        .visible(logRubberband::get)
        .build());

    private final Setting<Boolean> velocityEvents = sgRubberband.add(new BoolSetting.Builder()
        .name("velocity-events")
        .description("Also log server-applied velocity (knockback/explosion) aimed at YOU. Off by default "
            + "so the file stays focused on set-backs.")
        .defaultValue(false)
        .visible(logRubberband::get)
        .build());

    private final Setting<Boolean> rbCsv = sgRubberband.add(new BoolSetting.Builder()
        .name("write-csv")
        .description("Write machine-readable rows to necron/packets/rubberband.csv.")
        .defaultValue(true)
        .visible(logRubberband::get)
        .build());

    private final Setting<Boolean> rbTxt = sgRubberband.add(new BoolSetting.Builder()
        .name("write-txt")
        .description("Write a human-readable block per event to necron/packets/rubberband-log.txt.")
        .defaultValue(true)
        .visible(logRubberband::get)
        .build());

    private final Setting<Boolean> rbChat = sgRubberband.add(new BoolSetting.Builder()
        .name("rubberband-chat")
        .description("Print a one-line summary in chat on each set-back.")
        .defaultValue(true)
        .visible(logRubberband::get)
        .build());

    // ── Packet-log state ─────────────────────────────────────────────────
    private final Deque<String> ring = new ArrayDeque<>();
    // Per-packet lines formatted on the Netty thread, drained to the file/chat sinks on the client
    // thread (file I/O and chat must not run on the Netty thread).
    private final Queue<String> outQueue = new ConcurrentLinkedQueue<>();
    private BufferedWriter writer;
    private boolean writerFailed = false;
    private long lastChatMs = 0L;

    // ── Rubberband state ─────────────────────────────────────────────────
    private static final class Snap {
        double x, y, z;
        double yDelta;      // blocks gained this tick
        double hSpeed;      // horizontal blocks this tick
        double velY;        // intended vertical velocity
        boolean onGround, gliding, levitation;
        int movePackets;    // outgoing move packets that tick
        int sinceLast;      // ticks since previous event
    }

    private Snap[] rbRing;
    private int rbRingPos;
    private boolean rbRingFilled;

    private double prevY;
    private int sinceLastEvent;
    private int eventCount;
    private int chorusGrace;            // >0 means a chorus warp is expected; suppress set-back capture

    // cross-thread handoff (Netty -> tick)
    private final AtomicReference<String> pendingEvent = new AtomicReference<>();
    private final AtomicInteger sendMoveCount = new AtomicInteger();

    // capture state (tick thread only)
    private boolean capturePending;
    private int mergeLeft;
    private String capType;
    private double capVBps, capHBps, capTBps, capHeight;
    private Snap capSnap;
    private Vec3d capPos;

    private BufferedWriter csvW, txtW;

    public PacketLogger() {
        super(DWAddons.CATEGORY, "packet-logger",
            "Logs individual C2S packets to chat/file, captures anti-cheat rubberbands, and dumps a ring buffer on disconnect.");
    }

    @Override
    public void onActivate() {
        synchronized (ring) { ring.clear(); }
        outQueue.clear();
        writerFailed = false;
        lastChatMs = 0L;

        // Rubberband capture init.
        rbRing = new Snap[Math.max(2, preBuffer.get())];
        for (int i = 0; i < rbRing.length; i++) rbRing[i] = new Snap();
        rbRingPos = 0;
        rbRingFilled = false;
        sinceLastEvent = 0;
        eventCount = 0;
        chorusGrace = 0;
        capturePending = false;
        mergeLeft = 0;
        pendingEvent.set(null);
        sendMoveCount.set(0);
        prevY = mc.player != null ? mc.player.getY() : 0;
        if (logRubberband.get()) {
            if (rbCsv.get()) openCsv();
            if (rbTxt.get()) openTxt();
        }
    }

    @Override
    public void onDeactivate() {
        closeWriter();
        closeRubberbandWriters();
    }

    // ── Outgoing packets ─────────────────────────────────────────────────

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        Packet<?> p = event.packet;

        // Rubberband: count outgoing move packets per tick.
        if (logRubberband.get() && (p instanceof PlayerMoveC2SPacket || p instanceof VehicleMoveC2SPacket)) {
            sendMoveCount.incrementAndGet();
        }

        // Per-packet-type logging.
        boolean wantFileOrChat = logFile.get() || logChat.get();
        if (!wantFileOrChat && !logDisconnect.get()) return;

        String body = format(p);
        if (body == null) return; // type toggle off, or unhandled type

        String line = "[" + TIME.format(LocalTime.now()) + "] " + body;

        // pushRing is an in-memory synchronized buffer, safe to fill here; file/chat are deferred to
        // the client thread via outQueue so we never touch disk or the chat HUD from the Netty thread.
        if (logDisconnect.get()) pushRing(line);
        if (wantFileOrChat) outQueue.add(line);
    }

    /** Drains queued per-packet lines to the file/chat sinks on the client thread. */
    private void drainOutQueue() {
        String line;
        while ((line = outQueue.poll()) != null) {
            if (logFile.get()) writeFile(line);
            if (logChat.get()) chat(line);
        }
    }

    // ── Incoming packets: classify rubberband markers (Netty thread) ──────

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (!logRubberband.get()) return;
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

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (logDisconnect.get()) dumpRing();
    }

    // ── Tick thread: capture, measure correction, write ──────────────────

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        // Per-packet file/chat sink runs here, not on the Netty thread.
        drainOutQueue();

        if (!logRubberband.get()) return;

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

        // 1) Finalize a capture made on a PREVIOUS tick.
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
                capture(ev); // reads the tick-before snapshot (not yet overwritten below)
            }
        }

        // 3) Record this tick into the ring.
        Snap s = rbRing[rbRingPos];
        s.x = pos.x; s.y = y; s.z = pos.z;
        s.yDelta = yDelta;
        s.hSpeed = hSpeed;
        s.velY = vel.y;
        s.onGround = mc.player.isOnGround();
        s.gliding = mc.player.isGliding();
        s.levitation = mc.player.hasStatusEffect(StatusEffects.LEVITATION);
        s.movePackets = sendMoveCount.getAndSet(0);
        s.sinceLast = sinceLastEvent;

        rbRingPos = (rbRingPos + 1) % rbRing.length;
        if (rbRingPos == 0) rbRingFilled = true;

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
        if (!rbRingFilled && rbRingPos == 0) return null;
        int idx = (rbRingPos - 1 + rbRing.length) % rbRing.length;
        return rbRing[idx];
    }

    @Override
    public String getInfoString() {
        return logRubberband.get() ? String.format(Locale.ROOT, "%d rubberbands", eventCount) : null;
    }

    // ── Ring buffer ──────────────────────────────────────────────────────

    private void pushRing(String line) {
        synchronized (ring) {
            ring.addLast(line);
            while (ring.size() > bufferSize.get()) ring.pollFirst();
        }
    }

    private void dumpRing() {
        List<String> snapshot;
        synchronized (ring) {
            if (ring.isEmpty()) return;
            snapshot = new ArrayList<>(ring);
        }
        try {
            Path file = packetDir().resolve("disconnect-" + FILE_STAMP.format(LocalDateTime.now()) + ".log");
            Files.createDirectories(file.getParent());
            Files.write(file, snapshot);
            info("Dumped %d packets to %s", snapshot.size(), file.getFileName());
        } catch (IOException e) {
            warning("Failed to dump packet buffer: %s", e.getMessage());
        }
    }

    // ── File sink ────────────────────────────────────────────────────────

    private void writeFile(String line) {
        if (writerFailed) return;
        try {
            if (writer == null) {
                Path file = packetDir().resolve("packets.log");
                Files.createDirectories(file.getParent());
                writer = Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            writer.write(line);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            writerFailed = true; // stop hammering chat/disk if the path is unwritable
            warning("Packet log file write failed (disabling file sink): %s", e.getMessage());
        }
    }

    private void closeWriter() {
        if (writer != null) {
            try { writer.close(); } catch (IOException ignored) {}
            writer = null;
        }
    }

    // ── Chat sink (throttled) ────────────────────────────────────────────

    private void chat(String line) {
        long now = System.currentTimeMillis();
        if (now - lastChatMs < chatThrottleMs.get()) return;
        lastChatMs = now;
        info("%s", line);
    }

    private Path packetDir() {
        return FabricLoader.getInstance().getGameDir().resolve("necron").resolve("packets");
    }

    // ── Rubberband file sinks ────────────────────────────────────────────

    private void openCsv() {
        try {
            Path f = packetDir().resolve("rubberband.csv");
            Files.createDirectories(f.getParent());
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
            Path f = packetDir().resolve("rubberband-log.txt");
            Files.createDirectories(f.getParent());
            txtW = Files.newBufferedWriter(f, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            txtW.write("\n=== session " + RB_TIME.format(LocalTime.now()) + " ===\n");
            txtW.flush();
        } catch (IOException e) { txtW = null; warning("rubberband-log.txt open failed: " + e.getMessage()); }
    }

    private void writeEvent(String type, double vBps, double hBps, double tBps, double height,
                            Snap s, double dx, double dy, double dz, double pull) {
        String time = RB_TIME.format(LocalTime.now());

        if (rbChat.get()) {
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

    private void closeRubberbandWriters() {
        if (csvW != null) { try { csvW.flush(); csvW.close(); } catch (IOException ignored) {} csvW = null; }
        if (txtW != null) { try { txtW.flush(); txtW.close(); } catch (IOException ignored) {} txtW = null; }
    }

    // ── Formatting (returns null when the packet's type toggle is off) ────

    private String format(Packet<?> packet) {
        // Movement (four subtypes of PlayerMoveC2SPacket)
        if (packet instanceof PlayerMoveC2SPacket.Full p)                return movePosRot.get()     ? move("PosRot", p) : null;
        if (packet instanceof PlayerMoveC2SPacket.PositionAndOnGround p) return movePos.get()        ? move("Pos", p) : null;
        if (packet instanceof PlayerMoveC2SPacket.LookAndOnGround p)     return moveRot.get()        ? move("Rot", p) : null;
        if (packet instanceof PlayerMoveC2SPacket.OnGroundOnly p)        return moveStatusOnly.get()
            ? String.format("MovePlayer.StatusOnly onGround=%b horizColl=%b", p.isOnGround(), p.horizontalCollision()) : null;

        if (packet instanceof VehicleMoveC2SPacket p) {
            if (!moveVehicle.get()) return null;
            Vec3d v = p.position();
            return String.format("MoveVehicle x=%.3f y=%.3f z=%.3f yaw=%.1f pitch=%.1f onGround=%b",
                v.x, v.y, v.z, p.yaw(), p.pitch(), p.onGround());
        }
        if (packet instanceof PlayerActionC2SPacket p) {
            return playerAction.get() ? String.format("PlayerAction %s pos=%s dir=%s seq=%d",
                p.getAction(), p.getPos().toShortString(), p.getDirection(), p.getSequence()) : null;
        }
        if (packet instanceof UpdateSelectedSlotC2SPacket p) {
            return setCarriedItem.get() ? "SetCarriedItem slot=" + p.getSelectedSlot() : null;
        }
        if (packet instanceof ClickSlotC2SPacket p) {
            return containerClick.get() ? String.format("ContainerClick id=%d slot=%d type=%s",
                p.syncId(), p.slot(), p.actionType()) : null;
        }
        if (packet instanceof PickItemFromBlockC2SPacket)  return pickFromBlock.get()  ? "PickItemFromBlock" : null;
        if (packet instanceof PickItemFromEntityC2SPacket) return pickFromEntity.get() ? "PickItemFromEntity" : null;
        if (packet instanceof HandSwingC2SPacket p) {
            return swing.get() ? "Swing hand=" + p.getHand() : null;
        }
        if (packet instanceof PlayerInteractEntityC2SPacket p) {
            return interact.get() ? "Interact secondary=" + p.isPlayerSneaking() : null;
        }
        if (packet instanceof PlayerInteractBlockC2SPacket)  return useItemOn.get() ? "UseItemOn" : null;
        if (packet instanceof PlayerInteractItemC2SPacket p) {
            return useItem.get() ? String.format("UseItem hand=%s seq=%d", p.getHand(), p.getSequence()) : null;
        }
        if (packet instanceof ClientCommandC2SPacket p) {
            return playerCommand.get() ? String.format("PlayerCommand %s data=%d", p.getMode(), p.getMountJumpHeight()) : null;
        }
        if (packet instanceof ClientStatusC2SPacket p) {
            return clientCommand.get() ? "ClientCommand " + p.getMode() : null;
        }
        if (packet instanceof CloseHandledScreenC2SPacket p) {
            return containerClose.get() ? "ContainerClose id=" + p.getSyncId() : null;
        }
        if (packet instanceof TeleportConfirmC2SPacket p) {
            return acceptTeleport.get() ? "AcceptTeleport id=" + p.getTeleportId() : null;
        }
        if (packet instanceof CommonPongC2SPacket p) {
            return pong.get() ? "Pong id=" + p.getParameter() : null;
        }
        return null;
    }

    private String move(String kind, PlayerMoveC2SPacket p) {
        return String.format("MovePlayer.%s x=%.3f y=%.3f z=%.3f yaw=%.1f pitch=%.1f onGround=%b",
            kind, p.getX(0), p.getY(0), p.getZ(0), p.getYaw(0), p.getPitch(0), p.isOnGround());
    }
}
