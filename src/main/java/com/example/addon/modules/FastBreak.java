package com.example.addon.modules;

import meteordevelopment.meteorclient.events.entity.player.StartBreakingBlockEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.block.BlockState;

import net.minecraft.client.MinecraftClient;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Takes over vanilla block breaking with packet-level control and automatic silent tool
 * selection (see {@link com.example.addon.mixin.SilentSwapMixin}).
 *
 * <p>It does NOT pick blocks itself — it reacts to {@link StartBreakingBlockEvent}, the event
 * Meteor fires whenever a block is left-clicked (manually, or by another module such as Nuker
 * that drives blocks through {@code MultiPlayerGameMode.startDestroyBlock}). When the event
 * fires, FastBreak cancels vanilla breaking and handles the break itself.
 *
 * <h2>Protocol note (Mojang mappings, 1.21.8)</h2>
 * There is no {@code FINISH_DESTROY_BLOCK} action. Vanilla uses {@code STOP_DESTROY_BLOCK}
 * both to <em>complete</em> a break and to <em>hand off</em> a block to the server's single
 * "delayed destroy" slot (which keeps breaking it independently). {@code ServerPlayerGameMode}
 * tracks exactly one active destroy plus one delayed-destroy slot, so at most two blocks can be
 * packet-mined at once — that is what {@code max-blocks} exposes.
 *
 * <h2>Tool sync</h2>
 * The server recomputes {@code getDestroyProgress} every tick from whatever slot it believes you
 * hold, so the tool must be synced to the server for the WHOLE break, not just near the finish.
 * We send {@code ServerboundSetCarriedItemPacket} the moment a break starts and hold that server
 * slot until every break finishes, then restore the real slot.
 */
public class FastBreak extends Module {

    // ── Silent swap state (read by SilentSwapMixin) ──────────────────────
    private static int silentSlot = -1;
    public static int getSilentSlot() { return silentSlot; }

    // Tracks the slot the server currently believes is held, so we only send a
    // SetCarriedItem packet when it actually changes.
    private int lastServerSlot = -1;

    // Real (visually-held) selected slot last tick, for switch-reset detection. Distinct from
    // lastServerSlot, which tracks the silently-synced tool slot.
    private int lastRealSlot = -1;

    // Wall-clock time of the last finishing STOP_DESTROY_BLOCK, for the Grim inter-break delay.
    private long lastFinishMs = 0L;

    // ── Settings ─────────────────────────────────────────────────────────
    private final SettingGroup sg       = settings.getDefaultGroup();
    private final SettingGroup sgGrim   = settings.createGroup("Grim");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Integer> maxBlocks = sg.add(new IntSetting.Builder()
        .name("max-blocks")
        .description("Max simultaneous packet breaks. 1 = primary only; 2 = allow the primary→delayed-slot demotion. The server only tracks one active + one delayed destroy, so 3+ are rejected.")
        .defaultValue(2)
        .min(1).max(2)
        .sliderRange(1, 2)
        .build());

