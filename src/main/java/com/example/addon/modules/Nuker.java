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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

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

    private final Setting<Integer> blocksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("blocks-per-tick")
        .description("How many blocks to break per tick. Keep low on 2b2t (1-2).")
        .defaultValue(2)
        .min(1).max(4)
        .sliderRange(1, 4)
        .build());

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotate toward blocks before breaking (helps on 2b2t).")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> autoTool = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-tool")
        .description("Automatically switch to the best tool.")
        .defaultValue(true)
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

    // — Filter —
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

    private final Setting<SortMode> sortMode = sgGeneral.add(new EnumSetting.Builder<SortMode>()
        .name("sort-mode")
        .description("Order in which to target blocks.")
        .defaultValue(SortMode.CLOSEST)
        .build());

    public enum SortMode { CLOSEST, LOWEST, HIGHEST }

    // Tick count per tracked block (all paths). Keyed eviction is done against the full
    // in-range target set, so tick counts survive budget rotation (fix for Problem 1).
    private final Map<BlockPos, Integer> miningTicks = new LinkedHashMap<>();

    // Blocks in the multi-tick standalone path that have had START_DESTROY_BLOCK sent.
    // Re-sending START resets server-side progress, so we track and only send it once
    // per block (fix for Problem 2).
    private final Set<BlockPos> startedBreaks = new LinkedHashSet<>();

    // Render list: populated from startedBreaks + any per-tick budget targets, so the
    // overlay shows all actively-tracked blocks rather than just the budget slice.
    private final List<BlockPos> currentTargets = new ArrayList<>();

    private String miningBlockName = "";

    // True original slot before Nuker took over tool management in multi-tick mode.
    // -1 means no override is active. We restore here rather than every tick so the
    // server's per-tick mining progress calculation always sees the correct tool.
    private int savedSlot = -1;

    public Nuker() {
        super(com.example.addon.AddonTemplate.CATEGORY, "nuker",
            "Breaks all blocks in a circle. Tuned for 2b2t lag.");
    }

    @Override
    public String getInfoString() {
        return miningBlockName.isEmpty() ? null : miningBlockName;
    }

    @Override
    public void onActivate() {
        savedSlot = -1;
    }

    // Abort in-progress breaks and restore the tool slot when the module is disabled.
    @Override
    public void onDeactivate() {
        if (mc.player != null) {
            for (BlockPos pos : startedBreaks) {
                mc.player.connection.send(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                    pos, getFacing(pos)));
            }
            if (savedSlot != -1) {
                mc.player.connection.send(new ServerboundSetCarriedItemPacket(savedSlot));
                mc.player.getInventory().selected = savedSlot;
            }
        }
        miningTicks.clear();
        startedBreaks.clear();
        currentTargets.clear();
        miningBlockName = "";
        savedSlot = -1;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;

        List<BlockPos> targets = collectTargets();
        Set<BlockPos> targetSet = new HashSet<>(targets);

        // Evict state for blocks that left the radius. Using the full target set (not the
        // budget-capped slice) means blocks temporarily outside the budget keep their ticks.
        miningTicks.keySet().removeIf(pos -> !targetSet.contains(pos));
        startedBreaks.removeIf(pos -> !targetSet.contains(pos));

        // If eviction emptied all in-progress breaks, restore the tool slot now.
        if (savedSlot != -1 && startedBreaks.isEmpty()) {
            mc.player.connection.send(new ServerboundSetCarriedItemPacket(savedSlot));
            mc.player.getInventory().selected = savedSlot;
            savedSlot = -1;
        }

        // Render list starts with all actively-started blocks so the overlay persists
        // even when a started block rotates out of the current budget.
        currentTargets.clear();
        currentTargets.addAll(startedBreaks);

        if (targets.isEmpty()) {
            miningBlockName = "";
            return;
        }

        FastBreak fastBreak = Modules.get().get(FastBreak.class);
        boolean useFastBreak = fastBreak != null && fastBreak.isActive();

        int originalSlot = mc.player.getInventory().selected;

        // Cap at 2 when delegating to FastBreak: it tracks primary + secondary only.
        // Submitting a 3rd block causes it to abort the current primary.
        int budget = useFastBreak ? Math.min(blocksPerTick.get(), 2) : blocksPerTick.get();

        List<BlockPos> toBreak = new ArrayList<>();
        for (BlockPos pos : targets) {
            if (toBreak.size() >= budget) break;
            if (shouldSkip(mc.level.getBlockState(pos))) continue;
            toBreak.add(pos);
        }

        if (toBreak.isEmpty()) {
            miningBlockName = "";
            return;
        }

        // Sort by tool slot only in packet-mine mode (instant breaks, re-sorting is harmless).
        // The multi-tick path must NOT re-sort every tick — that causes budget churn and
        // prevents ticks accumulating on any one block (the root cause of Problem 2).
        if (!useFastBreak && autoTool.get() && packetMine.get()) {
            toBreak.sort(Comparator.comparingInt(p -> getBestToolSlot(mc.level.getBlockState(p))));
        }

        miningBlockName = mc.level.getBlockState(toBreak.get(0)).getBlock().getName().getString();

        int currentSlot = originalSlot;
        for (BlockPos pos : toBreak) {
            BlockState state = mc.level.getBlockState(pos);
            Direction face = getFacing(pos);

            if (rotate.get()) Rotations.rotate(
                Rotations.getYaw(pos), Rotations.getPitch(pos), null);

            if (useFastBreak) {
                // FastBreak intercepts StartBreakingBlockEvent and takes full control:
                // progress tracking, tool selection, and packet sending are all its job.
                mc.gameMode.startDestroyBlock(pos, face);
                miningTicks.merge(pos, 1, Integer::sum);
                if (!currentTargets.contains(pos)) currentTargets.add(pos);

            } else {
                // Nuker owns the break — swap to the best tool.
                if (autoTool.get()) {
                    int toolSlot = getBestToolSlot(state);
                    if (toolSlot != currentSlot) {
                        // Capture the true original slot the first time we switch in multi-tick
                        // mode. We hold the tool until the break is done rather than restoring
                        // every tick, so the server's per-tick progress calc uses the right tool.
                        if (!packetMine.get() && savedSlot == -1) savedSlot = originalSlot;
                        mc.player.connection.send(new ServerboundSetCarriedItemPacket(toolSlot));
                        mc.player.getInventory().selected = toolSlot;
                        currentSlot = toolSlot;
                    }
                }

                if (packetMine.get()) {
                    mc.player.connection.send(new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, face));
                    mc.player.connection.send(new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, face));
                    miningTicks.merge(pos, 1, Integer::sum);
                    if (!currentTargets.contains(pos)) currentTargets.add(pos);

                } else {
                    // Send START only once — re-sending resets server-side progress.
                    if (!startedBreaks.contains(pos)) {
                        mc.player.connection.send(new ServerboundPlayerActionPacket(
                            ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, face));
                        startedBreaks.add(pos);
                        miningTicks.put(pos, 0);
                        if (!currentTargets.contains(pos)) currentTargets.add(pos);
                    }

                    // Accumulate ticks and send STOP when the block has been mined long enough.
                    int ticks = miningTicks.merge(pos, 1, Integer::sum);
                    float delta = FastBreak.breakDeltaForSlot(state, pos, getBestToolSlot(state));

                    if (delta > 0 && delta * ticks >= 1.0f) {
                        mc.player.connection.send(new ServerboundPlayerActionPacket(
                            ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, face));
                        startedBreaks.remove(pos);
                        miningTicks.remove(pos);
                        currentTargets.remove(pos);
                        // Restore the slot once all multi-tick breaks are done.
                        if (startedBreaks.isEmpty() && savedSlot != -1) {
                            mc.player.connection.send(new ServerboundSetCarriedItemPacket(savedSlot));
                            mc.player.getInventory().selected = savedSlot;
                            savedSlot = -1;
                        }
                    }
                }
            }
        }

        // For instant (packet-mine) breaks: restore each tick — START+STOP land in the same
        // tick so the tool only needs to be correct for that window.
        // For multi-tick breaks: DON'T restore here. The server's per-tick progress calc
        // runs after processing all packets, so restoring every tick means it always sees
        // the wrong tool. We restore via savedSlot once the break completes or is evicted.
        if (!useFastBreak && packetMine.get() && currentSlot != originalSlot) {
            mc.player.connection.send(new ServerboundSetCarriedItemPacket(originalSlot));
            mc.player.getInventory().selected = originalSlot;
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!renderBlocks.get() || currentTargets.isEmpty()) return;
        if (mc.player == null || mc.level == null) return;

        for (BlockPos pos : currentTargets) {
            int ticks = miningTicks.getOrDefault(pos, 0);
            BlockState state = mc.level.getBlockState(pos);
            int bestSlot = getBestToolSlot(state);
            // Use the best hotbar tool's break speed, not the currently held one —
            // that's the actual speed the block is being mined at.
            float delta = FastBreak.breakDeltaForSlot(state, pos, bestSlot);
            float progress = delta <= 0 ? 1.0f : Math.min(delta * ticks, 1.0f);

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

    private List<BlockPos> collectTargets() {
        List<BlockPos> list = new ArrayList<>();
        double r = radius.get();
        int ri = (int) Math.ceil(r);
        Vec3 eye = mc.player.getEyePosition();
        BlockPos origin = BlockPos.containing(eye);

        for (int dx = -ri; dx <= ri; dx++) {
            for (int dy = -ri; dy <= ri; dy++) {
                for (int dz = -ri; dz <= ri; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    BlockState state = mc.level.getBlockState(pos);
                    if (state.isAir()) continue;
                    double distSq = eye.distanceToSqr(
                        pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                    if (distSq > r * r) continue;
                    if (onlyExposed.get() && !hasExposedFace(pos)) continue;
                    list.add(pos);
                }
            }
        }

        Vec3 sortEye = eye;
        Comparator<BlockPos> comp = switch (sortMode.get()) {
            case CLOSEST -> Comparator.comparingDouble(p ->
                sortEye.distanceToSqr(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5));
            case LOWEST  -> Comparator.comparingInt(BlockPos::getY);
            case HIGHEST -> Comparator.comparingInt((BlockPos p) -> p.getY()).reversed();
        };
        list.sort(comp);
        return list;
    }

    private boolean hasExposedFace(BlockPos pos) {
        for (Direction dir : Direction.values()) {
            if (mc.level.getBlockState(pos.relative(dir)).isAir()) return true;
        }
        return false;
    }

    private boolean shouldSkip(BlockState state) {
        if (state.isAir()) return true;
        if (filterBedrock.get() && state.getBlock() == Blocks.BEDROCK) return true;
        if (filterFluids.get() &&
            (state.getBlock() == Blocks.WATER || state.getBlock() == Blocks.LAVA)) return true;
        return false;
    }

    private Direction getFacing(BlockPos pos) {
        Vec3 eye = mc.player.getEyePosition();
        double dx = eye.x - (pos.getX() + 0.5);
        double dy = eye.y - (pos.getY() + 0.5);
        double dz = eye.z - (pos.getZ() + 0.5);
        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        if (ay >= ax && ay >= az) return dy > 0 ? Direction.UP : Direction.DOWN;
        if (ax >= az) return dx > 0 ? Direction.EAST : Direction.WEST;
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private int getBestToolSlot(BlockState state) {
        int bestSlot = mc.player.getInventory().selected;
        float bestSpeed = -1;
        for (int i = 0; i < 9; i++) {
            float speed = mc.player.getInventory().getItem(i).getDestroySpeed(state);
            if (speed > bestSpeed) { bestSpeed = speed; bestSlot = i; }
        }
        return bestSlot;
    }
}
