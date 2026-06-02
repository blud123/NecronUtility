package com.example.addon.modules;

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
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.Vec3d;

import java.util.*;

public class Nuker extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgFilter   = settings.createGroup("Filter");
    private final SettingGroup sgRender   = settings.createGroup("Render");

    // — General —
    private final Setting<Double> radius = sgGeneral.add(new DoubleSetting.Builder()
        .name("radius")
        .description("Break radius around the player.")
        .defaultValue(4.0)
        .min(1.0).max(6.0)
        .sliderRange(1.0, 6.0)
        .build());

    private final Setting<Integer> maxBlocks = sgGeneral.add(new IntSetting.Builder()
        .name("max-blocks")
        .description("Max simultaneous in-progress breaks. The server only tracks one active + one delayed destroy, so keep this 1-2 on 2b2t.")
        .defaultValue(2)
        .min(1).max(2)
        .sliderRange(1, 2)
        .build());

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotate toward blocks before breaking (helps on 2b2t).")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> autoTool = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-tool")
        .description("Let Nuker pick the best tool. Actual swapping is performed by FastBreak's swap-tool; this also gates swap-before.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> swapBefore = sgGeneral.add(new BoolSetting.Builder()
        .name("swap-before")
        .description("Sync the best tool to the server BEFORE the break starts, not just before it finishes.")
        .defaultValue(false)
        .visible(autoTool::get)
        .build());

    private final Setting<Boolean> packetMine = sgGeneral.add(new BoolSetting.Builder()
        .name("packet-mine")
        .description("Send START + STOP packets instantly (instant break for weak blocks).")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> onlyExposed = sgGeneral.add(new BoolSetting.Builder()
        .name("only-exposed")
        .description("Only break blocks with at least one exposed face (faster, less suspicious).")
        .defaultValue(true)
        .build());

    private final Setting<SortMode> sortMode = sgGeneral.add(new EnumSetting.Builder<SortMode>()
        .name("sort-mode")
        .description("Order in which to target blocks.")
        .defaultValue(SortMode.CLOSEST)
        .build());

    // — Filter —
    private final Setting<Selection> selection = sgFilter.add(new EnumSetting.Builder<Selection>()
        .name("selection")
        .description("ALL = break everything; WHITELIST = only listed blocks; BLACKLIST = everything except listed blocks.")
        .defaultValue(Selection.ALL)
        .build());

    private final Setting<List<Block>> whitelist = sgFilter.add(new BlockListSetting.Builder()
        .name("whitelist")
        .description("Only break these blocks (when selection = WHITELIST).")
        .visible(() -> selection.get() == Selection.WHITELIST)
        .build());

    private final Setting<List<Block>> blacklist = sgFilter.add(new BlockListSetting.Builder()
        .name("blacklist")
        .description("Never break these blocks (when selection = BLACKLIST).")
        .visible(() -> selection.get() == Selection.BLACKLIST)
        .build());

    private final Setting<Boolean> filterBedrock = sgFilter.add(new BoolSetting.Builder()
        .name("skip-bedrock")
        .description("Skip bedrock (unbreakable on 2b2t).")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> filterFluids = sgFilter.add(new BoolSetting.Builder()
        .name("skip-fluids")
        .description("Skip water and lava source blocks.")
        .defaultValue(true)
        .build());

    // — Render —
    private final Setting<Boolean> renderBlocks = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Render an overlay on blocks being mined.")
        .defaultValue(true)
        .build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How to render the block overlay.")
        .defaultValue(ShapeMode.Both)
        .build());

    private final Setting<SettingColor> fillColor = sgRender.add(new ColorSetting.Builder()
        .name("fill-color")
        .description("Color of the overlay fill.")
        .defaultValue(new SettingColor(255, 0, 0, 50))
        .build());

    private final Setting<SettingColor> outlineColor = sgRender.add(new ColorSetting.Builder()
        .name("outline-color")
        .description("Color of the overlay outline.")
        .defaultValue(new SettingColor(255, 0, 0, 255))
        .build());

    private final Setting<Boolean> rainbow = sgRender.add(new BoolSetting.Builder()
        .name("rainbow")
        .description("Cycle through rainbow colors.")
        .defaultValue(false)
        .build());

    private final Setting<Double> rainbowSpeed = sgRender.add(new DoubleSetting.Builder()
        .name("rainbow-speed")
        .description("Speed of the rainbow cycle.")
        .defaultValue(1.0)
        .min(0.1).max(5.0)
        .sliderRange(0.1, 5.0)
        .visible(rainbow::get)
        .build());

    public enum SortMode { CLOSEST, LOWEST, HIGHEST }
    public enum Selection { ALL, WHITELIST, BLACKLIST }

    /** Per-block break state for a locked (started) break — the single source of truth for "locked" slots. */
    private static class Mining {
        int ticks;        // multi-tick progress accumulator (standalone path)
        long startMs;     // anchor for the smooth time-based render
        int estTicks = 1; // estimated ticks to finish; locked at start

        Mining(long startMs, int estTicks) {
            this.startMs = startMs;
            this.estTicks = estTicks;
        }
    }

    // Locked breaks: insertion-ordered so the oldest locks keep priority. This is the single
    // source of truth for which blocks own a budget slot — selection always services these first
    // and never preempts a mid-break block (sticky targets, ncrnu/v5 §3b).
    private final Map<BlockPos, Mining> breaks = new LinkedHashMap<>();

    // Reused per-tick scratch so the hot loop allocates no containers.
    private final List<BlockPos> validTargets = new ArrayList<>();
    private final Set<BlockPos> targetSet = new HashSet<>();
    private final BlockPos.Mutable scratch = new BlockPos.Mutable();

    // Precomputed radius offset sphere, ordered for the current sort mode. Rebuilt only when the
    // radius or sort mode changes — the per-tick sweep just offsets these from the player origin.
    private int[][] offsets = new int[0][];
    private double cachedRadius = -1;
    private SortMode cachedSort = null;

    // Block-name cache so we don't call getName().getString() every tick.
    private final Map<Block, String> nameCache = new HashMap<>();
    private String miningBlockName = "";

    // Warn-once latch when FastBreak isn't available to drive breaking.
    private boolean warnedNoFastBreak = false;

    public Nuker() {
        super(com.example.addon.DWAddons.CATEGORY, "nuker",
            "Breaks all blocks in a circle. Tuned for 2b2t lag.");
    }

    @Override
    public String getInfoString() {
        return miningBlockName.isEmpty() ? null : miningBlockName;
    }

    @Override
    public void onActivate() {
        breaks.clear();
        miningBlockName = "";
        warnedNoFastBreak = false;
    }

    // Abort in-progress breaks (via FastBreak, which owns the packets) when the module is disabled.
    @Override
    public void onDeactivate() {
        FastBreak fastBreak = Modules.get().get(FastBreak.class);
        if (fastBreak != null && fastBreak.isActive()) {
            for (BlockPos pos : breaks.keySet()) fastBreak.cancelBreak(pos);
        }
        breaks.clear();
        miningBlockName = "";
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        // Single source of truth: Nuker selects targets, FastBreak owns the break engine (tool sync
        // + packets). Without an active FastBreak there is nothing to drive the breaks.
        FastBreak fastBreak = Modules.get().get(FastBreak.class);
        if (fastBreak == null || !fastBreak.isActive()) {
            if (!warnedNoFastBreak) {
                warning("Enable FastBreak — Nuker now drives its break engine instead of sending its own packets.");
                warnedNoFastBreak = true;
            }
            breaks.clear();
            miningBlockName = "";
            return;
        }
        warnedNoFastBreak = false;

        collectTargets(); // fills validTargets (sort order) + targetSet (membership)

        // Evict locks that left the radius / turned to air, telling FastBreak to abort each.
        Iterator<Map.Entry<BlockPos, Mining>> it = breaks.entrySet().iterator();
        while (it.hasNext()) {
            BlockPos pos = it.next().getKey();
            if (!targetSet.contains(pos)) {
                fastBreak.cancelBreak(pos);
                it.remove();
            }
        }

        if (validTargets.isEmpty()) {
            miningBlockName = "";
            return;
        }

        // FastBreak tracks one active + one delayed destroy, so the multi-tick budget is at most 2.
        int budget = Math.min(maxBlocks.get(), 2);

        // Build the active set: every still-valid locked break first (never preempted), then
        // fill any free slots with the highest-priority new candidates.
        List<BlockPos> active = new ArrayList<>(breaks.keySet());
        int freeSlots = budget - active.size();
        if (freeSlots > 0) {
            for (BlockPos pos : validTargets) {
                if (freeSlots <= 0) break;
                if (breaks.containsKey(pos) || active.contains(pos)) continue;
                active.add(pos);
                freeSlots--;
            }
        }

        if (active.isEmpty()) {
            miningBlockName = "";
            return;
        }

        miningBlockName = cachedName(mc.world.getBlockState(active.get(0)).getBlock());

        // Rotate once toward the first (highest-priority) target, via Meteor's shared Rotations.
        if (rotate.get()) {
            BlockPos first = active.get(0);
            Rotations.rotate(Rotations.getYaw(first), Rotations.getPitch(first));
        }

        for (BlockPos pos : active) {
            BlockState state = mc.world.getBlockState(pos);
            Direction face = getFacing(pos);

            // Swap-before: pre-sync the tool through FastBreak ahead of its START packet.
            if (swapBefore.get() && autoTool.get()) fastBreak.syncToolFor(state, pos);

            // Estimate using the exact slot FastBreak will hold, so instant detection matches reality.
            int toolSlot = fastBreak.effectiveToolSlot(state, pos);
            float delta = FastBreak.breakDeltaForSlot(state, pos, toolSlot);

            if (packetMine.get() && delta >= 1.0f) {
                // Instant fast path: one-shot START+STOP through FastBreak. Not a persistent lock.
                fastBreak.packetBreak(pos, face);
                breaks.remove(pos);
            } else {
                // Multi-tick: keep feeding the same position; FastBreak no-ops once it is tracking it.
                lock(pos, state, toolSlot); // render estimate only
                fastBreak.requestBreak(pos, face);
            }
        }
    }

    /** Registers a lock for {@code pos} if absent, computing its render estimate once. */
    private Mining lock(BlockPos pos, BlockState state, int toolSlot) {
        Mining m = breaks.get(pos);
        if (m != null) return m;
        float delta = FastBreak.breakDeltaForSlot(state, pos, toolSlot);
        int estTicks = delta <= 0 ? 1 : Math.max(1, (int) Math.ceil(1.0f / delta));
        m = new Mining(System.currentTimeMillis(), estTicks);
        breaks.put(pos, m);
        return m;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!renderBlocks.get() || breaks.isEmpty()) return;
        if (mc.player == null || mc.world == null) return;

        for (Map.Entry<BlockPos, Mining> e : breaks.entrySet()) {
            BlockPos pos = e.getKey();
            Mining m = e.getValue();
            if (mc.world.getBlockState(pos).isAir()) continue;

            // Smooth, tick-independent growth from the block centre to the full box (50 ms/tick).
            float progress;
            if (m.estTicks <= 1) {
                progress = 1.0f;
            } else {
                float elapsed = System.currentTimeMillis() - m.startMs;
                progress = Math.max(0.0f, Math.min(elapsed / (m.estTicks * 50.0f), 1.0f));
            }

            Color side;
            Color line;
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

            double cx = pos.getX() + 0.5;
            double cy = pos.getY() + 0.5;
            double cz = pos.getZ() + 0.5;
            double half = progress * 0.5;

            event.renderer.box(
                cx - half, cy - half, cz - half,
                cx + half, cy + half, cz + half,
                side, line, shapeMode.get(), 0
            );
        }
    }

    // ── Target collection (precomputed offset sphere) ───────────────────────

    private void collectTargets() {
        rebuildOffsetsIfNeeded();

        validTargets.clear();
        targetSet.clear();

        Vec3d eye = mc.player.getEyePos();
        int ox = (int) Math.floor(eye.x);
        int oy = (int) Math.floor(eye.y);
        int oz = (int) Math.floor(eye.z);

        for (int[] off : offsets) {
            scratch.set(ox + off[0], oy + off[1], oz + off[2]);
            BlockState state = mc.world.getBlockState(scratch);
            if (shouldSkip(state)) continue;
            if (onlyExposed.get() && !hasExposedFace(scratch)) continue;
            BlockPos pos = scratch.toImmutable();
            validTargets.add(pos);
            targetSet.add(pos);
        }
    }

    /** Rebuilds the offset sphere only when the radius or sort mode changes. */
    private void rebuildOffsetsIfNeeded() {
        double r = radius.get();
        SortMode mode = sortMode.get();
        if (r == cachedRadius && mode == cachedSort) return;

        int ri = (int) Math.ceil(r);
        double r2 = r * r;
        List<int[]> list = new ArrayList<>();
        for (int dx = -ri; dx <= ri; dx++) {
            for (int dy = -ri; dy <= ri; dy++) {
                for (int dz = -ri; dz <= ri; dz++) {
                    int distSq = dx * dx + dy * dy + dz * dz;
                    if (distSq <= r2) list.add(new int[]{dx, dy, dz, distSq});
                }
            }
        }

        Comparator<int[]> comp = switch (mode) {
            case CLOSEST -> Comparator.<int[]>comparingInt(o -> o[3]);
            case LOWEST  -> Comparator.<int[]>comparingInt(o -> o[1]);
            case HIGHEST -> Comparator.<int[]>comparingInt(o -> o[1]).reversed();
        };
        list.sort(comp);

        offsets = list.toArray(new int[0][]);
        cachedRadius = r;
        cachedSort = mode;
    }

    private boolean hasExposedFace(BlockPos pos) {
        for (Direction dir : Direction.values()) {
            if (mc.world.getBlockState(pos.offset(dir)).isAir()) return true;
        }
        return false;
    }

    private boolean shouldSkip(BlockState state) {
        if (state.isAir()) return true;
        Block block = state.getBlock();
        if (filterBedrock.get() && block == Blocks.BEDROCK) return true;
        if (filterFluids.get() && (block == Blocks.WATER || block == Blocks.LAVA)) return true;
        // Whitelist/blacklist selection mode, applied on top of the bedrock/fluid filters.
        switch (selection.get()) {
            case WHITELIST -> { if (!whitelist.get().contains(block)) return true; }
            case BLACKLIST -> { if (blacklist.get().contains(block)) return true; }
            case ALL -> { /* break everything not already filtered */ }
        }
        return false;
    }

    private Direction getFacing(BlockPos pos) {
        Vec3d eye = mc.player.getEyePos();
        double dx = eye.x - (pos.getX() + 0.5);
        double dy = eye.y - (pos.getY() + 0.5);
        double dz = eye.z - (pos.getZ() + 0.5);
        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        if (ay >= ax && ay >= az) return dy > 0 ? Direction.UP : Direction.DOWN;
        if (ax >= az) return dx > 0 ? Direction.EAST : Direction.WEST;
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private String cachedName(Block block) {
        return nameCache.computeIfAbsent(block, b -> b.getName().getString());
    }
}