    private final Setting<Boolean> swapTool = sg.add(new BoolSetting.Builder()
        .name("swap-tool")
        .description("Silently swap to the best tool in your hotbar for each block.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> rotate = sg.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotate to the block face before sending the finishing packet. Mostly helps under Grim.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> multitask = sg.add(new BoolSetting.Builder()
        .name("multitask")
        .description("Keep breaking while using an item (eating, blocking, drawing a bow). Off = pause breaks while using.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> switchReset = sg.add(new BoolSetting.Builder()
        .name("switch-reset")
        .description("Restart in-progress break progress if you change your real selected hotbar slot mid-break.")
        .defaultValue(false)
        .build());

    // ── Grim group ───────────────────────────────────────────────────────
    // Anticheat behaviour drifts over time: these mirror Shoreline's 2.x Grim FastBreak handling
    // and may need revalidation against the current Grim build. Not guaranteed to bypass anything.
    private final Setting<Boolean> grim = sgGrim.add(new BoolSetting.Builder()
        .name("grim")
        .description("Use Grim-safe block-break packet timing (delayed finishes + a START/ABORT/STOP/swing sequence).")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> grimV3 = sgGrim.add(new BoolSetting.Builder()
        .name("grim-v3")
        .description("Use the newer Grim packet sequence on each block start.")
        .defaultValue(false)
        .visible(grim::get)
        .build());

    private final Setting<Integer> breakDelayMs = sgGrim.add(new IntSetting.Builder()
        .name("break-delay-ms")
        .description("Minimum milliseconds between consecutive break finishes while Grim mode is on.")
        .defaultValue(300)
        .min(0).max(500)
        .sliderRange(0, 500)
        .visible(grim::get)
        .build());

    private final Setting<Boolean> renderBreak = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Render an expanding cube on blocks being mined.")
        .defaultValue(true)
        .build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How to render the break overlay.")
        .defaultValue(ShapeMode.Both)
        .build());

    private final Setting<SettingColor> fillColor = sgRender.add(new ColorSetting.Builder()
        .name("fill-color")
        .description("Fill color of the break overlay.")
        .defaultValue(new SettingColor(0, 180, 255, 50))
        .build());

    private final Setting<SettingColor> outlineColor = sgRender.add(new ColorSetting.Builder()
        .name("outline-color")
        .description("Outline color of the break overlay.")
        .defaultValue(new SettingColor(0, 180, 255, 255))
        .build());

    private final Setting<Boolean> rainbow = sgRender.add(new BoolSetting.Builder()
        .name("rainbow")
        .description("Cycle through rainbow colors.")
        .defaultValue(false)
        .build());

    private final Setting<Double> rainbowSpeed = sgRender.add(new DoubleSetting.Builder()
        .name("rainbow-speed")
        .description("Speed of the rainbow color cycle.")
        .defaultValue(1.0)
        .min(0.1).max(5.0)
        .sliderRange(0.1, 5.0)
        .visible(rainbow::get)
        .build());

    // ── Break tracking ────────────────────────────────────────────────────

    /** State for one actively-breaking block. */
    private static class MineTarget {
        BlockPos pos;
        Direction face;
        int breakingTicks;
        boolean secondary;      // true = demoted by a new primary; server breaks it independently

        // Render/timing estimate. estTicks is the number of ticks the break is expected to take;
        // it doubles as the finish threshold. startMs anchors the smooth time-based render.
        long startMs;
        int estTicks = 1;
        int estSlot = Integer.MIN_VALUE; // slot estTicks was last computed for; sentinel = uncomputed

        MineTarget(BlockPos pos, Direction face, boolean secondary) {
            this.pos = pos;
            this.face = face;
            this.secondary = secondary;
            this.startMs = System.currentTimeMillis();
        }
    }

    private MineTarget primary   = null;
    private MineTarget secondary = null;

    // ── Constructor ───────────────────────────────────────────────────────

    public FastBreak() {
        super(
            com.example.addon.DWAddons.CATEGORY,
            "fast-break",
            "Break blocks faster using packet-level control with automatic tool selection. Works with Nuker."
        );
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override
    public void onActivate() {
        primary   = null;
        secondary = null;
        silentSlot = -1;
        lastServerSlot = mc.player != null ? mc.player.getInventory().getSelectedSlot() : -1;
        lastRealSlot   = lastServerSlot;
        lastFinishMs   = 0L;
    }

    @Override
    public void onDeactivate() {
        if (mc.player != null) {
            if (primary   != null) sendAbort(primary);
            if (secondary != null) sendAbort(secondary);
            restoreSlot();
        }
        primary   = null;
        secondary = null;
        silentSlot = -1;
    }

    // ── Start breaking — triggered by player click AND by Nuker ───────────

    @EventHandler
    private void onStartBreaking(StartBreakingBlockEvent event) {
        if (mc.player == null || mc.world == null) return;
        beginBreak(event.blockPos, event.direction);
        // Always swallow the vanilla break — we take it over whether or not a new target started.
        event.setCancelled(true);
    }

    /**
     * Public entry point so other modules (e.g. {@link Nuker}) can drive a block through the same
     * break pipeline instead of duplicating the packet/slot logic. Feeding the same position each
     * tick is a no-op once it is already being tracked, exactly like a held left-click.
     */
    public void requestBreak(BlockPos pos, Direction face) {
        if (!isActive() || mc.player == null || mc.world == null) return;
        beginBreak(pos, face);
    }

    /**
     * Instant one-shot break (START + finishing STOP) routed through FastBreak's tool sync — the
     * "packet-mine" fast path for blocks weak enough to break in a single hit. Does not occupy a
     * primary/secondary slot. Used by {@link Nuker} so it never sends its own break packets.
     */
    public void packetBreak(BlockPos pos, Direction face) {
        if (!isActive() || mc.player == null || mc.world == null) return;
        BlockState state = mc.world.getBlockState(pos);
        if (state.isAir()) return;
        syncToolFor(state, pos);
        MineTarget t = new MineTarget(pos, face, false);
        send(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, t);
        sendFinish(t);
    }

    /**
     * Pre-syncs the best tool for a block to the server without starting a break — lets a caller
     * implement "swap before start" by syncing the tool a step ahead of the START packet. Reuses
     * the same {@link #syncServerSlot} path, so there is still only one server-slot owner.
     */
    public void syncToolFor(BlockState state, BlockPos pos) {
        if (!isActive() || mc.player == null) return;
        int bestSlot = swapTool.get() ? bestToolSlot(state, pos) : -1;
        syncServerSlot(bestSlot != -1 ? bestSlot : mc.player.getInventory().getSelectedSlot());
    }

    /**
     * The tool slot FastBreak would actually hold for this block ({@code -1} = keep current),
     * honoring the swap-tool setting. Lets callers (e.g. {@link Nuker}) estimate break speed with
     * the exact slot FastBreak will use, so their predictions can't disagree with the real break.
     */
    public int effectiveToolSlot(BlockState state, BlockPos pos) {
        return swapTool.get() ? bestToolSlot(state, pos) : -1;
    }

    /** Aborts an in-progress break for {@code pos} if FastBreak is tracking it (used by Nuker on eviction). */
    public void cancelBreak(BlockPos pos) {
        if (mc.player == null) return;
        if (primary != null && primary.pos.equals(pos)) {
            sendAbort(primary);
            primary = null;
        } else if (secondary != null && secondary.pos.equals(pos)) {
            sendAbort(secondary);
            secondary = null;
        }
    }

    /** Starts (or demotes/replaces) a packet break for {@code pos}. Shared by the click event and Nuker. */
    private void beginBreak(BlockPos pos, Direction face) {
        BlockState state = mc.world.getBlockState(pos);
        if (state.isAir()) return;

        // Already tracking this block — nothing to do.
        if ((primary != null && primary.pos.equals(pos)) || (secondary != null && secondary.pos.equals(pos))) {
            return;
        }

        if (primary != null) {
            if (maxBlocks.get() >= 2 && secondary == null) {
                // Demote the current primary to secondary: STOP_DESTROY_BLOCK hands it off to
                // the server's delayed-destroy slot, so it keeps breaking independently.
                secondary = new MineTarget(primary.pos, primary.face, true);
                secondary.breakingTicks = primary.breakingTicks;
                secondary.startMs  = primary.startMs;
                secondary.estTicks = primary.estTicks;
                secondary.estSlot  = primary.estSlot;
                sendStop(secondary);
            } else {
                // No room for another simultaneous break — drop the old primary.
                sendAbort(primary);
                primary = null;
            }
        }

        primary = new MineTarget(pos, face, false);
        sendStart(primary);
        // Sync the tool to the server at the moment the break starts and hold it for the whole break.
        int bestSlot = swapTool.get() ? bestToolSlot(state, pos) : -1;
        syncServerSlot(bestSlot != -1 ? bestSlot : mc.player.getInventory().getSelectedSlot());
    }

    // ── Per-tick progress update ──────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // Switch-reset: if the real held slot changed this tick (silentSlot is -1 here, so the getter
        // returns the genuine selected slot), restart progress so timing reflects the new tool.
        int realSlot = mc.player.getInventory().getSelectedSlot();
        if (switchReset.get() && lastRealSlot != -1 && realSlot != lastRealSlot) {
            resetProgress(primary);
            resetProgress(secondary);
        }
        lastRealSlot = realSlot;

        // Process secondary first so the primary (the active destroy slot) wins the held server
        // slot for this tick — that's the break the server is actively timing.
        if (secondary != null) secondary = tickTarget(secondary);
        if (primary   != null) primary   = tickTarget(primary);

        // Once nothing is breaking, hand the real slot back to the server.
        if (primary == null && secondary == null) restoreSlot();
    }

    /** Restarts a target's break progress (used by switch-reset). */
    private void resetProgress(MineTarget t) {
        if (t == null) return;
        t.breakingTicks = 0;
        t.startMs = System.currentTimeMillis();
        t.estSlot = Integer.MIN_VALUE; // force estTicks recompute for the new tool
    }

    /**
     * Advances one tick of breaking for a target. Returns {@code null} when the target finished
     * or became invalid, otherwise the (mutated) target.
     */
    private MineTarget tickTarget(MineTarget target) {
        if (mc.player == null || mc.world == null) return null;

        BlockState state = mc.world.getBlockState(target.pos);
        if (state.isAir()) return null; // already gone

        // Multitask gate: unless enabled, don't progress or finish a break while using an item
        // (eating, blocking, drawing a bow). Hold the target so it resumes when the use ends.
        if (!multitask.get() && mc.player.isUsingItem()) return target;

        int bestSlot = swapTool.get() ? bestToolSlot(state, target.pos) : -1;

        // Compute the per-tick break fraction with the best tool silently active (client-side only).
        float breakDelta = getBreakDelta(state, target.pos, bestSlot);
        if (breakDelta <= 0) return target; // unbreakable with this tool

        // Lock the tick estimate at start; recompute only if the tool/state changes.
        if (target.estSlot != bestSlot) {
            target.estTicks = Math.max(1, (int) Math.ceil(1.0f / breakDelta));
            target.estSlot  = bestSlot;
        }

        // Hold the tool on the server for the WHOLE break (syncServerSlot only sends on change).
        syncServerSlot(bestSlot != -1 ? bestSlot : mc.player.getInventory().getSelectedSlot());

        target.breakingTicks++;
        if (target.breakingTicks >= target.estTicks) {
            // Grim inter-break delay: hold the finishing STOP until break-delay-ms has elapsed since
            // the last finish, so finishes aren't sent back-to-back. The block stays at full progress
            // server-side until we send STOP, so the only effect is a slightly later break.
            if (grim.get() && System.currentTimeMillis() - lastFinishMs < breakDelayMs.get()) {
                return target;
            }
            // Rotate to the block face before the finishing packet so the server sees us looking at it.
            if (rotate.get()) Rotations.rotate(Rotations.getYaw(target.pos), Rotations.getPitch(target.pos));
            sendFinish(target);
            lastFinishMs = System.currentTimeMillis();
            // Do NOT remove the block client-side — let the server send the block update.
            // Optimistic removal before server confirmation is what causes ghost blocks.
            return null;
        }

        return target;
    }

    // ── Tool selection ────────────────────────────────────────────────────

    /**
     * Hotbar slot (0-8) that breaks this block fastest, or -1 if nothing beats the currently-held
     * item. Ranks by {@link #breakDeltaForSlot} — the SAME quantity used to time the break — so it
     * folds in Efficiency, Haste, Mining Fatigue, being submerged/off-ground, etc., and selection
     * can never disagree with timing. Shared by FastBreak and {@link Nuker} (single source of truth).
     */
    static int bestToolSlot(BlockState state, BlockPos pos) {
        // Baseline = the real held item (silent slot -1 resolves to the genuine selected slot).
        float bestDelta = breakDeltaForSlot(state, pos, -1);
        int bestSlot = -1;
        for (int i = 0; i < 9; i++) {
            float delta = breakDeltaForSlot(state, pos, i);
            if (delta > bestDelta) {
                bestDelta = delta;
                bestSlot  = i;
            }
        }
        return bestSlot;
    }

    /**
     * Breaking progress per tick for the block, computed as if {@code toolSlot} were held
     * (or the real hand if {@code toolSlot == -1}).
     *
     * <p>{@code getDestroyProgress} resolves the player's main-hand item through
     * {@code Inventory.getSelectedSlot()}, so setting the silent override makes it use the
     * chosen tool without touching the real slot or notifying the server.
     */
    private float getBreakDelta(BlockState state, BlockPos pos, int toolSlot) {
        if (mc.player == null || mc.world == null) return 0;
        int saved = silentSlot;
        silentSlot = toolSlot;
        try {
            return state.calcBlockBreakingDelta(mc.player, mc.world, pos);
        } finally {
            silentSlot = saved;
        }
    }

    /**
     * Computes break-delta for a given hotbar slot without permanently altering silentSlot.
     * Safe to call from render or from Nuker — the override is held only for the duration
     * of the getDestroyProgress call and is always restored.
     */
    static float breakDeltaForSlot(BlockState state, BlockPos pos, int toolSlot) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return 0;
        int saved = silentSlot;
        silentSlot = toolSlot;
        try {
            return state.calcBlockBreakingDelta(client.player, client.world, pos);
        } finally {
            silentSlot = saved;
        }
    }

    // ── Silent swap helpers ───────────────────────────────────────────────

    /** Clears the override and restores the server's held item to the real selected slot. */
    private void restoreSlot() {
        silentSlot = -1;
        if (mc.player != null) syncServerSlot(mc.player.getInventory().getSelectedSlot());
    }

    private void syncServerSlot(int slot) {
        if (mc.player == null || slot == lastServerSlot) return;
        mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
        lastServerSlot = slot;
    }

    // ── Packet helpers ────────────────────────────────────────────────────

    private void sendStart(MineTarget t)  {
        if (grim.get()) { sendStartGrim(t); return; }
        send(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, t);
    }
    private void sendAbort(MineTarget t)  { send(PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, t); }

    /**
     * Grim-specific start sequence. Mirrors Shoreline's 2.x Grim FastBreak handling: it interleaves
     * START/ABORT/STOP destroy actions with swings so Grim's block-action state machine sees a
     * "clean" begin-and-cancel before the real break proceeds. The server treats START as begin,
     * ABORT as cancel, and STOP as finish/hand-off of its single destroy slot — sending them in
     * quick succession resets that slot rather than completing a break.
     *
     * <p>⚠ Anticheat behaviour changes over time; this may need revalidation against the current
     * Grim build and is not guaranteed to bypass anything.
     */
    private void sendStartGrim(MineTarget t) {
        if (grimV3.get()) {
            // grimV3 path: STOP, START, ABORT to flush the slot, then STOP + three swings.
            send(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, t);
            send(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, t);
            send(PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, t);
            send(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, t);
            swing(); swing(); swing();
        } else {
            // grimV3-off path: two rounds of START / ABORT / STOP / swing.
            for (int rep = 0; rep < 2; rep++) {
                send(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, t);
                send(PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, t);
                send(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, t);
                swing();
            }
        }
    }

    private void swing() {
        if (mc.player == null) return;
        mc.player.networkHandler.sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
    }

    // STOP_DESTROY_BLOCK both completes a break and hands one off to the server's delayed slot;
    // the server decides which based on its own tracked progress, so finish and hand-off are the
    // same packet (kept as two names for readability).
    private void sendStop(MineTarget t)   { send(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, t); }
    private void sendFinish(MineTarget t) { send(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, t); }

    private void send(PlayerActionC2SPacket.Action action, MineTarget t) {
        if (mc.player == null) return;
        mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(action, t.pos, t.face));
    }

