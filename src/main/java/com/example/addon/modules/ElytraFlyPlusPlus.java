package com.example.addon.modules;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalBlock;
import com.example.addon.AddonTemplate;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.PlaySoundEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.player.ChestSwap;
import meteordevelopment.meteorclient.systems.modules.world.Timer;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;

import net.minecraft.core.BlockPos;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Elytra fly with bounce, a baritone obstacle passer, and a chestplate "fake fly" trick.
 *
 * <p>This is the Mojmap port of the original module, extended with a {@link HighwayType} setting:
 * <ul>
 *   <li>{@code DIAGONAL} — the original wall-bounce. On 45° highways the diagonal staircase wall
 *       re-triggers {@link net.minecraft.world.entity.Entity#horizontalCollision} every block,
 *       which keeps the motion-y boost engaged.</li>
 *   <li>{@code CARDINAL} — floor-bounce for straight N/S/E/W highways. A flat cardinal wall never
 *       re-collides, so engagement is driven by floor contact instead of wall contact.</li>
 *   <li>{@code AUTO} — infers the mode from the locked yaw (a multiple of 90° → cardinal).</li>
 * </ul>
 *
 * <p><b>Cardinal limitation:</b> floor-bounce needs continuous ground at {@code y-level}; any gap
 * in the floor drops the player a tick and causes a speed stutter until ground is regained.
 */
public class ElytraFlyPlusPlus extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgObstaclePasser = settings.createGroup("Obstacle Passer");

    private final Setting<Boolean> bounce = sgGeneral.add(new BoolSetting.Builder()
        .name("bounce").description("Automatically does bounce efly.").defaultValue(false).build());

    private final Setting<Boolean> motionYBoost = sgGeneral.add(new BoolSetting.Builder()
        .name("motion-y-boost").description("Greatly increases speed by cancelling Y momentum.")
        .defaultValue(false).visible(bounce::get).build());

    private final Setting<Boolean> onlyWhileColliding = sgGeneral.add(new BoolSetting.Builder()
        .name("only-while-colliding").description("Only enables motion y boost if colliding with a wall.")
        .defaultValue(true).visible(() -> bounce.get() && motionYBoost.get()).build());

    private final Setting<Boolean> tunnelBounce = sgGeneral.add(new BoolSetting.Builder()
        .name("tunnel-bounce").description("Allows you to bounce in 1x2 tunnels. This should not be on if you are not in a tunnel.")
        .defaultValue(false).visible(() -> bounce.get() && motionYBoost.get()).build());

    // ── NEW: highway type ────────────────────────────────────────────────
    private final Setting<HighwayType> highwayType = sgGeneral.add(new EnumSetting.Builder<HighwayType>()
        .name("highway-type")
        .description("DIAGONAL = original wall-bounce. CARDINAL = floor-bounce for straight N/S/E/W highways. AUTO = infer from the locked yaw (multiple of 90 -> cardinal).")
        .defaultValue(HighwayType.AUTO)
        .visible(() -> bounce.get() && motionYBoost.get())
        .build());

    private final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed").description("The speed in blocks per second to keep you at.")
        .defaultValue(100.0).sliderRange(20, 250).visible(() -> bounce.get() && motionYBoost.get()).build());

    private final Setting<Boolean> lockPitch = sgGeneral.add(new BoolSetting.Builder()
        .name("lock-pitch").description("Whether to lock your pitch when bounce is enabled.")
        .defaultValue(true).visible(bounce::get).build());

    private final Setting<Double> pitch = sgGeneral.add(new DoubleSetting.Builder()
        .name("pitch").description("The pitch to set when bounce is enabled.")
        .defaultValue(90.0).sliderRange(-90, 90).visible(() -> bounce.get() && lockPitch.get()).build());

    private final Setting<Boolean> lockYaw = sgGeneral.add(new BoolSetting.Builder()
        .name("lock-yaw").description("Whether to lock your yaw when bounce is enabled.")
        .defaultValue(false).visible(bounce::get).build());

    private final Setting<Boolean> useCustomYaw = sgGeneral.add(new BoolSetting.Builder()
        .name("use-custom-yaw").description("Enable this if you want to use a yaw that isn't a factor of 45. WARNING: This effects the baritone goal for obstacle passer.")
        .defaultValue(false).visible(bounce::get).build());

    private final Setting<Double> yaw = sgGeneral.add(new DoubleSetting.Builder()
        .name("yaw").description("The yaw to set when bounce is enabled. Auto-set to the closest 45 deg angle unless Use Custom Yaw is on.")
        .defaultValue(0.0).sliderRange(0, 359).visible(() -> bounce.get() && useCustomYaw.get()).build());

    private final Setting<Boolean> highwayObstaclePasser = sgObstaclePasser.add(new BoolSetting.Builder()
        .name("highway-obstacle-passer").description("Uses baritone to pass obstacles.")
        .defaultValue(false).visible(bounce::get).build());

    private final Setting<Boolean> useCustomStartPos = sgObstaclePasser.add(new BoolSetting.Builder()
        .name("use-custom-start-position").description("Enable ONLY on a ringroad or to not be locked to a highway. Otherwise (0,0) is used.")
        .defaultValue(false).visible(() -> bounce.get() && highwayObstaclePasser.get()).build());

    private final Setting<BlockPos> startPos = sgObstaclePasser.add(new BlockPosSetting.Builder()
        .name("start-position").description("The start position to use when using a custom start position.")
        .defaultValue(new BlockPos(0, 0, 0)).visible(() -> bounce.get() && highwayObstaclePasser.get() && useCustomStartPos.get()).build());

    private final Setting<Boolean> awayFromStartPos = sgObstaclePasser.add(new BoolSetting.Builder()
        .name("away-from-start-position").description("If true, go away from the start position instead of towards it.")
        .defaultValue(true).visible(() -> bounce.get() && highwayObstaclePasser.get()).build());

    private final Setting<Double> distance = sgObstaclePasser.add(new DoubleSetting.Builder()
        .name("distance").description("The distance to set the baritone goal for path realignment.")
        .defaultValue(10.0).visible(() -> bounce.get() && highwayObstaclePasser.get()).build());

    private final Setting<Integer> targetY = sgObstaclePasser.add(new IntSetting.Builder()
        .name("y-level").description("The Y level to bounce at. This must be correct or bounce will not start properly.")
        .defaultValue(120).visible(() -> bounce.get() && highwayObstaclePasser.get()).build());

    private final Setting<Boolean> avoidPortalTraps = sgObstaclePasser.add(new BoolSetting.Builder()
        .name("avoid-portal-traps").description("Will attempt to detect portal traps on chunk load and avoid them.")
        .defaultValue(false).visible(() -> bounce.get() && highwayObstaclePasser.get()).build());

    private final Setting<Double> portalAvoidDistance = sgObstaclePasser.add(new DoubleSetting.Builder()
        .name("portal-avoid-distance").description("Distance to a portal trap where the obstacle passer takes over.")
        .defaultValue(20).min(0).sliderMax(50).visible(() -> bounce.get() && highwayObstaclePasser.get() && avoidPortalTraps.get()).build());

    private final Setting<Integer> portalScanWidth = sgObstaclePasser.add(new IntSetting.Builder()
        .name("portal-scan-width").description("Width on the highway axis scanned for portal traps.")
        .defaultValue(5).min(3).sliderMax(10).visible(() -> bounce.get() && highwayObstaclePasser.get() && avoidPortalTraps.get()).build());

    private final Setting<Boolean> fakeFly = sgGeneral.add(new BoolSetting.Builder()
        .name("chestplate-fakefly").description("Fly using a chestplate to use almost 0 elytra durability. Must have elytra in hotbar.")
        .defaultValue(false).build());

    private final Setting<Boolean> toggleElytra = sgGeneral.add(new BoolSetting.Builder()
        .name("toggle-elytra").description("Equips an elytra on activate, and a chestplate on deactivate.")
        .defaultValue(false).visible(() -> !fakeFly.get()).build());

    public ElytraFlyPlusPlus() {
        super(AddonTemplate.CATEGORY, "ElytraFlyPlusPlus", "Elytra fly with some more features.");
    }

    public enum HighwayType { DIAGONAL, CARDINAL, AUTO }

    private boolean startSprinting;
    private BlockPos portalTrap = null;
    private boolean paused = false;
    private boolean elytraToggled = false;
    private Vec3 lastUnstuckPos;
    private int stuckTimer = 0;
    private Vec3 lastPos;
    private final double maxDistance = 16 * 5; // 5 chunks forward
    private BlockPos tempPath = null;
    private boolean waitingForChunksToLoad;

    // ── highway-math helpers (inlined; Mojmap Vec3) ──────────────────────

    /** Snap an angle (deg) to the nearest 45° highway axis, normalized to [0,360). */
    private static double angleOnAxis(double yawDeg) {
        double snapped = Math.round(yawDeg / 45.0) * 45.0;
        return ((snapped % 360) + 360) % 360;
    }

    /** Unit horizontal direction for a Minecraft yaw (deg). yaw 0 = +Z, 90 = -X. */
    private static Vec3 yawToDirection(double yawDeg) {
        double rad = Math.toRadians(yawDeg);
        return new Vec3(-Math.sin(rad), 0.0, Math.cos(rad));
    }

    /** pos moved {@code dist} blocks along {@code yawDeg}. */
    private static Vec3 positionInDirection(Vec3 pos, double yawDeg, double dist) {
        return pos.add(yawToDirection(yawDeg).scale(dist));
    }

    /** Horizontal perpendicular distance from {@code point} to the line through {@code linePoint} along {@code dir}. */
    private static double distancePointToDirection(Vec3 linePoint, Vec3 dir, Vec3 point) {
        Vec3 d = new Vec3(dir.x, 0.0, dir.z).normalize();
        Vec3 rel = point.subtract(linePoint);
        rel = new Vec3(rel.x, 0.0, rel.z);
        Vec3 proj = d.scale(rel.dot(d));
        return rel.subtract(proj).length();
    }

    // ── NEW: resolve active mode. AUTO infers from the snapped yaw. ───────
    private boolean isCardinalMode() {
        switch (highwayType.get()) {
            case CARDINAL: return true;
            case DIAGONAL: return false;
            default: // AUTO
                int yawDeg = Math.floorMod(Math.round(yaw.get()), 360);
                return (yawDeg % 90) == 0; // 0/90/180/270 -> cardinal
        }
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        // Only suppress the server's forced container close while the fake-fly swap trick is active;
        // cancelling it unconditionally would stop every GUI/chest from closing server-side.
        if (fakeFly.get() && event.packet instanceof ClientboundContainerClosePacket) {
            event.cancel();
        }
    }

    @Override
    public void onActivate() {
        if (mc.player == null || mc.player.getAbilities().mayfly) return;

        startSprinting = mc.player.isSprinting();
        tempPath = null;
        portalTrap = null;
        paused = false;
        waitingForChunksToLoad = false;
        elytraToggled = false;
        lastPos = mc.player.position();
        lastUnstuckPos = mc.player.position();
        stuckTimer = 0;

        if (bounce.get() && mc.player.position().multiply(1, 0, 1).length() >= 100) {
            if (BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess().currentDestination() == null) {
                BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoal(null);
            }
            if (!useCustomStartPos.get()) {
                startPos.set(new BlockPos(0, 0, 0));
            }
            if (!useCustomYaw.get()) {
                if (mc.player.blockPosition().distSqr(startPos.get()) < 10_000 || !highwayObstaclePasser.get()) {
                    double playerAngleNormalized = angleOnAxis(mc.player.getYRot());
                    yaw.set(playerAngleNormalized);
                } else {
                    BlockPos directionVec = mc.player.blockPosition().subtract(startPos.get());
                    double angle = Math.toDegrees(Math.atan2(-directionVec.getX(), directionVec.getZ()));
                    double angleNormalized = angleOnAxis(angle);
                    if (!awayFromStartPos.get()) angleNormalized += 180;
                    yaw.set(angleNormalized);
                }
            }
        }
    }

    @EventHandler
    private void onPlayerMove(PlayerMoveEvent event) {
        if (mc.player == null || event.type != MoverType.SELF || !enabled() || !motionYBoost.get() || !bounce.get()) return;

        // ── NEW: mode-aware engagement ───────────────────────────────────
        // Diagonal staircase walls re-trigger horizontalCollision every block, sustaining the boost.
        // Flat cardinal walls never re-collide, so on cardinals engage on floor contact instead.
        boolean cardinal = isCardinalMode();
        boolean requireWall = onlyWhileColliding.get() && !cardinal;
        if (requireWall && !mc.player.horizontalCollision) return;

        if (lastPos != null) {
            double speedBps = mc.player.position().subtract(lastPos).multiply(20, 0, 20).length();

            Timer timer = Modules.get().get(Timer.class);
            if (timer != null && timer.isActive()) speedBps *= timer.getMultiplier();

            if (mc.player.onGround() && mc.player.isSprinting() && speedBps < speed.get()) {
                // A diagonal uses the wall to bootstrap from standstill; a flat cardinal floor has nothing,
                // so cancel vertical movement from the first tick so speed can start building.
                if (speedBps > 20 || tunnelBounce.get() || cardinal) {
                    event.movement = new Vec3(event.movement.x, 0.0, event.movement.z);
                }
                mc.player.setDeltaMovement(mc.player.getDeltaMovement().x, 0.0, mc.player.getDeltaMovement().z);
            }
        }

        lastPos = mc.player.position();
    }

    @Override
    public void onDeactivate() {
        if (mc.player == null) return;
        if (bounce.get()) {
            if (BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess().currentDestination() == null) {
                BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoal(null);
            }
        }
        mc.player.setSprinting(startSprinting);
        if (toggleElytra.get() && !fakeFly.get()) {
            if (!mc.player.getItemBySlot(EquipmentSlot.CHEST).getItem().toString().contains("chestplate")) {
                Modules.get().get(ChestSwap.class).swap();
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.player.getAbilities().mayfly) return;

        if (toggleElytra.get() && !fakeFly.get() && !elytraToggled) {
            if (!mc.player.getItemBySlot(EquipmentSlot.CHEST).getItem().equals(Items.ELYTRA)) {
                Modules.get().get(ChestSwap.class).swap();
            } else {
                elytraToggled = true;
            }
        }

        if (enabled()) mc.player.setSprinting(true);

        if (bounce.get()) {
            if (tempPath != null && mc.player.blockPosition().distSqr(tempPath) < 500) {
                tempPath = null;
                BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoal(null);
            } else if (tempPath != null) {
                BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(tempPath));
                return;
            }

            if (highwayObstaclePasser.get() && BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().getGoal() != null) {
                return;
            }

            if (mc.player.distanceToSqr(lastUnstuckPos) < 25) {
                stuckTimer++;
            } else {
                stuckTimer = 0;
                lastUnstuckPos = mc.player.position();
            }

            if (highwayObstaclePasser.get() && mc.player.position().length() > 100 &&
                (mc.player.getY() < targetY.get() || mc.player.getY() > targetY.get() + 2
                    || (mc.player.horizontalCollision && !mc.player.minorHorizontalCollision)
                    || (portalTrap != null && portalTrap.distSqr(mc.player.blockPosition()) < portalAvoidDistance.get() * portalAvoidDistance.get())
                    || waitingForChunksToLoad
                    || stuckTimer > 50)) {
                waitingForChunksToLoad = false;
                paused = true;
                BlockPos goal = mc.player.blockPosition();
                double currDistance = distance.get();

                if (portalTrap != null) {
                    currDistance += mc.player.position().distanceTo(Vec3.atCenterOf(portalTrap));
                    portalTrap = null;
                    info("Pathing around portal.");
                }

                do {
                    if (currDistance > maxDistance) {
                        tempPath = goal;
                        BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(goal));
                        return;
                    }
                    Vec3 unitYawVec = yawToDirection(yaw.get());
                    Vec3 travelVec = mc.player.position().subtract(Vec3.atCenterOf(startPos.get()));

                    double parallelCurrPosDot = travelVec.multiply(1, 0, 1).dot(unitYawVec);
                    Vec3 parallelCurrPosComponent = unitYawVec.scale(parallelCurrPosDot);

                    Vec3 pos = Vec3.atCenterOf(startPos.get()).add(parallelCurrPosComponent);
                    pos = positionInDirection(pos, yaw.get(), currDistance);

                    goal = new BlockPos((int) Math.floor(pos.x), targetY.get(), (int) Math.floor(pos.z));
                    currDistance++;

                    if (mc.level.getBlockState(goal).getBlock() == Blocks.VOID_AIR) {
                        waitingForChunksToLoad = true;
                        return;
                    }
                } while (!mc.level.getBlockState(goal.below()).isRedstoneConductor(mc.level, goal.below())
                    || mc.level.getBlockState(goal).getBlock() == Blocks.NETHER_PORTAL
                    || !mc.level.getBlockState(goal).isAir());
                BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(goal));
            } else {
                paused = false;
                if (!enabled()) return;

                if (!fakeFly.get()) {
                    if (mc.player.onGround() && (!motionYBoost.get() || Utils.getPlayerSpeed().multiply(1, 0, 1).length() < speed.get())) {
                        mc.player.jumpFromGround();
                    }
                }

                if (lockYaw.get()) mc.player.setYRot(yaw.get().floatValue());
                if (lockPitch.get()) mc.player.setXRot(pitch.get().floatValue());
            }
        }

        if (enabled()) {
            if (fakeFly.get()) doGrimEflyStuff();
            else sendStartFlyingPacket();
        }
    }

    public boolean enabled() {
        return this.isActive() && !paused && mc.player != null
            && (fakeFly.get() || mc.player.getItemBySlot(EquipmentSlot.CHEST).getItem().equals(Items.ELYTRA));
    }

    private void doGrimEflyStuff() {
        FindItemResult itemResult = InvUtils.findInHotbar(Items.ELYTRA);
        if (!itemResult.found()) return;
        swapToItem(itemResult.slot());
        sendStartFlyingPacket();
        if (bounce.get() && mc.player.onGround() && (!motionYBoost.get() || Utils.getPlayerSpeed().multiply(1, 0, 1).length() < speed.get())) {
            mc.player.jumpFromGround();
        }
        swapToItem(itemResult.slot());
    }

    @EventHandler
    private void onPlaySound(PlaySoundEvent event) {
        if (!fakeFly.get()) return;
        List<ResourceLocation> armorEquipSounds = List.of(
            ResourceLocation.parse("minecraft:item.armor.equip_generic"),
            ResourceLocation.parse("minecraft:item.armor.equip_netherite"),
            ResourceLocation.parse("minecraft:item.armor.equip_elytra"),
            ResourceLocation.parse("minecraft:item.armor.equip_diamond"),
            ResourceLocation.parse("minecraft:item.armor.equip_gold"),
            ResourceLocation.parse("minecraft:item.armor.equip_iron"),
            ResourceLocation.parse("minecraft:item.armor.equip_chain"),
            ResourceLocation.parse("minecraft:item.armor.equip_leather"),
            ResourceLocation.parse("minecraft:item.elytra.flying")
        );
        for (ResourceLocation id : armorEquipSounds) {
            if (id.equals(event.sound.getLocation())) {
                event.cancel();
                break;
            }
        }
    }

    // Build the server-side SWAP that puts the hotbar elytra (button = hotbar index) into the
    // chestplate menu slot (6) and the chestplate back into the hotbar slot (36 + index). The
    // client never applies it locally, so it keeps showing the chestplate (the "grim efly" trick).
    private void swapToItem(int slot) {
        ItemStack chestItem = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        ItemStack hotbarSwapItem = mc.player.getInventory().getItem(slot);

        // 1.21.8 click packets carry hashed item predictions, not full stacks.
        var gen = mc.player.connection.decoratedHashOpsGenenerator();
        Int2ObjectMap<HashedStack> changedSlots = new Int2ObjectOpenHashMap<>();
        changedSlots.put(6, HashedStack.create(hotbarSwapItem, gen));
        changedSlots.put(slot + 36, HashedStack.create(chestItem, gen));
        sendSwapPacket(changedSlots, slot);
    }

    private void sendStartFlyingPacket() {
        if (mc.player == null) return;
        mc.player.connection.send(new ServerboundPlayerCommandPacket(
            mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
    }

    private void sendSwapPacket(Int2ObjectMap<HashedStack> changedSlots, int buttonNum) {
        int syncId = mc.player.containerMenu.containerId;
        int stateId = mc.player.containerMenu.getStateId();
        mc.player.connection.send(new ServerboundContainerClickPacket(
            syncId, stateId, (short) 6, (byte) buttonNum, ClickType.SWAP, changedSlots, HashedStack.EMPTY));
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (!avoidPortalTraps.get() || !highwayObstaclePasser.get()) return;
        if (mc.player == null || mc.level == null) return;
        ChunkPos pos = event.chunk().getPos();
        BlockPos centerPos = new BlockPos(pos.getMiddleBlockX(), targetY.get(), pos.getMiddleBlockZ());

        Vec3 moveDir = yawToDirection(yaw.get());
        double distanceToHighway = distancePointToDirection(Vec3.atLowerCornerOf(centerPos), moveDir, mc.player.position());
        if (distanceToHighway > 21) return;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = targetY.get(); y < targetY.get() + 3; y++) {
                    BlockPos position = new BlockPos(pos.x * 16 + x, y, pos.z * 16 + z);
                    if (distancePointToDirection(Vec3.atLowerCornerOf(position), moveDir, mc.player.position()) > portalScanWidth.get()) continue;

                    if (mc.level.getBlockState(position).getBlock().equals(Blocks.NETHER_PORTAL)) {
                        BlockPos posBehind = new BlockPos((int) Math.floor(position.getX() + moveDir.x), position.getY(), (int) Math.floor(position.getZ() + moveDir.z));
                        if (mc.level.getBlockState(posBehind).isRedstoneConductor(mc.level, posBehind)
                            || mc.level.getBlockState(posBehind).getBlock() == Blocks.NETHER_PORTAL) {
                            if (portalTrap == null || (portalTrap.distSqr(posBehind) > 100
                                && mc.player.blockPosition().distSqr(posBehind) < mc.player.blockPosition().distSqr(portalTrap))) {
                                portalTrap = posBehind;
                            }
                        }
                    }
                }
            }
        }
    }
}
