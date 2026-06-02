package com.example.addon.modules;

import com.example.addon.DWAddons;
import com.example.addon.mixin.EntityAccessor;
import com.example.addon.mixin.IServerboundMovePlayerPacket;
import com.example.addon.mixin.LivingEntityInvoker;
import com.example.addon.utils.HighwayUtil;
import com.example.addon.utils.PacketLimiter;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Items;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.world.Timer;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.entity.MovementType;
import net.minecraft.util.math.Vec3d;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class ElytraBouncePlus extends Module {

    public enum Mode { DIAGONAL, CARDINAL }

    // How close (blocks) to the x=0 / z=0 axis line counts as "on the highway".
    private static final double HIGHWAY_TOLERANCE = 5.0;
    // Minimum ticks between START_FALL_FLYING sends (edge-trigger cooldown).
    private static final int FALL_FLY_COOLDOWN = 10;

    private final SettingGroup sg        = settings.getDefaultGroup();
    private final SettingGroup sgPacket  = settings.createGroup("Packet Bounce");
    private final SettingGroup sgDesync  = settings.createGroup("Packet Desync");

    // ─── General ──────────────────────────────────────────────────────────────

    private final Setting<Mode> mode = sg.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("DIAGONAL: wall-bounce on diagonal highways. CARDINAL: ground-bounce on cardinal highways.")
        .defaultValue(Mode.DIAGONAL)
        .build());

    private final Setting<Boolean> motionYBoost = sg.add(new BoolSetting.Builder()
        .name("motion-y-boost")
        .description("Zero vertical movement on ground contact to convert it to horizontal speed.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> onlyWhileColliding = sg.add(new BoolSetting.Builder()
        .name("only-while-colliding")
        .description("Only apply motion-y-boost when touching a wall (diagonal highways).")
        .defaultValue(true)
        .visible(() -> motionYBoost.get() && mode.get() == Mode.DIAGONAL)
        .build());

    private final Setting<Boolean> tunnelBounce = sg.add(new BoolSetting.Builder()
        .name("tunnel-bounce")
        .description("Allow motion-y-boost even at low speeds (for narrow tunnel entry).")
        .defaultValue(false)
        .visible(() -> motionYBoost.get())
        .build());

    private final Setting<Double> speed = sg.add(new DoubleSetting.Builder()
        .name("speed")
        .description("Minimum speed (blocks/s) before motion-y-boost fires.")
        .defaultValue(100.0)
        .min(20.0).max(250.0)
        .sliderRange(20.0, 250.0)
        .visible(() -> motionYBoost.get())
        .build());

    private final Setting<Boolean> lockPitch = sg.add(new BoolSetting.Builder()
        .name("lock-pitch")
        .description("Lock the player's pitch to a fixed angle.")
        .defaultValue(true)
        .build());

    private final Setting<Double> pitch = sg.add(new DoubleSetting.Builder()
        .name("pitch")
        .description("Pitch angle to lock to (-90 = straight up, 90 = straight down).")
        .defaultValue(0.0)
        .min(-90.0).max(90.0)
        .sliderRange(-90.0, 90.0)
        .visible(() -> lockPitch.get())
        .build());

    private final Setting<Boolean> lockYaw = sg.add(new BoolSetting.Builder()
        .name("lock-yaw")
        .description("Lock the player's yaw to a fixed heading.")
        .defaultValue(false)
        .build());

    private final Setting<Double> yaw = sg.add(new DoubleSetting.Builder()
        .name("yaw")
        .description("Yaw angle to lock to (0 = south, 90 = west, 180/‑180 = north, ‑90 = east).")
        .defaultValue(0.0)
        .min(-180.0).max(180.0)
        .sliderRange(0.0, 359.0)
        .visible(() -> lockYaw.get())
        .build());

    // ─── Packet Bounce ────────────────────────────────────────────────────────

    private final Setting<Boolean> packetBounce = sgPacket.add(new BoolSetting.Builder()
        .name("packet-bounce")
        .description("Activates highway boost when speed exceeds the threshold.")
        .defaultValue(false)
        .build());

    private final Setting<Double> speedThreshold = sgPacket.add(new DoubleSetting.Builder()
        .name("speed-threshold")
        .description("Speed in blocks/sec required to arm packet bounce.")
        .defaultValue(35.0)
        .min(5.0).max(100.0)
        .sliderRange(5.0, 100.0)
        .build());

    private final Setting<Double> targetSpeed = sgPacket.add(new DoubleSetting.Builder()
        .name("target-speed")
        .description("Target speed in blocks/sec to maintain during packet bounce.")
        .defaultValue(60.0)
        .min(35.0).max(300.0)
        .sliderRange(35.0, 300.0)
        .build());

    private final Setting<Double> wallBoostMultiplier = sgPacket.add(new DoubleSetting.Builder()
        .name("wall-boost-multiplier")
        .description("Velocity multiplier applied when sliding against a wall.")
        .defaultValue(2.5)
        .min(1.1).max(10.0)
        .sliderRange(1.1, 10.0)
        .build());

    private final Setting<Boolean> frictionBypass = sgPacket.add(new BoolSetting.Builder()
        .name("friction-bypass")
        .description("Spoof onGround=false to server to prevent ground friction being applied.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> velocityKeepAlive = sgPacket.add(new BoolSetting.Builder()
        .name("velocity-keep-alive")
        .description("Re-apply target velocity every tick to fight server-side deceleration.")
        .defaultValue(true)
        .build());

    // ─── Packet Desync (the only packet path — see class javadoc) ───────────────

    private final Setting<Double> desyncStrength = sgDesync.add(new DoubleSetting.Builder()
        .name("desync-strength")
        .description("How far ahead to nudge the intercepted movement packet each tick.")
        .defaultValue(0.15)
        .min(0.01).max(0.5)
        .sliderRange(0.01, 0.5)
        .visible(() -> packetBounce.get())
        .build());

    // ─── State ────────────────────────────────────────────────────────────────

    private Vec3d    lastPos            = null;
    private Vec3d    bounceLastPos      = null;
    private boolean packetBounceActive = false;
    private boolean wasOnGround        = false;
    private int     fallFlyCooldown    = 0;

    public ElytraBouncePlus() {
        super(DWAddons.CATEGORY, "elytra-bounce-plus", "Elytra highway speed for diagonal and cardinal 2b2t tunnels. Uses single-packet desync (no flooding).");
    }

    /** Called by both mixins to gate all injections. Requires elytra to be worn. */
    public boolean enabled() {
        return isActive()
            && mc.player != null
            && mc.player.getEquippedStack(EquipmentSlot.CHEST).getItem() == Items.ELYTRA;
    }

    @Override
    public void onActivate() {
        if (mc.player == null) return;
        lastPos            = mc.player.getPos();
        bounceLastPos      = mc.player.getPos();
        packetBounceActive = false;
        fallFlyCooldown    = 0;
        wasOnGround        = ((EntityAccessor) mc.player).isOnGroundAccessor();
        mc.player.setSprinting(true);
    }

    // ─── Tick ─────────────────────────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        boolean onGround = ((EntityAccessor) mc.player).isOnGroundAccessor();
        // Set sprint once on landing rather than every tick (redundant re-sends, ncrnu.md B.2.5).
        if (onGround && !wasOnGround) mc.player.setSprinting(true);

        if (lockYaw.get()) ((EntityAccessor) mc.player).invokeSetYRot(yaw.get().floatValue());
        if (lockPitch.get()) ((EntityAccessor) mc.player).invokeSetXRot(pitch.get().floatValue());

        if (onGround) {
            Vec3d ps = Utils.getPlayerSpeed();
            boolean shouldJump = !motionYBoost.get()
                || new Vec3d(ps.x, 0.0, ps.z).length() < speed.get();
            if (shouldJump) ((LivingEntityInvoker) mc.player).invokeJump();
        }

        // Edge-trigger elytra deploy: only re-send START_FALL_FLYING when not already gliding,
        // and rate-limit it. Spamming the toggle every tick is abnormal and pointless (B.2.1).
        if (fallFlyCooldown > 0) fallFlyCooldown--;
        if (enabled() && !mc.player.isGliding() && fallFlyCooldown <= 0) {
            if (PacketLimiter.send(new ClientCommandC2SPacket(
                    mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING))) {
                fallFlyCooldown = FALL_FLY_COOLDOWN;
            }
        }

        if (packetBounce.get()) {
            handlePacketBounce();
        } else {
            packetBounceActive = false;
        }

        wasOnGround   = onGround;
        bounceLastPos = mc.player.getPos();
    }

    // ─── Motion-Y boost ───────────────────────────────────────────────────────

    @EventHandler
    private void onPlayerMove(PlayerMoveEvent event) {
        if (mc.player == null) return;
        if (event.type != MovementType.SELF) return;
        if (!enabled()) return;
        if (!motionYBoost.get()) return;

        boolean needsCollision = onlyWhileColliding.get() && mode.get() != Mode.CARDINAL;
        if (needsCollision && !mc.player.horizontalCollision) return;

        if (lastPos == null) {
            lastPos = mc.player.getPos();
            return;
        }

        Vec3d diff = mc.player.getPos().subtract(lastPos);
        double speedBps = new Vec3d(diff.x * 20, 0, diff.z * 20).length();

        Timer timer = Modules.get().get(Timer.class);
        if (timer != null && timer.isActive()) speedBps *= timer.getMultiplier();

        if (((EntityAccessor) mc.player).isOnGroundAccessor() && mc.player.isSprinting() && speedBps < speed.get()) {
            if (speedBps > 20 || tunnelBounce.get()) {
                Vec3d vel = mc.player.getVelocity();
                // Hysteresis: only zero Y when there's actual vertical motion to cancel.
                // Zeroing an already-flat Y every tick is a no-op that causes stutter (B.2.3).
                if (Math.abs(vel.y) > 0.1) {
                    event.movement = new Vec3d(event.movement.x, 0.0, event.movement.z);
                    mc.player.setVelocity(vel.x, 0.0, vel.z);
                }
            }
        }

        lastPos = mc.player.getPos();
    }

    // ─── Packet Desync — modify the single move packet the client already sends ──
    // This is the ONLY packet path: zero extra packets, which is the only footprint that
    // survives Grim on 2b2t. The old flood/burst paths have been removed (ncrnu.md B.2.2).

    @EventHandler
    private void onSendPacket(PacketEvent.Send event) {
        if (mc.player == null) return;
        if (!packetBounce.get() || !packetBounceActive) return;
        if (!(event.packet instanceof PlayerMoveC2SPacket)) return;

        IServerboundMovePlayerPacket iPacket = (IServerboundMovePlayerPacket) event.packet;

        Vec3d pos = mc.player.getPos();
        Vec3d vel = mc.player.getVelocity();
        HighwayUtil.Axis axis = HighwayUtil.detect(pos, vel, HIGHWAY_TOLERANCE);
        double strength  = desyncStrength.get();

        double newX = iPacket.getX();
        double newZ = iPacket.getZ();

        if (axis == HighwayUtil.Axis.X_AXIS) {
            newX += vel.x > 0 ? strength : -strength;
        } else if (axis == HighwayUtil.Axis.Z_AXIS) {
            newZ += vel.z > 0 ? strength : -strength;
        } else {
            double h = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
            if (h > 0) { newX += vel.x / h * strength; newZ += vel.z / h * strength; }
        }

        iPacket.setX(newX);
        iPacket.setZ(newZ);
        if (frictionBypass.get()) iPacket.setOnGround(false);
    }

    // ─── Packet Bounce handler ────────────────────────────────────────────────

    private void handlePacketBounce() {
        if (mc.player == null) return;
        Vec3d currentPos = mc.player.getPos();
        Vec3d velocity   = mc.player.getVelocity();

        double spd = velocity.horizontalLength() * 20.0;

        Vec3d   prev      = bounceLastPos != null ? bounceLastPos : currentPos;
        double movedX    = Math.abs(currentPos.x - prev.x);
        double movedZ    = Math.abs(currentPos.z - prev.z);
        double expectedX = Math.abs(velocity.x);
        double expectedZ = Math.abs(velocity.z);

        boolean againstWallX = expectedX > 0.01 && movedX < expectedX * 0.2;
        boolean againstWallZ = expectedZ > 0.01 && movedZ < expectedZ * 0.2;
        boolean againstWall  = againstWallX || againstWallZ;

        if (spd >= speedThreshold.get()) packetBounceActive = true;
        if (!packetBounceActive) return;

        // Friction bypass (onGround=false) is applied in onSendPacket by editing the single
        // move packet — no extra packet is sent here.

        HighwayUtil.Axis axis   = HighwayUtil.detect(currentPos, velocity, HIGHWAY_TOLERANCE);
        double desiredTickSpeed = targetSpeed.get() / 20.0;
        double boost            = wallBoostMultiplier.get();
        double newVx            = velocity.x;
        double newVz            = velocity.z;

        if (axis == HighwayUtil.Axis.X_AXIS) {
            if (againstWallZ) {
                double sign = Math.signum(velocity.x) != 0
                    ? Math.signum(velocity.x) : Math.signum(currentPos.x);
                newVx = sign * desiredTickSpeed;
                newVz = -velocity.z * 0.2;
            } else {
                if (velocityKeepAlive.get()) {
                    newVx = Math.signum(velocity.x) != 0
                        ? Math.signum(velocity.x) * desiredTickSpeed : desiredTickSpeed;
                }
                newVz = 0;
            }

        } else if (axis == HighwayUtil.Axis.Z_AXIS) {
            if (againstWallX) {
                double sign = Math.signum(velocity.z) != 0
                    ? Math.signum(velocity.z) : Math.signum(currentPos.z);
                newVz = sign * desiredTickSpeed;
                newVx = -velocity.x * 0.2;
            } else {
                if (velocityKeepAlive.get()) {
                    newVz = Math.signum(velocity.z) != 0
                        ? Math.signum(velocity.z) * desiredTickSpeed : desiredTickSpeed;
                }
                newVx = 0;
            }

        } else {
            if (!againstWall) {
                if (velocityKeepAlive.get()) {
                    double currentH = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
                    if (currentH > 0) {
                        double scale = desiredTickSpeed / currentH;
                        newVx = velocity.x * scale;
                        newVz = velocity.z * scale;
                    }
                }
            } else {
                newVx = againstWallX ? -velocity.x * boost : velocity.x * boost;
                newVz = againstWallZ ? -velocity.z * boost : velocity.z * boost;

                double currentH = Math.sqrt(newVx * newVx + newVz * newVz);
                if (currentH > 0) {
                    double scale = desiredTickSpeed / currentH;
                    newVx *= scale;
                    newVz *= scale;
                }
            }
        }

        double newVy = mc.player.isOnGround() ? 0.0 : velocity.y;
        mc.player.setVelocity(newVx, newVy, newVz);
        // No flooding and no burst packets — the velocity set above plus the single-packet
        // edit in onSendPacket is the entire effect. Sprint is set on landing in onTick.
    }
}
