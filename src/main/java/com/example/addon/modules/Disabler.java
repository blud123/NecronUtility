package com.example.addon.modules;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.network.packet.s2c.common.KeepAliveS2CPacket;
import net.minecraft.network.packet.s2c.common.CommonPingS2CPacket;
import net.minecraft.network.packet.c2s.common.KeepAliveC2SPacket;
import net.minecraft.network.packet.c2s.common.CommonPongC2SPacket;
import net.minecraft.network.packet.c2s.play.*;
import net.minecraft.util.Hand;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.util.PlayerInput;
import net.minecraft.item.Items;
import net.minecraft.util.math.Vec3d;

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

    private final Setting<Integer> overflowCount = sgTiming.add(new IntSetting.Builder()
        .name("overflow-count")
        .description("Interact/release packet pairs sent per pulse in GRIM_OVERFLOW mode.")
        .defaultValue(20)
        .min(1).max(120)
        .sliderRange(1, 120)
        .visible(() -> mode.get() == DisablerMode.GRIM_OVERFLOW)
        .build());

    public enum DisablerMode {
        SPRINT_ONLY,   // Sprint toggle only — lowest packet count
        TRANSACTION,   // Transaction + keepalive delay only — cleanest, no extra packets
        STATE_RESET,   // State resets only (swing, abort, slot)
        DESYNC,        // Transaction delay + sprint/sneak toggle
        PAYLOAD,       // Book payload stall only
        COMBINED,      // All active methods
        GRIM_TRIDENT,  // Interact-item + release-use desync (Shoreline GRIM_TRIDENT)
        GRIM_FIREWORK, // Interact-item + START_FALL_FLYING + release (Shoreline GRIM_FIREWORK)
        GRIM_OVERFLOW  // Flood interact/release pairs to overflow Grim's packet processing
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
        super(com.example.addon.DWAddons.CATEGORY, "disabler",
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

        if (transactionDelay.get() && event.packet instanceof CommonPingS2CPacket ping) {
            event.cancel();
            pendingPings.add(new long[]{ping.getParameter(), tick + pingDelayTicks.get()});
        }

        if (keepalivePause.get() && event.packet instanceof KeepAliveS2CPacket keepalive) {
            event.cancel();
            pendingKeepAlives.add(new long[]{keepalive.getId(), tick + keepaliveDelayTicks.get()});
        }
    }

    // ═══════════════════════════════════════════
    //  TICK
    // ═══════════════════════════════════════════

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        tick++;

        // Flush delayed responses — always runs regardless of mode or speed gate
        flushPendingPings(tick);
        flushPendingKeepAlives(tick);

        // Elytra assertion — every tick, only if elytra actually equipped
        if (elytraToggle.get() && mc.player.getEquippedStack(EquipmentSlot.CHEST).getItem() == Items.ELYTRA) {
            mc.player.networkHandler.sendPacket(new ClientCommandC2SPacket(
                mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
        }

        // Speed gate for pulse methods
        if (onlyWhileMoving.get()) {
            double speed = mc.player.getVelocity().horizontalLength() * 20.0;
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
            case GRIM_TRIDENT  -> doGrimTrident();
            case GRIM_FIREWORK -> doGrimFirework();
            case GRIM_OVERFLOW -> doGrimOverflow();
        }

        if (groundSpoof.get()) doGroundSpoof();
    }

    // ═══════════════════════════════════════════
    //  FLUSH HELPERS
    // ═══════════════════════════════════════════

    private void flushPendingPings(long currentTick) {
        if (mc.player == null) return;
        while (!pendingPings.isEmpty() && pendingPings.peek()[1] <= currentTick) {
            mc.player.networkHandler.sendPacket(new CommonPongC2SPacket((int) pendingPings.poll()[0]));
        }
    }

    private void flushPendingKeepAlives(long currentTick) {
        if (mc.player == null) return;
        while (!pendingKeepAlives.isEmpty() && pendingKeepAlives.peek()[1] <= currentTick) {
            mc.player.networkHandler.sendPacket(new KeepAliveC2SPacket(pendingKeepAlives.poll()[0]));
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
            mc.player.networkHandler.sendPacket(new ClientCommandC2SPacket(
                mc.player, ClientCommandC2SPacket.Mode.START_SPRINTING));
            mc.player.networkHandler.sendPacket(new ClientCommandC2SPacket(
                mc.player, ClientCommandC2SPacket.Mode.STOP_SPRINTING));
        }
        // Always leave sprinting on so normal movement isn't affected
        mc.player.networkHandler.sendPacket(new ClientCommandC2SPacket(
            mc.player, ClientCommandC2SPacket.Mode.START_SPRINTING));
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
            mc.player.networkHandler.sendPacket(new PlayerInputC2SPacket(
                new PlayerInput(false, false, false, false, false, true, sprinting)));
            mc.player.networkHandler.sendPacket(new PlayerInputC2SPacket(
                new PlayerInput(false, false, false, false, false, false, sprinting)));
        }
        // Always end uncrouched so movement feels normal
        mc.player.networkHandler.sendPacket(new PlayerInputC2SPacket(
            new PlayerInput(false, false, false, false, false, false, sprinting)));
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
        mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.OnGroundOnly(
            !mc.player.isOnGround(), mc.player.horizontalCollision));
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

        Vec3d pos = mc.player.getPos();
        BlockPos belowPos = BlockPos.ofFloored(pos).down();

        if (swingDesync.get()) {
            mc.player.networkHandler.sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
        }

        if (actionDesync.get()) {
            mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK,
                belowPos, Direction.UP, 0));
        }

        if (slotDesync.get()) {
            int realSlot = mc.player.getInventory().getSelectedSlot();
            int fakeSlot = (realSlot + 1) % 9;
            mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(fakeSlot));
            mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(realSlot));
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
        mc.player.networkHandler.sendPacket(new BookUpdateC2SPacket(slot, pages, Optional.empty()));
    }

    // ═══════════════════════════════════════════
    //  GRIM EXPLOIT METHODS (Shoreline DisablerModule)
    //
    //  These port Shoreline's GRIM_TRIDENT / GRIM_FIREWORK / GRIM_OVERFLOW strategies into Mojang
    //  mappings. They build on ServerboundUseItemPacket (interact-item) + a RELEASE_USE_ITEM player
    //  action, plus START_FALL_FLYING for the firework variant. The server treats UseItem as the
    //  start of an item use and RELEASE_USE_ITEM as its end; firing them back-to-back (and flooding
    //  them, for overflow) desyncs Grim's item-use / elytra-boost state.
    //
    //  ⚠ Exploit-specific and version/anticheat-dependent. These mirror Shoreline's handling and may
    //  already be patched by the current Grim build — implemented faithfully, NOT guaranteed to work.
    // ═══════════════════════════════════════════

    private void doGrimTrident() {
        if (mc.player == null) return;
        // Begin then immediately end a main-hand item use to whiplash Grim's use-item tracker.
        sendUseItem(Hand.MAIN_HAND);
        sendReleaseUse();
    }

    private void doGrimFirework() {
        if (mc.player == null) return;
        // Interact-item (rocket), assert elytra flight, then release — desyncs Grim's boost handling.
        sendUseItem(Hand.MAIN_HAND);
        mc.player.networkHandler.sendPacket(new ClientCommandC2SPacket(
            mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
        sendReleaseUse();
        mc.player.networkHandler.sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
    }

    private void doGrimOverflow() {
        if (mc.player == null) return;
        // Flood interact/release pairs so Grim's single-threaded packet processing falls behind.
        int n = overflowCount.get();
        for (int i = 0; i < n; i++) {
            sendUseItem(Hand.MAIN_HAND);
            sendReleaseUse();
        }
    }

    // Sequence 0 is intentional: the server uses it only to ack predicted block changes, which we
    // don't care about here — the desync comes from the use/release pairing, not the sequence.
    private void sendUseItem(Hand hand) {
        if (mc.player == null) return;
        mc.player.networkHandler.sendPacket(new PlayerInteractItemC2SPacket(
            hand, 0, mc.player.getYaw(), mc.player.getPitch()));
    }

    private void sendReleaseUse() {
        if (mc.player == null) return;
        // Vanilla releases item use with pos ZERO / facing DOWN; the server ignores the coordinates
        // for RELEASE_USE_ITEM and only acts on the action itself.
        mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.RELEASE_USE_ITEM, BlockPos.ORIGIN, Direction.DOWN));
    }
}
