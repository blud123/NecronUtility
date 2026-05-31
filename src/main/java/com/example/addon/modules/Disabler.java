package com.example.addon.modules;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class Disabler extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgMethods = settings.createGroup("Methods");
    private final SettingGroup sgTiming  = settings.createGroup("Timing");

    // ═══════════════════════════════════════════
    //  GENERAL
    // ═══════════════════════════════════════════

    private final Setting<DisablerMode> mode = sgGeneral.add(new EnumSetting.Builder<DisablerMode>()
        .name("mode")
        .description("Which Grim disabler strategy to run.")
        .defaultValue(DisablerMode.COMBINED)
        .build());

    private final Setting<Boolean> onlyWhileMoving = sgGeneral.add(new BoolSetting.Builder()
        .name("only-while-moving")
        .description("Only apply pulse methods when the player is moving. Transaction delay always runs.")
        .defaultValue(false)
        .build());

    private final Setting<Double> minSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-speed")
        .description("Minimum horizontal speed (blocks/sec) before pulse methods activate.")
        .defaultValue(4.0)
        .min(0.0).max(50.0)
        .sliderRange(0.0, 50.0)
        .visible(onlyWhileMoving::get)
        .build());

    // ═══════════════════════════════════════════
    //  METHODS
    // ═══════════════════════════════════════════

    private final Setting<Boolean> transactionDelay = sgMethods.add(new BoolSetting.Builder()
        .name("transaction-delay")
        .description("Intercept Grim's ping packets and hold the pong response, inflating measured latency so Grim's lag-comp window stays open wider.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> keepalivePause = sgMethods.add(new BoolSetting.Builder()
        .name("keepalive-delay")
        .description("Intercept keepalive packets and delay responses to further inflate Grim's latency measurement. Keep delay-ticks below 500 to avoid timeout.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> sprintToggle = sgMethods.add(new BoolSetting.Builder()
        .name("sprint-toggle")
        .description("Rapidly toggle sprint to confuse Grim's speed prediction model.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> sneakToggle = sgMethods.add(new BoolSetting.Builder()
        .name("sneak-toggle")
        .description("Rapidly toggle sneak to force Grim to recalculate hitbox height and speed cap boundaries.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> groundSpoof = sgMethods.add(new BoolSetting.Builder()
        .name("ground-spoof")
        .description("Send a status-only packet with inverted on-ground flag each pulse to desync Grim's landing simulation.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> swingDesync = sgMethods.add(new BoolSetting.Builder()
        .name("swing-desync")
        .description("Send swing packets to reset Grim's reach/combat prediction state.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> actionDesync = sgMethods.add(new BoolSetting.Builder()
        .name("action-desync")
        .description("Send ABORT_DESTROY_BLOCK to reset Grim's block interaction state.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> slotDesync = sgMethods.add(new BoolSetting.Builder()
        .name("slot-desync")
        .description("Cycle selected slot to force Grim to recalculate movement modifiers.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> elytraToggle = sgMethods.add(new BoolSetting.Builder()
        .name("elytra-toggle")
        .description("Assert START_FALL_FLYING every tick to hold Grim in elytra physics mode. Requires elytra in chest slot.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> bookPayload = sgMethods.add(new BoolSetting.Builder()
        .name("book-payload")
        .description("Periodically send a large book packet to stall Grim's processing thread.")
        .defaultValue(false)
        .build());

    // ═══════════════════════════════════════════
    //  TIMING
    // ═══════════════════════════════════════════

    private final Setting<Integer> pingDelayTicks = sgTiming.add(new IntSetting.Builder()
        .name("ping-delay-ticks")
        .description("Ticks to hold pong responses before sending. 8 ticks ≈ 400ms extra perceived latency for Grim.")
        .defaultValue(8)
        .min(1).max(200)
        .sliderRange(1, 200)
        .visible(transactionDelay::get)
        .build());

    private final Setting<Integer> keepaliveDelayTicks = sgTiming.add(new IntSetting.Builder()
        .name("keepalive-delay-ticks")
        .description("Ticks to hold keepalive responses. Keep below 500 ticks (25s) to avoid disconnect.")
        .defaultValue(20)
        .min(1).max(400)
        .sliderRange(1, 400)
        .visible(keepalivePause::get)
        .build());

    private final Setting<Integer> pulseInterval = sgTiming.add(new IntSetting.Builder()
        .name("pulse-interval")
        .description("Ticks between disabler pulses (sprint/sneak/state/book methods).")
        .defaultValue(3)
        .min(1).max(40)
        .sliderRange(1, 40)
        .build());

    private final Setting<Integer> sprintToggleCount = sgTiming.add(new IntSetting.Builder()
        .name("sprint-toggle-count")
        .description("START/STOP sprint pairs sent per pulse.")
        .defaultValue(2)
        .min(1).max(5)
        .sliderRange(1, 5)
        .visible(sprintToggle::get)
        .build());

    private final Setting<Integer> sneakToggleCount = sgTiming.add(new IntSetting.Builder()
        .name("sneak-toggle-count")
        .description("START/STOP sneak pairs sent per pulse.")
        .defaultValue(2)
        .min(1).max(5)
        .sliderRange(1, 5)
        .visible(sneakToggle::get)
        .build());

    private final Setting<Integer> bookPages = sgTiming.add(new IntSetting.Builder()
        .name("book-pages")
        .description("Max-length pages in the book payload packet.")
        .defaultValue(40)
        .min(1).max(100)
        .sliderRange(1, 100)
        .visible(bookPayload::get)
        .build());

    public enum DisablerMode {
        SPRINT_ONLY,  // Sprint toggle only — lowest packet count
        TRANSACTION,  // Transaction + keepalive delay only — cleanest, no extra packets
        STATE_RESET,  // State resets only (swing, abort, slot)
        DESYNC,       // Transaction delay + sprint/sneak toggle
        PAYLOAD,      // Book payload stall only
        COMBINED      // All active methods
    }

    // ═══════════════════════════════════════════
    //  STATE
    // ═══════════════════════════════════════════

    private long tick = 0;
    private int  pulseCounter = 0;

    // Each entry: {id, releaseTick}
    private final ArrayDeque<long[]> pendingPings      = new ArrayDeque<>();
    private final ArrayDeque<long[]> pendingKeepAlives = new ArrayDeque<>();

    // ═══════════════════════════════════════════
    //  CONSTRUCTOR
    // ═══════════════════════════════════════════

    public Disabler() {
        super(com.example.addon.AddonTemplate.CATEGORY, "disabler",
              "Disrupts Grim AntiCheat state tracking on 2b2t.");
    }

    // ═══════════════════════════════════════════
    //  LIFECYCLE
    // ═══════════════════════════════════════════

    @Override
    public void onActivate() {
        tick = 0;
        pulseCounter = 0;
        pendingPings.clear();
        pendingKeepAlives.clear();
    }

    @Override
    public void onDeactivate() {
        // Flush all held packets so the connection doesn't stay desynced after disable
        if (mc.player != null) {
            flushPendingPings(Long.MAX_VALUE);
            flushPendingKeepAlives(Long.MAX_VALUE);
        }
        pendingPings.clear();
        pendingKeepAlives.clear();
    }

    // ═══════════════════════════════════════════
    //  PACKET INTERCEPTION
    //
    //  Grim sends ClientboundPingPacket before each
    //  movement batch and measures round-trip time
    //  to size its lag-compensation window. By
    //  holding the ServerboundPongPacket response,
    //  we inflate Grim's measured latency, forcing
    //  it to accept positions over a wider window.
    //  Keepalive delay compounds this effect.
    // ═══════════════════════════════════════════

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.player == null) return;

        if (transactionDelay.get() && event.packet instanceof ClientboundPingPacket ping) {
            event.cancel();
            pendingPings.add(new long[]{ping.getId(), tick + pingDelayTicks.get()});
        }

        if (keepalivePause.get() && event.packet instanceof ClientboundKeepAlivePacket keepalive) {
            event.cancel();
            pendingKeepAlives.add(new long[]{keepalive.getId(), tick + keepaliveDelayTicks.get()});
        }
    }

    // ═══════════════════════════════════════════
    //  TICK
    // ═══════════════════════════════════════════

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;
        tick++;

        // Flush delayed responses — always runs regardless of mode or speed gate
        flushPendingPings(tick);
        flushPendingKeepAlives(tick);

        // Elytra assertion — every tick, only if elytra actually equipped
        if (elytraToggle.get() && mc.player.getItemBySlot(EquipmentSlot.CHEST).getItem() == Items.ELYTRA) {
            mc.player.connection.send(new ServerboundPlayerCommandPacket(
                mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
        }

        // Speed gate for pulse methods
        if (onlyWhileMoving.get()) {
            double speed = mc.player.getDeltaMovement().horizontalDistance() * 20.0;
            if (speed < minSpeed.get()) return;
        }

        pulseCounter++;
        if (pulseCounter < pulseInterval.get()) return;
        pulseCounter = 0;

        switch (mode.get()) {
            case SPRINT_ONLY -> { if (sprintToggle.get()) doSprintToggle(); }
            case TRANSACTION -> {}  // handled entirely by packet interception above
            case STATE_RESET -> doStateReset();
            case PAYLOAD     -> { if (bookPayload.get()) doBookPayload(); }
            case DESYNC      -> {
                if (sprintToggle.get()) doSprintToggle();
                if (sneakToggle.get())  doSneakToggle();
            }
            case COMBINED -> {
                if (sprintToggle.get()) doSprintToggle();
                if (sneakToggle.get())  doSneakToggle();
                doStateReset();
                if (bookPayload.get())  doBookPayload();
            }
        }

        if (groundSpoof.get()) doGroundSpoof();
    }

    // ═══════════════════════════════════════════
    //  FLUSH HELPERS
    // ═══════════════════════════════════════════

    private void flushPendingPings(long currentTick) {
        if (mc.player == null) return;
        while (!pendingPings.isEmpty() && pendingPings.peek()[1] <= currentTick) {
            mc.player.connection.send(new ServerboundPongPacket((int) pendingPings.poll()[0]));
        }
    }

    private void flushPendingKeepAlives(long currentTick) {
        if (mc.player == null) return;
        while (!pendingKeepAlives.isEmpty() && pendingKeepAlives.peek()[1] <= currentTick) {
            mc.player.connection.send(new ServerboundKeepAlivePacket(pendingKeepAlives.poll()[0]));
        }
    }

    // ═══════════════════════════════════════════
    //  METHOD: SPRINT TOGGLE
    //
    //  Grim uses sprinting state to set the max
    //  allowed speed. Toggling START/STOP rapidly
    //  forces Grim to re-evaluate which speed cap
    //  applies, creating a window where the higher
    //  sprint limit is accepted for longer.
    // ═══════════════════════════════════════════

    private void doSprintToggle() {
        if (mc.player == null) return;
        for (int i = 0; i < sprintToggleCount.get(); i++) {
            mc.player.connection.send(new ServerboundPlayerCommandPacket(
                mc.player, ServerboundPlayerCommandPacket.Action.START_SPRINTING));
            mc.player.connection.send(new ServerboundPlayerCommandPacket(
                mc.player, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
        }
        // Always leave sprinting on so normal movement isn't affected
        mc.player.connection.send(new ServerboundPlayerCommandPacket(
            mc.player, ServerboundPlayerCommandPacket.Action.START_SPRINTING));
    }

    // ═══════════════════════════════════════════
    //  METHOD: SNEAK TOGGLE
    //
    //  Sneaking changes the hitbox height (1.8→1.5)
    //  and lowers Grim's speed cap. Cycling
    //  START/STOP_SNEAKING rapidly forces Grim to
    //  recalculate the cap boundary, briefly
    //  applying the standing limit while the
    //  crouched hitbox is still tracked.
    // ═══════════════════════════════════════════

    private void doSneakToggle() {
        if (mc.player == null) return;
        boolean sprinting = mc.player.isSprinting();
        for (int i = 0; i < sneakToggleCount.get(); i++) {
            mc.player.connection.send(new ServerboundPlayerInputPacket(
                new Input(false, false, false, false, false, true, sprinting)));
            mc.player.connection.send(new ServerboundPlayerInputPacket(
                new Input(false, false, false, false, false, false, sprinting)));
        }
        // Always end uncrouched so movement feels normal
        mc.player.connection.send(new ServerboundPlayerInputPacket(
            new Input(false, false, false, false, false, false, sprinting)));
    }

    // ═══════════════════════════════════════════
    //  METHOD: GROUND SPOOF
    //
    //  Sends a status packet with an inverted
    //  on-ground flag. Grim's ground tracker and
    //  its landing/fall simulation must reconcile
    //  the contradiction, causing it to briefly
    //  accept positions it would otherwise flag.
    // ═══════════════════════════════════════════

    private void doGroundSpoof() {
        if (mc.player == null) return;
        mc.player.connection.send(new ServerboundMovePlayerPacket.StatusOnly(
            !mc.player.onGround(), mc.player.horizontalCollision));
    }

    // ═══════════════════════════════════════════
    //  METHOD: STATE RESET
    //
    //  Several packets that each target a different
    //  Grim state tracker, forcing mid-movement
    //  resets across multiple prediction systems.
    // ═══════════════════════════════════════════

    private void doStateReset() {
        if (mc.player == null) return;

        Vec3 pos = mc.player.position();
        BlockPos belowPos = BlockPos.containing(pos).below();

        if (swingDesync.get()) {
            mc.player.connection.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        }

        if (actionDesync.get()) {
            mc.player.connection.send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                belowPos, Direction.UP, 0));
        }

        if (slotDesync.get()) {
            int realSlot = mc.player.getInventory().getSelectedSlot();
            int fakeSlot = (realSlot + 1) % 9;
            mc.player.connection.send(new ServerboundSetCarriedItemPacket(fakeSlot));
            mc.player.connection.send(new ServerboundSetCarriedItemPacket(realSlot));
        }
    }

    // ═══════════════════════════════════════════
    //  METHOD: BOOK PAYLOAD
    //
    //  Grim processes all packets sequentially on
    //  one thread. A large book packet takes several
    //  milliseconds to parse, stalling the thread
    //  and queuing subsequent movement packets so
    //  they're checked late.
    // ═══════════════════════════════════════════

    private void doBookPayload() {
        if (mc.player == null) return;

        int slot = mc.player.getInventory().getSelectedSlot();
        List<String> pages = new ArrayList<>();
        String fullPage = "A".repeat(255);
        for (int i = 0; i < bookPages.get(); i++) {
            pages.add(fullPage);
        }
        mc.player.connection.send(new ServerboundEditBookPacket(slot, pages, Optional.empty()));
    }
}
