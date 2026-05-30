package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import com.example.addon.mixin.EntityAccessor;
import com.example.addon.mixin.IServerboundMovePlayerPacket;
import com.example.addon.mixin.LivingEntityInvoker;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.world.Timer;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class ElytraBouncePlus extends Module {

    public enum Mode { DIAGONAL, CARDINAL }

    private enum HighwayAxis { X_AXIS, Z_AXIS, NONE }

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

    private final Setting<Double> bounceOffsetStep = sgPacket.add(new DoubleSetting.Builder()
        .name("bounce-offset-step")
        .description("How far each packet nudges you away from the wall per tick.")
        .defaultValue(0.1)
        .min(0.01).max(0.5)
        .sliderRange(0.01, 0.5)
        .build());

    private final Setting<Integer> packetBurst = sgPacket.add(new IntSetting.Builder()
        .name("packet-burst")
        .description("Number of position packets sent per tick during a wall bounce.")
        .defaultValue(3)
        .min(1).max(8)
        .sliderRange(1, 8)
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

    private final Setting<Integer> positionFlood = sgPacket.add(new IntSetting.Builder()
        .name("position-flood")
        .description("Extra position packets sent per tick ahead of the player on the highway axis.")
        .defaultValue(4)
        .min(1).max(10)
        .sliderRange(1, 10)
        .build());

    private final Setting<Double> floodStepSize = sgPacket.add(new DoubleSetting.Builder()
        .name("flood-step-size")
        .description("Distance per flooded position packet ahead of the player.")
        .defaultValue(0.2)
        .min(0.05).max(1.0)
        .sliderRange(0.05, 1.0)
        .build());

    // ─── Packet Desync ────────────────────────────────────────────────────────

    private final Setting<Boolean> packetDesync = sgDesync.add(new BoolSetting.Builder()
        .name("packet-desync")
        .description("Modify the existing movement packet instead of sending extra ones (lower packet footprint).")
        .defaultValue(false)
        .visible(() -> packetBounce.get())
        .build());

    private final Setting<Double> desyncStrength = sgDesync.add(new DoubleSetting.Builder()
        .name("desync-strength")
        .description("How far ahead to nudge the intercepted position packet each tick.")
        .defaultValue(0.15)
        .min(0.01).max(0.5)
        .sliderRange(0.01, 0.5)
        .visible(() -> packetBounce.get() && packetDesync.get())
        .build());

    private final Setting<Boolean> groundPhaseDesync = sgDesync.add(new BoolSetting.Builder()
        .name("ground-phase")
        .description("Dip Y in the intercepted packet to trigger inside-solid velocity bypass.")
        .defaultValue(true)
        .visible(() -> packetBounce.get() && packetDesync.get())
        .build());

    private final Setting<Double> groundPhaseDip = sgDesync.add(new DoubleSetting.Builder()
        .name("ground-phase-dip")
        .description("How far below ground to dip in the intercepted packet.")
        .defaultValue(0.05)
        .min(0.01).max(0.15)
        .sliderRange(0.01, 0.15)
        .visible(() -> packetBounce.get() && packetDesync.get() && groundPhaseDesync.get())
        .build());

    private final Setting<Boolean> yStagger = sgDesync.add(new BoolSetting.Builder()
        .name("y-stagger")
        .description("Alternate Y slightly each tick to confuse position validation.")
        .defaultValue(true)
        .visible(() -> packetBounce.get() && packetDesync.get())
        .build());

    private final Setting<Double> yStaggerAmount = sgDesync.add(new DoubleSetting.Builder()
        .name("y-stagger-amount")
        .description("Y stagger magnitude per tick.")
        .defaultValue(0.02)
        .min(0.005).max(0.08)
        .sliderRange(0.005, 0.08)
        .visible(() -> packetBounce.get() && packetDesync.get() && yStagger.get())
        .build());

    // ─── State ────────────────────────────────────────────────────────────────

    private Vec3    lastPos            = null;
    private Vec3    bounceLastPos      = null;
    private boolean packetBounceActive = false;
    private boolean staggerUp          = true;

    public ElytraBouncePlus() {
        super(AddonTemplate.CATEGORY, "elytra-bounce-plus", "Elytra highway speed for diagonal and cardinal 2b2t tunnels.");
    }

    /** Called by both mixins to gate all injections. Requires elytra to be worn. */
    public boolean enabled() {
        return isActive()
            && mc.player != null
            && mc.player.getItemBySlot(EquipmentSlot.CHEST).getItem() == Items.ELYTRA;
    }

    @Override
    public void onActivate() {
        if (mc.player == null) return;
        lastPos            = mc.player.position();
        bounceLastPos      = mc.player.position();
        packetBounceActive = false;
        staggerUp          = true;
        mc.player.setSprinting(true);
    }

    // ─── Tick ─────────────────────────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        mc.player.setSprinting(true);

        if (lockYaw.get()) ((EntityAccessor) mc.player).invokeSetYRot(yaw.get().floatValue());
        if (lockPitch.get()) ((EntityAccessor) mc.player).invokeSetXRot(pitch.get().floatValue());

        if (((EntityAccessor) mc.player).isOnGroundAccessor()) {
            Vec3 ps = Utils.getPlayerSpeed();
            boolean shouldJump = !motionYBoost.get()
                || new Vec3(ps.x, 0.0, ps.z).length() < speed.get();
            if (shouldJump) ((LivingEntityInvoker) mc.player).invokeJump();
        }

        if (enabled()) {
            mc.player.connection.send(new ServerboundPlayerCommandPacket(
                mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
        }

        if (packetBounce.get()) {
            handlePacketBounce();
            staggerUp = !staggerUp;
        } else {
            packetBounceActive = false;
        }

        bounceLastPos = mc.player.position();
    }

    // ─── Motion-Y boost ───────────────────────────────────────────────────────

    @EventHandler
    private void onPlayerMove(PlayerMoveEvent event) {
        if (mc.player == null) return;
        if (event.type != MoverType.SELF) return;
        if (!enabled()) return;
        if (!motionYBoost.get()) return;

        boolean needsCollision = onlyWhileColliding.get() && mode.get() != Mode.CARDINAL;
        if (needsCollision && !mc.player.horizontalCollision) return;

        if (lastPos == null) {
            lastPos = mc.player.position();
            return;
        }

        Vec3 diff = mc.player.position().subtract(lastPos);
        double speedBps = new Vec3(diff.x * 20, 0, diff.z * 20).length();

        Timer timer = Modules.get().get(Timer.class);
        if (timer != null && timer.isActive()) speedBps *= timer.getMultiplier();

        if (((EntityAccessor) mc.player).isOnGroundAccessor() && mc.player.isSprinting() && speedBps < speed.get()) {
            if (speedBps > 20 || tunnelBounce.get()) {
                event.movement = new Vec3(event.movement.x, 0.0, event.movement.z);
                Vec3 vel = mc.player.getDeltaMovement();
                mc.player.setDeltaMovement(vel.x, 0.0, vel.z);
            }
        }

        lastPos = mc.player.position();
    }

    // ─── Packet Desync — modify existing packet instead of flooding ───────────

    @EventHandler
    private void onSendPacket(PacketEvent.Send event) {
        if (mc.player == null) return;
        if (!packetBounce.get() || !packetDesync.get() || !packetBounceActive) return;
        if (!(event.packet instanceof ServerboundMovePlayerPacket)) return;

        IServerboundMovePlayerPacket iPacket = (IServerboundMovePlayerPacket) event.packet;

        Vec3 pos = mc.player.position();
        Vec3 vel = mc.player.getDeltaMovement();
        HighwayAxis axis = detectHighwayAxis(pos, vel);
        double strength  = desyncStrength.get();

        double newX = iPacket.getX();
        double newY = iPacket.getY();
        double newZ = iPacket.getZ();

        if (axis == HighwayAxis.X_AXIS) {
            newX += vel.x > 0 ? strength : -strength;
        } else if (axis == HighwayAxis.Z_AXIS) {
            newZ += vel.z > 0 ? strength : -strength;
        } else {
            double h = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
            if (h > 0) { newX += vel.x / h * strength; newZ += vel.z / h * strength; }
        }

        if (groundPhaseDesync.get() && mc.player.onGround()) {
            newY -= groundPhaseDip.get();
        }

        if (yStagger.get()) {
            newY += staggerUp ? yStaggerAmount.get() : -yStaggerAmount.get();
        }

        iPacket.setX(newX);
        iPacket.setY(newY);
        iPacket.setZ(newZ);
        if (frictionBypass.get()) iPacket.setOnGround(false);
    }

    // ─── Packet Bounce handler ────────────────────────────────────────────────

    private void handlePacketBounce() {
        if (mc.player == null) return;
        Vec3 currentPos = mc.player.position();
        Vec3 velocity   = mc.player.getDeltaMovement();

        double spd = velocity.horizontalDistance() * 20.0;

        Vec3   prev      = bounceLastPos != null ? bounceLastPos : currentPos;
        double movedX    = Math.abs(currentPos.x - prev.x);
        double movedZ    = Math.abs(currentPos.z - prev.z);
        double expectedX = Math.abs(velocity.x);
        double expectedZ = Math.abs(velocity.z);

        boolean againstWallX = expectedX > 0.01 && movedX < expectedX * 0.2;
        boolean againstWallZ = expectedZ > 0.01 && movedZ < expectedZ * 0.2;
        boolean againstWall  = againstWallX || againstWallZ;

        if (spd >= speedThreshold.get()) packetBounceActive = true;
        if (!packetBounceActive) return;

        // Spoof onGround=false so server skips ground friction.
        // In desync mode this is handled by onSendPacket modifying the existing packet.
        if (frictionBypass.get() && mc.player.onGround() && !packetDesync.get()) {
            mc.player.connection.send(new ServerboundMovePlayerPacket.PosRot(
                currentPos.x, currentPos.y, currentPos.z,
                mc.player.getYRot(), mc.player.getXRot(),
                false, false
            ));
        }

        HighwayAxis axis        = detectHighwayAxis(currentPos, velocity);
        double desiredTickSpeed = targetSpeed.get() / 20.0;
        double boost            = wallBoostMultiplier.get();
        double offset           = bounceOffsetStep.get();
        double newVx            = velocity.x;
        double newVz            = velocity.z;

        if (axis == HighwayAxis.X_AXIS) {
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

        } else if (axis == HighwayAxis.Z_AXIS) {
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

        double newVy = mc.player.onGround() ? 0.0 : velocity.y;
        mc.player.setDeltaMovement(newVx, newVy, newVz);
        if (mc.player.onGround()) mc.player.setSprinting(true);

        // In desync mode the existing packet is modified in onSendPacket — skip flooding.
        if (!packetDesync.get()) {
            if (mc.player.onGround() && mc.player.isSprinting()) {
                sendGroundPhaseFlood(currentPos, newVx, newVz, axis);
            } else {
                sendAirFlood(currentPos, newVx, newVz, axis);
            }
        }

        if (againstWall && !packetDesync.get()) {
            for (int i = 0; i < packetBurst.get(); i++) {
                mc.player.connection.send(new ServerboundMovePlayerPacket.PosRot(
                    currentPos.x + (newVx * offset * (i + 1)),
                    currentPos.y,
                    currentPos.z + (newVz * offset * (i + 1)),
                    mc.player.getYRot(), mc.player.getXRot(),
                    false, false
                ));
            }
        }
    }

    // Sends packets with Y slightly below ground surface to bypass velocity checks.
    // A snap-back packet at real Y resyncs position after.
    private void sendGroundPhaseFlood(Vec3 currentPos, double newVx, double newVz, HighwayAxis axis) {
        int    flood = positionFlood.get();
        double step  = floodStepSize.get();

        for (int i = 1; i <= flood; i++) {
            double floodX = currentPos.x;
            double floodZ = currentPos.z;
            double phaseY = currentPos.y - (0.05 * i);

            if (axis == HighwayAxis.X_AXIS) {
                floodX += newVx * step * i;
            } else if (axis == HighwayAxis.Z_AXIS) {
                floodZ += newVz * step * i;
            } else {
                floodX += newVx * step * i;
                floodZ += newVz * step * i;
            }

            mc.player.connection.send(new ServerboundMovePlayerPacket.PosRot(
                floodX, phaseY, floodZ,
                mc.player.getYRot(), mc.player.getXRot(),
                false, false
            ));
        }

        mc.player.connection.send(new ServerboundMovePlayerPacket.PosRot(
            currentPos.x, currentPos.y, currentPos.z,
            mc.player.getYRot(), mc.player.getXRot(),
            true, false
        ));
    }

    private void sendAirFlood(Vec3 currentPos, double newVx, double newVz, HighwayAxis axis) {
        int    flood = positionFlood.get();
        double step  = floodStepSize.get();

        for (int i = 1; i <= flood; i++) {
            double floodX = currentPos.x;
            double floodZ = currentPos.z;

            if (axis == HighwayAxis.X_AXIS) {
                floodX += newVx * step * i;
            } else if (axis == HighwayAxis.Z_AXIS) {
                floodZ += newVz * step * i;
            } else {
                floodX += newVx * step * i;
                floodZ += newVz * step * i;
            }

            mc.player.connection.send(new ServerboundMovePlayerPacket.PosRot(
                floodX, currentPos.y, floodZ,
                mc.player.getYRot(), mc.player.getXRot(),
                false, false
            ));
        }
    }

    // ─── Highway axis detection ───────────────────────────────────────────────

    private HighwayAxis detectHighwayAxis(Vec3 pos, Vec3 vel) {
        double absVx = Math.abs(vel.x);
        double absVz = Math.abs(vel.z);

        boolean movingAlongX = absVx > absVz * 3.0;
        boolean movingAlongZ = absVz > absVx * 3.0;

        if (!movingAlongX && !movingAlongZ) return HighwayAxis.NONE;

        double tol = 5.0;

        if (movingAlongX) {
            double zOffset = Math.abs(pos.z % 1000);
            if (zOffset < tol || zOffset > 1000 - tol) return HighwayAxis.X_AXIS;
            if (Math.abs(pos.z) < tol) return HighwayAxis.X_AXIS;
        }

        if (movingAlongZ) {
            double xOffset = Math.abs(pos.x % 1000);
            if (xOffset < tol || xOffset > 1000 - tol) return HighwayAxis.Z_AXIS;
            if (Math.abs(pos.x) < tol) return HighwayAxis.Z_AXIS;
        }

        if (movingAlongX) return HighwayAxis.X_AXIS;
        return HighwayAxis.Z_AXIS;
    }
}
