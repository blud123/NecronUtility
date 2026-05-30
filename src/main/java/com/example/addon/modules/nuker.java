package com.example.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockIterator;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

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
        .defaultValue(1)
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

    public Nuker() {
        super(com.example.addon.AddonTemplate.CATEGORY, "nuker",
            "Breaks all blocks in a circle. Tuned for 2b2t lag.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        List<BlockPos> targets = collectTargets();
        if (targets.isEmpty()) return;

        int broken = 0;
        for (BlockPos pos : targets) {
            if (broken >= blocksPerTick.get()) break;

            BlockState state = mc.world.getBlockState(pos);
            if (shouldSkip(state)) continue;

            if (autoTool.get()) equipBestTool(state);
            if (rotate.get()) Rotations.rotate(
                Rotations.getYaw(pos), Rotations.getPitch(pos), null);

            if (packetMine.get()) {
                // Instant-break packet pair
                mc.player.networkHandler.sendPacket(
                    new PlayerActionC2SPacket(
                        PlayerActionC2SPacket.Action.START_DESTROY_BLOCK,
                        pos, Direction.UP));
                mc.player.networkHandler.sendPacket(
                    new PlayerActionC2SPacket(
                        PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK,
                        pos, Direction.UP));
                mc.world.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
            } else {
                mc.interactionManager.attackBlock(pos, Direction.UP);
            }
            broken++;
        }
    }

    private List<BlockPos> collectTargets() {
        List<BlockPos> list = new ArrayList<>();
        double r = radius.get();

        BlockIterator.register((int) Math.ceil(r), (int) Math.ceil(r), (pos, state) -> {
            if (state.isAir()) return;
            if (mc.player.squaredDistanceTo(
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > r * r) return;

            if (onlyExposed.get() && !hasExposedFace(pos)) return;
            list.add(pos.toImmutable());
        });

        Comparator<BlockPos> comp = switch (sortMode.get()) {
            case CLOSEST -> Comparator.comparingDouble(p ->
                mc.player.squaredDistanceTo(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5));
            case LOWEST  -> Comparator.comparingInt(BlockPos::getY);
            case HIGHEST -> Comparator.comparingInt((BlockPos p) -> p.getY()).reversed();
        };
        list.sort(comp);
        return list;
    }

    private boolean hasExposedFace(BlockPos pos) {
        for (Direction dir : Direction.values()) {
            if (mc.world.getBlockState(pos.offset(dir)).isAir()) return true;
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

    private void equipBestTool(BlockState state) {
        int bestSlot = -1;
        float bestSpeed = -1;
        for (int i = 0; i < 9; i++) {
            float speed = mc.player.getInventory().getStack(i)
                .getMiningSpeedMultiplier(state);
            if (speed > bestSpeed) { bestSpeed = speed; bestSlot = i; }
        }
        if (bestSlot != -1) mc.player.getInventory().selectedSlot = bestSlot;
    }
}
