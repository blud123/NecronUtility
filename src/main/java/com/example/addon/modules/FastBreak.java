package com.example.addon.modules;

import meteordevelopment.meteorclient.events.entity.player.StartBreakingBlockEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import net.minecraft.client.Minecraft;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Takes over vanilla block breaking with packet-level control, automatic silent tool
 * selection (see {@link com.example.addon.mixin.SilentSwapMixin}) and optional double-break.
 *
 * <p>It does NOT pick blocks itself — it reacts to {@link StartBreakingBlockEvent}, the event
 * Meteor fires whenever a block is left-clicked (manually, or by another module such as Nuker
 * that drives blocks through {@code MultiPlayerGameMode.startDestroyBlock}). When the event
 * fires, FastBreak cancels vanilla breaking and handles the break itself.
 *
 * <h2>Protocol note (Mojang mappings, 1.21.8)</h2>
 * There is no {@code FINISH_DESTROY_BLOCK} action. Vanilla uses {@code STOP_DESTROY_BLOCK}
 * both to <em>complete</em> a break and to <em>hand off</em> a block to the server's single
 * "delayed destroy" slot (which keeps breaking it independently). That delayed slot is what
 * makes double-break possible: STOP the in-progress block (server demotes it to delayed and
 * keeps mining it), then START the next one.
 */
public class FastBreak extends Module {

    // ── Silent swap state (read by SilentSwapMixin) ──────────────────────
    private static int silentSlot = -1;
    public static int getSilentSlot() { return silentSlot; }

    // Tracks the slot the server currently believes is held, so we only send a
    // SetCarriedItem packet when it actually changes.
    private int lastServerSlot = -1;

