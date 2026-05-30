package com.example.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockIterator;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class Nuker extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgFilter   = settings.createGroup("Filter");

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

    private final Setting<SortMode> sortMode = sgGeneral.add(new EnumSetting.Builder<SortMode>()
        .name("sort-mode")
        .description("Order in which to target blocks.")
        .defaultValue(SortMode.CLOSEST)
        .build());

    public enum SortMode { CLOSEST, LOWEST, HIGHEST }

    private String miningBlockName = "";

    public Nuker() {
        super(com.example.addon.AddonTemplate.CATEGORY, "nuker",
            "Breaks all blocks in a circle. Tuned for 2b2t lag.");
    }

    @Override
    public String getInfoString() {
        return miningBlockName.isEmpty() ? null : miningBlockName;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;

        List<BlockPos> targets = collectTargets();
        if (targets.isEmpty()) {
            miningBlockName = "";
            return;
        }

        int broken = 0;
        for (BlockPos pos : targets) {
            if (broken >= blocksPerTick.get()) break;

            BlockState state = mc.level.getBlockState(pos);
            if (shouldSkip(state)) continue;

            if (broken == 0) miningBlockName = state.getBlock().getName().getString();

            if (autoTool.get()) equipBestTool(state);
            if (rotate.get()) Rotations.rotate(
                Rotations.getYaw(pos), Rotations.getPitch(pos), null);

            if (packetMine.get()) {
                Direction face = getFacing(pos);
                mc.player.connection.send(
                    new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                        pos, face));
                mc.player.connection.send(
                    new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                        pos, face));
            } else {
                mc.gameMode.startDestroyBlock(pos, getFacing(pos));
            }
            broken++;
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

    private void equipBestTool(BlockState state) {
        int bestSlot = -1;
        float bestSpeed = -1;
        for (int i = 0; i < 9; i++) {
            float speed = mc.player.getInventory().getItem(i).getDestroySpeed(state);
            if (speed > bestSpeed) { bestSpeed = speed; bestSlot = i; }
        }
        if (bestSlot != -1) mc.player.getInventory().selected = bestSlot;
    }
}
