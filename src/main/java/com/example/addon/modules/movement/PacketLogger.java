package com.example.addon.modules.movement;

import com.example.addon.DWAddons;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.common.CommonPongC2SPacket;
import net.minecraft.network.packet.c2s.play.*;
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

/**
 * Per-packet-type outbound logger (reference: Shoreline {@code PacketLoggerModule}). Unlike the
 * sibling {@link PacketTracker} — which is a throughput/rubberband <em>diagnostic</em> — this logs
 * individual C2S packets to chat, a file, and an in-memory ring buffer that is dumped on disconnect
 * so you can see exactly what was sent right before a kick.
 *
 * <p>Each listed packet type has its own toggle (all off by default). Only enabled types are
 * logged. Sinks are independent: {@code log-file}, {@code log-chat} (throttled to avoid flooding
 * chat with movement packets), and {@code log-disconnect} (the ring-buffer dump).
 */
public class PacketLogger extends Module {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    // ── Packet-type toggles ──────────────────────────────────────────────
    private final SettingGroup sgTypes  = settings.getDefaultGroup();
    private final SettingGroup sgOutput = settings.createGroup("Output");

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

    // ── State ────────────────────────────────────────────────────────────
    private final Deque<String> ring = new ArrayDeque<>();
    private BufferedWriter writer;
    private boolean writerFailed = false;
    private long lastChatMs = 0L;

    public PacketLogger() {
        super(DWAddons.CATEGORY, "packet-logger",
            "Logs individual C2S packets to chat/file, with a ring buffer dumped on disconnect.");
    }

    @Override
    public void onActivate() {
        synchronized (ring) { ring.clear(); }
        writerFailed = false;
        lastChatMs = 0L;
    }

    @Override
    public void onDeactivate() {
        closeWriter();
    }

    // ── Outgoing packets ─────────────────────────────────────────────────

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        if (!logFile.get() && !logChat.get() && !logDisconnect.get()) return;

        String body = format(event.packet);
        if (body == null) return; // type toggle off, or unhandled type

        String line = "[" + TIME.format(LocalTime.now()) + "] " + body;

        if (logDisconnect.get()) pushRing(line);
        if (logFile.get()) writeFile(line);
        if (logChat.get()) chat(line);
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (logDisconnect.get()) dumpRing();
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
