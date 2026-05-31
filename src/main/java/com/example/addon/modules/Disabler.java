package com.example.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class Disabler extends Module {

    private final SettingGroup sgGeneral  = settings.getDefaultGroup();
    private final SettingGroup sgMethods  = settings.createGroup("Methods");
    private final SettingGroup sgTiming   = settings.createGroup("Timing");

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
        .description("Only send disabler packets when the player is moving.")
        .defaultValue(true)
        .build());

    private final Setting<Double> minSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-speed")
        .description("Minimum horizontal speed in blocks/sec before activating.")
        .defaultValue(10.0)
        .min(0.0).max(50.0)
        .sliderRange(0.0, 50.0)
        .visible(onlyWhileMoving::get)
        .build());

    // ═══════════════════════════════════════════
    //  METHODS
    // ═══════════════════════════════════════════

    private final Setting<Boolean> sprintToggle = sgMethods.add(new BoolSetting.Builder()
        .name("sprint-toggle")
        .description("Rapidly toggle sprint state to confuse Grim's speed prediction model.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> elytraToggle = sgMethods.add(new BoolSetting.Builder()
        .name("elytra-toggle")
        .description("Send START_FALL_FLYING at intervals to force Grim to swap physics simulations.")
        .defaultValue(true)
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

    private final Setting<Boolean> teleportDesync = sgMethods.add(new BoolSetting.Builder()
        .name("teleport-desync")
        .description("Send stale teleport ACKs to keep Grim's position check window open.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> bookPayload = sgMethods.add(new BoolSetting.Builder()
        .name("book-payload")
        .description("Send a large book packet to stall Grim's processing thread.")
        .defaultValue(false)
        .build());

    // ═══════════════════════════════════════════
    //  TIMING
    // ═══════════════════════════════════════════

    private final Setting<Integer> pulseInterval = sgTiming.add(new IntSetting.Builder()
        .name("pulse-interval")
        .description("Ticks between each disabler pulse.")
        .defaultValue(5)
        .min(1).max(40)
        .sliderRange(1, 40)
        .build());

    private final Setting<Integer> sprintToggleCount = sgTiming.add(new IntSetting.Builder()
        .name("sprint-toggle-count")
        .description("How many START/STOP sprint pairs to send per pulse.")
        .defaultValue(2)
        .min(1).max(5)
        .sliderRange(1, 5)
        .visible(sprintToggle::get)
        .build());

    private final Setting<Integer> bookPages = sgTiming.add(new IntSetting.Builder()
        .name("book-pages")
        .description("Number of max-length pages in the book payload packet.")
        .defaultValue(40)
        .min(1).max(100)
        .sliderRange(1, 100)
        .visible(bookPayload::get)
        .build());

    public enum DisablerMode {
        SPRINT_ONLY,   // Sprint toggle only — safest, lowest packet count
        STATE_RESET,   // Swing + action + slot resets only
        PAYLOAD,       // Book payload stall only
        COMBINED       // All enabled methods together
    }

    // ═══════════════════════════════════════════
    //  STATE
    // ═══════════════════════════════════════════

    private int tickCounter  = 0;
    private int teleportId   = 0;

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
        tickCounter = 0;
        teleportId  = 0;
    }

    // ═══════════════════════════════════════════
    //  TICK
    // ═══════════════════════════════════════════

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        if (onlyWhileMoving.get()) {
            double speed = mc.player.getDeltaMovement().horizontalDistance() * 20.0;
            if (speed < minSpeed.get()) return;
        }

        tickCounter++;
        if (tickCounter < pulseInterval.get()) return;
        tickCounter = 0;

        switch (mode.get()) {
            case SPRINT_ONLY  -> doSprintToggle();
            case STATE_RESET  -> doStateReset();
            case PAYLOAD      -> doBookPayload();
            case COMBINED     -> {
                if (sprintToggle.get())    doSprintToggle();
                if (elytraToggle.get())    doElytraToggle();
                doStateReset();
                if (bookPayload.get())     doBookPayload();
                if (teleportDesync.get())  doTeleportDesync();
            }
        }
    }

    // ═══════════════════════════════════════════
    //  METHOD: SPRINT TOGGLE
    //
    //  Grim uses sprinting state to set the max
    //  allowed speed. Toggling START/STOP rapidly
    //  forces Grim to re-evaluate which speed cap
    //  applies on each movement packet, creating
    //  a window where the higher sprint speed is
    //  accepted for longer than it should be.
    // ═══════════════════════════════════════════

    private void doSprintToggle() {
        if (mc.player == null) return;

        for (int i = 0; i < sprintToggleCount.get(); i++) {
            mc.player.connection.send(new ServerboundPlayerCommandPacket(
                mc.player, ServerboundPlayerCommandPacket.Action.START_SPRINTING));
            mc.player.connection.send(new ServerboundPlayerCommandPacket(
                mc.player, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
        }

        // Always leave sprinting on
        mc.player.connection.send(new ServerboundPlayerCommandPacket(
            mc.player, ServerboundPlayerCommandPacket.Action.START_SPRINTING));
    }

    // ═══════════════════════════════════════════
    //  METHOD: ELYTRA TOGGLE
    //
    //  Grim has two separate physics simulations:
    //  ground and elytra. Sending START_FALL_FLYING
    //  mid-tick forces Grim to swap between them,
    //  leaving a gap where neither simulation's
    //  velocity limits are fully enforced.
    // ═══════════════════════════════════════════

    private void doElytraToggle() {
        if (mc.player == null) return;

        mc.player.connection.send(new ServerboundPlayerCommandPacket(
            mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
    }

    // ═══════════════════════════════════════════
    //  METHOD: STATE RESET
    //
    //  Sends several packets that target different
    //  Grim state trackers, forcing them to reset
    //  their predictions mid-movement.
    // ═══════════════════════════════════════════

    private void doStateReset() {
        if (mc.player == null) return;

        Vec3 pos = mc.player.position();
        BlockPos blockPos = BlockPos.containing(pos);
        BlockPos belowPos = blockPos.below();

        // ── Swing — resets reach/combat prediction ──
        if (swingDesync.get()) {
            mc.player.connection.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        }

        // ── Abort destroy — resets block interaction state ──
        if (actionDesync.get()) {
            mc.player.connection.send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                belowPos,
                Direction.UP,
                0 // sequence number — 0 is safe for abort actions
            ));
        }

        // ── Slot cycle — forces movement modifier recalculation ──
        if (slotDesync.get()) {
            int realSlot = mc.player.getInventory().selected;
            int fakeSlot = (realSlot + 1) % 9;

            mc.player.connection.send(new ServerboundSetCarriedItemPacket(fakeSlot));
            mc.player.connection.send(new ServerboundSetCarriedItemPacket(realSlot));
        }

    }

    // ═══════════════════════════════════════════
    //  METHOD: BOOK PAYLOAD
    //
    //  Grim processes all incoming packets on a
    //  single thread sequentially. A 40-page book
    //  packet takes several milliseconds to parse,
    //  stalling the thread and creating a window
    //  where subsequent movement packets are queued
    //  but not immediately checked.
    // ═══════════════════════════════════════════

    private void doBookPayload() {
        if (mc.player == null) return;

        int slot = mc.player.getInventory().selected;

        List<String> pages = new ArrayList<>();
        String fullPage = "A".repeat(255); // Max characters per page
        for (int i = 0; i < bookPages.get(); i++) {
            pages.add(fullPage);
        }

        mc.player.connection.send(new ServerboundEditBookPacket(
            slot,
            pages,
            Optional.empty() // no title — avoids server-side validation
        ));
    }

    // ═══════════════════════════════════════════
    //  METHOD: TELEPORT DESYNC
    //
    //  Grim queues teleport confirmations and keeps
    //  its position check window open until it
    //  receives a matching ACK. Sending stale IDs
    //  extends this window on the server side.
    // ═══════════════════════════════════════════

    private void doTeleportDesync() {
        if (mc.player == null) return;

        for (int i = 0; i < 3; i++) {
            mc.player.connection.send(new ServerboundAcceptTeleportationPacket(teleportId++));
        }

        // Cap to prevent integer overflow
        if (teleportId > 10_000) teleportId = 0;
    }
}