    // ── Render ────────────────────────────────────────────────────────────

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!renderBreak.get() || mc.player == null || mc.world == null) return;
        if (Modules.get().get(Nuker.class).isActive()) return;
        renderTarget(event, primary);
        renderTarget(event, secondary);
    }

    private void renderTarget(Render3DEvent event, MineTarget target) {
        if (target == null) return;

        BlockState state = mc.world.getBlockState(target.pos);
        if (state.isAir()) return;

        // Time-based, tick-independent growth: the cube expands continuously from the block centre
        // to the full 1×1×1 box over the break's estimated duration (50 ms per tick). Instant
        // breaks (estTicks <= 1) just show the full box for their single frame.
        float progress;
        if (target.estTicks <= 1) {
            progress = 1.0f;
        } else {
            float elapsed = System.currentTimeMillis() - target.startMs;
            progress = Math.max(0.0f, Math.min(elapsed / (target.estTicks * 50.0f), 1.0f));
        }

        Color side, line;
        if (rainbow.get()) {
            float hue = (float) ((System.currentTimeMillis() / 1000.0 * rainbowSpeed.get()) % 1.0);
            int rgb = java.awt.Color.HSBtoRGB(hue, 1.0f, 1.0f);
            int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
            side = new Color(r, g, b, fillColor.get().a);
            line = new Color(r, g, b, outlineColor.get().a);
        } else {
            side = fillColor.get();
            line = outlineColor.get();
        }

        double cx = target.pos.getX() + 0.5;
        double cy = target.pos.getY() + 0.5;
        double cz = target.pos.getZ() + 0.5;
        double half = progress * 0.5;

        event.renderer.box(
            cx - half, cy - half, cz - half,
            cx + half, cy + half, cz + half,
            side, line, shapeMode.get(), 0
        );
    }
}
