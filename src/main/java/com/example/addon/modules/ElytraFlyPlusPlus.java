package com.example.addon.modules;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalBlock;
import com.example.addon.DWAddons;
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

import net.minecraft.util.math.BlockPos;
import net.minecraft.screen.sync.ItemStackHash;
import net.minecraft.network.packet.s2c.play.CloseScreenS2CPacket;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.util.Identifier;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.MovementType;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.Vec3d;

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
        super(DWAddons.CATEGORY, "ElytraFlyPlusPlus", "Elytra fly with some more features.");
    }

    public enum HighwayType { DIAGONAL, CARDINAL, AUTO }

    private boolean startSprinting;
    private BlockPos portalTrap = null;
    private boolean paused = false;
    private boolean elytraToggled = false;
    private Vec3d lastUnstuckPos;
    private int stuckTimer = 0;
    private Vec3d lastPos;
    private final double maxDistance = 16 * 5; // 5 chunks forward
    private BlockPos tempPath = null;
    private boolean waitingForChunksToLoad;

    // ── highway-math helpers (inlined; Mojmap Vec3d) ──────────────────────

    /** Snap an angle (deg) to the nearest 45° highway axis, normalized to [0,360). */
    private static double angleOnAxis(double yawDeg) {
        double snapped = Math.round(yawDeg / 45.0) * 45.0;
        return ((snapped % 360) + 360) % 360;
    }

    /** Unit horizontal direction for a Minecraft yaw (deg). yaw 0 = +Z, 90 = -X. */
    private static Vec3d yawToDirection(double yawDeg) {
        double rad = Math.toRadians(yawDeg);
        return new Vec3d(-Math.sin(rad), 0.0, Math.cos(rad));
    }

    /** pos moved {@code dist} blocks along {@code yawDeg}. */
    private static Vec3d positionInDirection(Vec3d pos, double yawDeg, double dist) {
        return pos.add(yawToDirection(yawDeg).multiply(dist));
    }

    /** Horizontal perpendicular distance from {@code point} to the line through {@code linePoint} along {@code dir}. */
    private static double distancePointToDirection(Vec3d linePoint, Vec3d dir, Vec3d point) {
        Vec3d d = new Vec3d(dir.x, 0.0, dir.z).normalize();
        Vec3d rel = point.subtract(linePoint);
        rel = new Vec3d(rel.x, 0.0, rel.z);
        Vec3d proj = d.multiply(rel.dotProduct(d));
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
        if (fakeFly.get() && event.packet instanceof CloseScreenS2CPacket) {
            event.cancel();
        }
    }

    @Override
    public void onActivate() {
        if (mc.player == null || mc.player.getAbilities().allowFlying) return;

        startSprinting = mc.player.isSprinting();
        tempPath = null;
        portalTrap = null;
        paused = false;
        waitingForChunksToLoad = false;
        elytraToggled = false;
        lastPos = mc.player.getPos();
        lastUnstuckPos = mc.player.getPos();
        stuckTimer = 0;

        if (bounce.get() && mc.player.getPos().multiply(1, 0, 1).length() >= 100) {
            if (BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess().currentDestination() == null) {
                BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoal(null);
            }
            if (!useCustomStartPos.get()) {
                startPos.set(new BlockPos(0, 0, 0));
            }
            if (!useCustomYaw.get()) {
                if (mc.player.getBlockPos().getSquaredDistance(startPos.get()) < 10_000 || !highwayObstaclePasser.get()) {
                    double playerAngleNormalized = angleOnAxis(mc.player.getYaw());
                    yaw.set(playerAngleNormalized);
                } else {
                    BlockPos directionVec = mc.player.getBlockPos().subtract(startPos.get());
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
        if (mc.player == null || event.type != MovementType.SELF || !enabled() || !motionYBoost.get() || !bounce.get()) return;

        // ── NEW: mode-aware engagement ───────────────────────────────────
        // Diagonal staircase walls re-trigger horizontalCollision every block, sustaining the boost.
        // Flat cardinal walls never re-collide, so on cardinals engage on floor contact instead.
        boolean cardinal = isCardinalMode();
        boolean requireWall = onlyWhileColliding.get() && !cardinal;
        if (requireWall && !mc.player.horizontalCollision) return;

        if (lastPos != null) {
            double speedBps = mc.player.getPos().subtract(lastPos).multiply(20, 0, 20).length();

            Timer timer = Modules.get().get(Timer.class);
            if (timer != null && timer.isActive()) speedBps *= timer.getMultiplier();

            if (mc.player.isOnGround() && mc.player.isSprinting() && speedBps < speed.get()) {
                // A diagonal uses the wall to bootstrap from standstill; a flat cardinal floor has nothing,
                // so cancel vertical movement from the first tick so speed can start building.
                if (speedBps > 20 || tunnelBounce.get() || cardinal) {
                    event.movement = new Vec3d(event.movement.x, 0.0, event.movement.z);
                }
                mc.player.setVelocity(mc.player.getVelocity().x, 0.0, mc.player.getVelocity().z);
            }
        }

        lastPos = mc.player.getPos();
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
            if (!mc.player.getEquippedStack(EquipmentSlot.CHEST).getItem().toString().contains("chestplate")) {
                Modules.get().get(ChestSwap.class).swap();
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.player.getAbilities().allowFlying) return;

        if (toggleElytra.get() && !fakeFly.get() && !elytraToggled) {
            if (!mc.player.getEquippedStack(EquipmentSlot.CHEST).getItem().equals(Items.ELYTRA)) {
                Modules.get().get(ChestSwap.class).swap();
            } else {
                elytraToggled = true;
            }
        }

        if (enabled()) mc.player.setSprinting(true);

        if (bounce.get()) {
            if (tempPath != null && mc.player.getBlockPos().getSquaredDistance(tempPath) < 500) {
                tempPath = null;
                BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoal(null);
            } else if (tempPath != null) {
                BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(tempPath));
                return;
            }

            if (highwayObstaclePasser.get() && BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().getGoal() != null) {
                return;
            }

            if (mc.player.squaredDistanceTo(lastUnstuckPos) < 25) {
                stuckTimer++;
            } else {
                stuckTimer = 0;
                lastUnstuckPos = mc.player.getPos();
            }

            if (highwayObstaclePasser.get() && mc.player.getPos().length() > 100 &&
                (mc.player.getY() < targetY.get() || mc.player.getY() > targetY.get() + 2
                    || (mc.player.horizontalCollision && !mc.player.collidedSoftly)
                    || (portalTrap != null && portalTrap.getSquaredDistance(mc.player.getBlockPos()) < portalAvoidDistance.get() * portalAvoidDistance.get())
                    || waitingForChunksToLoad
                    || stuckTimer > 50)) {
                waitingForChunksToLoad = false;
                paused = true;
                BlockPos goal = mc.player.getBlockPos();
                double currDistance = distance.get();

                if (portalTrap != null) {
                    currDistance += mc.player.getPos().distanceTo(Vec3d.ofCenter(portalTrap));
                    portalTrap = null;
                    info("Pathing around portal.");
                }

                do {
                    if (currDistance > maxDistance) {
                        tempPath = goal;
                        BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(goal));
                        return;
                    }
                    Vec3d unitYawVec = yawToDirection(yaw.get());
                    Vec3d travelVec = mc.player.getPos().subtract(Vec3d.ofCenter(startPos.get()));

                    double parallelCurrPosDot = travelVec.multiply(1, 0, 1).dotProduct(unitYawVec);
                    Vec3d parallelCurrPosComponent = unitYawVec.multiply(parallelCurrPosDot);

                    Vec3d pos = Vec3d.ofCenter(startPos.get()).add(parallelCurrPosComponent);
                    pos = positionInDirection(pos, yaw.get(), currDistance);

                    goal = new BlockPos((int) Math.floor(pos.x), targetY.get(), (int) Math.floor(pos.z));
                    currDistance++;

                    if (mc.world.getBlockState(goal).getBlock() == Blocks.VOID_AIR) {
                        waitingForChunksToLoad = true;
                        return;
                    }
                } while (!mc.world.getBlockState(goal.down()).isSolidBlock(mc.world, goal.down())
                    || mc.world.getBlockState(goal).getBlock() == Blocks.NETHER_PORTAL
                    || !mc.world.getBlockState(goal).isAir());
                BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(goal));
            } else {
                paused = false;
                if (!enabled()) return;

                if (!fakeFly.get()) {
                    if (mc.player.isOnGround() && (!motionYBoost.get() || Utils.getPlayerSpeed().multiply(1, 0, 1).length() < speed.get())) {
                        mc.player.jump();
                    }
                }

                if (lockYaw.get()) mc.player.setYaw(yaw.get().floatValue());
                if (lockPitch.get()) mc.player.setPitch(pitch.get().floatValue());
            }
        }

        if (enabled()) {
            if (fakeFly.get()) doGrimEflyStuff();
            else sendStartFlyingPacket();
        }
    }

    public boolean enabled() {
        return this.isActive() && !paused && mc.player != null
            && (fakeFly.get() || mc.player.getEquippedStack(EquipmentSlot.CHEST).getItem().equals(Items.ELYTRA));
    }

    private void doGrimEflyStuff() {
        FindItemResult itemResult = InvUtils.findInHotbar(Items.ELYTRA);
        if (!itemResult.found()) return;
        swapToItem(itemResult.slot());
        sendStartFlyingPacket();
        if (bounce.get() && mc.player.isOnGround() && (!motionYBoost.get() || Utils.getPlayerSpeed().multiply(1, 0, 1).length() < speed.get())) {
            mc.player.jump();
        }
        swapToItem(itemResult.slot());
    }

    @EventHandler
    private void onPlaySound(PlaySoundEvent event) {
        if (!fakeFly.get()) return;
        List<Identifier> armorEquipSounds = List.of(
            Identifier.of("minecraft:item.armor.equip_generic"),
            Identifier.of("minecraft:item.armor.equip_netherite"),
            Identifier.of("minecraft:item.armor.equip_elytra"),
            Identifier.of("minecraft:item.armor.equip_diamond"),
            Identifier.of("minecraft:item.armor.equip_gold"),
            Identifier.of("minecraft:item.armor.equip_iron"),
            Identifier.of("minecraft:item.armor.equip_chain"),
            Identifier.of("minecraft:item.armor.equip_leather"),
            Identifier.of("minecraft:item.elytra.flying")
        );
        for (Identifier id : armorEquipSounds) {
            if (id.equals(event.sound.getId())) {
                event.cancel();
                break;
            }
        }
    }

    // Build the server-side SWAP that puts the hotbar elytra (button = hotbar index) into the
    // chestplate menu slot (6) and the chestplate back into the hotbar slot (36 + index). The
    // client never applies it locally, so it keeps showing the chestplate (the "grim efly" trick).
    private void swapToItem(int slot) {
        ItemStack chestItem = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        ItemStack hotbarSwapItem = mc.player.getInventory().getStack(slot);

        // 1.21.8 click packets carry hashed item predictions, not full stacks.
        var gen = mc.player.networkHandler.getComponentHasher();
        Int2ObjectMap<ItemStackHash> changedSlots = new Int2ObjectOpenHashMap<>();
        changedSlots.put(6, ItemStackHash.fromItemStack(hotbarSwapItem, gen));
        changedSlots.put(slot + 36, ItemStackHash.fromItemStack(chestItem, gen));
        sendSwapPacket(changedSlots, slot);
    }

    private void sendStartFlyingPacket() {
        if (mc.player == null) return;
        mc.player.networkHandler.sendPacket(new ClientCommandC2SPacket(
            mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
    }

    private void sendSwapPacket(Int2ObjectMap<ItemStackHash> changedSlots, int buttonNum) {
        int syncId = mc.player.currentScreenHandler.syncId;
        int stateId = mc.player.currentScreenHandler.getRevision();
        mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
            syncId, stateId, (short) 6, (byte) buttonNum, SlotActionType.SWAP, changedSlots, ItemStackHash.EMPTY));
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (!avoidPortalTraps.get() || !highwayObstaclePasser.get()) return;
        if (mc.player == null || mc.world == null) return;
        ChunkPos pos = event.chunk().getPos();
        BlockPos centerPos = new BlockPos(pos.getCenterX(), targetY.get(), pos.getCenterZ());

        Vec3d moveDir = yawToDirection(yaw.get());
        double distanceToHighway = distancePointToDirection(Vec3d.of(centerPos), moveDir, mc.player.getPos());
        if (distanceToHighway > 21) return;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = targetY.get(); y < targetY.get() + 3; y++) {
                    BlockPos position = new BlockPos(pos.x * 16 + x, y, pos.z * 16 + z);
                    if (distancePointToDirection(Vec3d.of(position), moveDir, mc.player.getPos()) > portalScanWidth.get()) continue;

                    if (mc.world.getBlockState(position).getBlock().equals(Blocks.NETHER_PORTAL)) {
                        BlockPos posBehind = new BlockPos((int) Math.floor(position.getX() + moveDir.x), position.getY(), (int) Math.floor(position.getZ() + moveDir.z));
                        if (mc.world.getBlockState(posBehind).isSolidBlock(mc.world, posBehind)
                            || mc.world.getBlockState(posBehind).getBlock() == Blocks.NETHER_PORTAL) {
                            if (portalTrap == null || (portalTrap.getSquaredDistance(posBehind) > 100
                                && mc.player.getBlockPos().getSquaredDistance(posBehind) < mc.player.getBlockPos().getSquaredDistance(portalTrap))) {
                                portalTrap = posBehind;
                            }
                        }
                    }
                }
            }
        }
    }
}