    // ── Settings ─────────────────────────────────────────────────────────
    private final SettingGroup sg       = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Boolean> doubleBreak = sg.add(new BoolSetting.Builder()
        .name("double-break")
        .description("Break a second block simultaneously while the first is in progress.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> swapTool = sg.add(new BoolSetting.Builder()
        .name("swap-tool")
        .description("Silently swap to the best tool in your hotbar for each block.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> serverSwapTicks = sg.add(new IntSetting.Builder()
        .name("server-swap-ticks")
        .description("How many ticks before finishing a break to sync the tool, giving the server time to process it.")
        .defaultValue(2)
        .min(0).max(5)
        .sliderRange(0, 5)
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
        boolean secondary; // true = demoted by a new primary; server breaks it independently

        MineTarget(BlockPos pos, Direction face, int breakingTicks, boolean secondary) {
            this.pos = pos;
            this.face = face;
            this.breakingTicks = breakingTicks;
            this.secondary = secondary;
        }
    }

    private MineTarget primary   = null;
    private MineTarget secondary = null;

    // ── Constructor ───────────────────────────────────────────────────────

    public FastBreak() {
        super(
            com.example.addon.AddonTemplate.CATEGORY,
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
    }

    @Override
    public void onDeactivate() {
        if (mc.player != null) {
            if (primary   != null) sendAbort(primary);
            if (secondary != null) sendAbort(secondary);
            silentSlot = -1;
            restoreSlot();
        }
        primary   = null;
        secondary = null;
        silentSlot = -1;
    }

    // ── Start breaking — triggered by player click AND by Nuker ───────────

    @EventHandler
    private void onStartBreaking(StartBreakingBlockEvent event) {
        if (mc.player == null || mc.level == null) return;

        BlockPos pos   = event.blockPos;
        Direction face = event.direction;

        BlockState state = mc.level.getBlockState(pos);
        if (state.isAir()) return;

        // Already tracking this block — just swallow the vanilla break.
        if ((primary != null && primary.pos.equals(pos)) || (secondary != null && secondary.pos.equals(pos))) {
            event.setCancelled(true);
            return;
        }

        if (primary != null) {
            if (doubleBreak.get() && secondary == null) {
                // Demote the current primary to secondary: STOP_DESTROY_BLOCK hands it off to
                // the server's delayed-destroy slot, so it keeps breaking independently.
                secondary = new MineTarget(primary.pos, primary.face, primary.breakingTicks, true);
                sendStop(secondary);
            } else {
                // No room for another simultaneous break — drop the old primary.
                sendAbort(primary);
                primary = null;
            }
        }

        primary = new MineTarget(pos, face, 0, false);
        sendStart(primary);
        event.setCancelled(true);
    }

    // ── Per-tick progress update ──────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        // Process secondary first so its tool sync is ordered before the primary's.
        if (secondary != null) secondary = tickTarget(secondary);
        if (primary   != null) primary   = tickTarget(primary);

        // Restore the held item server-side after all work this tick.
        restoreSlot();
    }

    /**
     * Advances one tick of breaking for a target. Returns {@code null} when the target finished
     * or became invalid, otherwise the (mutated) target.
     */
    private MineTarget tickTarget(MineTarget target) {
        if (mc.player == null || mc.level == null) return null;

        BlockState state = mc.level.getBlockState(target.pos);
        if (state.isAir()) return null; // already gone

        int bestSlot = swapTool.get() ? findBestToolSlot(state) : -1;

        // Compute the per-tick break fraction with the best tool silently active (client-side only).
        float breakDelta = getBreakDelta(state, target.pos, bestSlot);
        if (breakDelta <= 0) return target; // unbreakable with this tool

        int ticksToFinish = (int) Math.ceil(1.0f / breakDelta);

        // Sync the tool to the server ahead of the finish so it has time to register the swap.
        boolean nearFinish = target.breakingTicks >= ticksToFinish - serverSwapTicks.get();
        if (bestSlot != -1 && nearFinish) applySilentSwap(bestSlot);

        target.breakingTicks++;
        float progress = breakDelta * target.breakingTicks;

        if (progress >= 1.0f) {
            // Guarantee the correct tool is in the server's hand for the finishing packet.
            if (bestSlot != -1) applySilentSwap(bestSlot);
            sendFinish(target);
            // Do NOT remove the block client-side — let the server send the block update.
            // Optimistic removal before server confirmation is what causes ghost blocks.
            restoreSlot();
            return null;
        }

        return target;
    }

    // ── Tool selection ────────────────────────────────────────────────────

    /**
     * Hotbar slot (0-8) with the highest mining speed for this block, or -1 if nothing in the
     * hotbar beats the bare hand. Reads {@code getItem(i)} directly, so it is unaffected by the
     * silent override.
     */
    private int findBestToolSlot(BlockState state) {
        if (mc.player == null) return -1;
        int bestSlot = -1;
        float bestSpeed = 1.0f;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            float speed = stack.getDestroySpeed(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
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
        if (mc.player == null || mc.level == null) return 0;
        silentSlot = toolSlot; // -1 simply means "no override / use the real hand"
        return state.getDestroyProgress(mc.player, mc.level, pos);
    }

    /**
     * Computes break-delta for a given hotbar slot without permanently altering silentSlot.
     * Safe to call from render or from Nuker — the override is held only for the duration
     * of the getDestroyProgress call and is always restored.
     */
    static float breakDeltaForSlot(BlockState state, BlockPos pos, int toolSlot) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return 0;
        int saved = silentSlot;
        silentSlot = toolSlot;
        float delta = state.getDestroyProgress(client.player, client.level, pos);
        silentSlot = saved;
        return delta;
    }

    // ── Silent swap helpers ───────────────────────────────────────────────

    /** Sets the client-side override AND tells the server we're holding this slot. */
    private void applySilentSwap(int slot) {
        silentSlot = slot;
        syncServerSlot(slot);
    }

    /** Clears the override and restores the server's held item to the real selected slot. */
    private void restoreSlot() {
        silentSlot = -1;
        if (mc.player != null) syncServerSlot(mc.player.getInventory().getSelectedSlot());
    }

    private void syncServerSlot(int slot) {
        if (mc.player == null || slot == lastServerSlot) return;
        mc.player.connection.send(new ServerboundSetCarriedItemPacket(slot));
        lastServerSlot = slot;
    }

    // ── Packet helpers ────────────────────────────────────────────────────

    private void sendStart(MineTarget t)  { send(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, t); }
    private void sendAbort(MineTarget t)  { send(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, t); }

    // STOP_DESTROY_BLOCK both completes a break and hands one off to the server's delayed slot;
    // the server decides which based on its own tracked progress, so finish and hand-off are the
    // same packet (kept as two names for readability).
    private void sendStop(MineTarget t)   { send(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, t); }
    private void sendFinish(MineTarget t) { send(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, t); }

    private void send(ServerboundPlayerActionPacket.Action action, MineTarget t) {
        if (mc.player == null) return;
        mc.player.connection.send(new ServerboundPlayerActionPacket(action, t.pos, t.face));
    }

    // ── Render ────────────────────────────────────────────────────────────

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!renderBreak.get() || mc.player == null || mc.level == null) return;
        renderTarget(event, primary);
        renderTarget(event, secondary);
    }

    private void renderTarget(Render3DEvent event, MineTarget target) {
        if (target == null) return;

        BlockState state = mc.level.getBlockState(target.pos);
        if (state.isAir()) return;

        int bestSlot = swapTool.get() ? findBestToolSlot(state) : -1;
        float delta = breakDeltaForSlot(state, target.pos, bestSlot);
        float progress = delta <= 0 ? 1.0f : Math.min(delta * target.breakingTicks, 1.0f);

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
