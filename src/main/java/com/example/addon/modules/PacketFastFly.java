package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import com.example.addon.mixin.LivingEntityInvoker;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
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

public class PacketFastFly extends Module {

    private final SettingGroup sgGeneral    = settings.getDefaultGroup();
    private final SettingGroup sgInterp     = settings.createGroup("Interpolation");
    private final SettingGroup sgHighway    = settings.createGroup("Highway");
    private final SettingGroup sgAntiDetect = settings.createGroup("Anti-Detect");

    // ─── General ──────────────────────────────────────────────────────────────

    private final Setting<Double> targetSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("target-speed")
        .description("Target speed in blocks/sec.")
        .defaultValue(80.0)
        .min(10.0).max(400.0)
        .sliderRange(10.0, 400.0)
        .build());

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Which fly method to use.")
        .defaultValue(Mode.INTERPOLATION)
        .build());

    private final Setting<Boolean> motionYBoost = sgGeneral.add(new BoolSetting.Builder()
        .name("motion-y-boost")
        .description("Cancel Y momentum at movement event level to keep speed horizontal.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> velocityKeepAlive = sgGeneral.add(new BoolSetting.Builder()
        .name("velocity-keep-alive")
        .description("Re-stamp velocity every tick to fight server-side friction.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> frictionBypass = sgGeneral.add(new BoolSetting.Builder()
        .name("friction-bypass")
        .description("Spoof onGround=false so server uses air drag instead of ground friction.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> openElytra = sgGeneral.add(new BoolSetting.Builder()
        .name("open-elytra")
        .description("Send START_FALL_FLYING every tick to keep elytra open.")
        .defaultValue(true)
        .build());

    // ─── Interpolation ────────────────────────────────────────────────────────

    private final Setting<Integer> lookaheadPackets = sgInterp.add(new IntSetting.Builder()
        .name("lookahead-packets")
        .description("Number of packets sent ahead of the player per tick.")
        .defaultValue(6)
        .min(1).max(20)
        .sliderRange(1, 20)
        .build());

    private final Setting<Double> lookaheadDistance = sgInterp.add(new DoubleSetting.Builder()
        .name("lookahead-distance")
        .description("How far ahead each packet is placed (blocks per step).")
        .defaultValue(0.25)
        .min(0.05).max(2.0)
        .sliderRange(0.05, 2.0)
        .build());

    private final Setting<Integer> anchorPackets = sgInterp.add(new IntSetting.Builder()
        .name("anchor-packets")
        .description("Real-position anchor packets sent after the lookahead burst to keep the server in sync.")
        .defaultValue(2)
        .min(0).max(5)
        .sliderRange(0, 5)
        .build());

    private final Setting<Boolean> yStagger = sgInterp.add(new BoolSetting.Builder()
        .name("y-stagger")
        .description("Alternate Y position slightly between packets to confuse position validation.")
        .defaultValue(true)
        .build());

    private final Setting<Double> yStaggerAmount = sgInterp.add(new DoubleSetting.Builder()
        .name("y-stagger-amount")
        .description("How much to stagger Y per packet.")
        .defaultValue(0.03)
        .min(0.01).max(0.1)
        .sliderRange(0.01, 0.1)
        .build());

    private final Setting<Boolean> groundPhase = sgInterp.add(new BoolSetting.Builder()
        .name("ground-phase")
        .description("Dip packets slightly below ground to trigger the inside-solid velocity bypass.")
        .defaultValue(true)
        .build());

    private final Setting<Double> groundPhaseDip = sgInterp.add(new DoubleSetting.Builder()
        .name("ground-phase-dip")
        .description("How far below ground to dip per burst packet.")
        .defaultValue(0.05)
        .min(0.01).max(0.2)
        .sliderRange(0.01, 0.2)
        .build());

    // ─── Highway ──────────────────────────────────────────────────────────────

    private final Setting<Boolean> highwayMode = sgHighway.add(new BoolSetting.Builder()
        .name("highway-mode")
        .description("Lock movement to detected cardinal highway axis.")
        .defaultValue(true)
        .build());

    private final Setting<Double> highwayTolerance = sgHighway.add(new DoubleSetting.Builder()
        .name("highway-tolerance")
        .description("How close to a highway centerline to be considered on it (blocks).")
        .defaultValue(5.0)
        .min(1.0).max(20.0)
        .sliderRange(1.0, 20.0)
        .build());

    private final Setting<Boolean> axisCorrection = sgHighway.add(new BoolSetting.Builder()
        .name("axis-correction")
        .description("Suppress velocity on the perpendicular axis to prevent drift off the highway.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> bounceAssist = sgHighway.add(new BoolSetting.Builder()
        .name("bounce-assist")
        .description("Jump at the right tick to maintain highway bounce cadence.")
        .defaultValue(true)
        .build());

    // ─── Anti-Detect ──────────────────────────────────────────────────────────

    private final Setting<Boolean> timerSync = sgAntiDetect.add(new BoolSetting.Builder()
        .name("timer-sync")
        .description("Scale per-tick speed to match Meteor Timer module if active.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> rubberBandRecovery = sgAntiDetect.add(new BoolSetting.Builder()
        .name("rubberband-recovery")
        .description("Detect and recover from server rubberbands automatically.")
        .defaultValue(true)
        .build());

    private final Setting<Double> rubberBandThreshold = sgAntiDetect.add(new DoubleSetting.Builder()
        .name("rubberband-threshold")
        .description("Position delta that indicates a rubberband has occurred (blocks).")
        .defaultValue(3.0)
        .min(1.0).max(10.0)
        .sliderRange(1.0, 10.0)
        .build());

    private final Setting<Integer> recoveryTicks = sgAntiDetect.add(new IntSetting.Builder()
        .name("recovery-ticks")
        .description("Ticks to pause packet flood after a rubberband before resuming.")
        .defaultValue(10)
        .min(0).max(40)
        .sliderRange(0, 40)
        .build());

    public enum Mode { INTERPOLATION, BOOST_FLY, STEP_HEIGHT, GROUND_PHASE }

    private enum HighwayAxis { X_AXIS, Z_AXIS, NONE }

    // ─── State ────────────────────────────────────────────────────────────────

    private Vec3    lastPos            = Vec3.ZERO;
    private Vec3    lastServerPos      = Vec3.ZERO;
    private int     rubberBandCooldown = 0;
    private boolean staggerUp          = true;

    public PacketFastFly() {
        super(AddonTemplate.CATEGORY, "packet-fast-fly",
            "Advanced packet position interpolation fly for 2b2t highways.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null) return;
        lastPos            = mc.player.position();
        lastServerPos      = mc.player.position();
        rubberBandCooldown = 0;
    }

    // ─── Motion-Y cancel ──────────────────────────────────────────────────────

    @EventHandler
    private void onPlayerMove(PlayerMoveEvent event) {
        if (event.type != MoverType.SELF) return;
        if (!motionYBoost.get() || mc.player == null) return;

        double spd = mc.player.getDeltaMovement().horizontalDistance() * 20.0;

        if (mc.player.onGround() && mc.player.isSprinting() && spd > 20) {
            event.movement = new Vec3(event.movement.x, 0.0, event.movement.z);
            Vec3 v = mc.player.getDeltaMovement();
            mc.player.setDeltaMovement(v.x, 0.0, v.z);
        }
    }

    // ─── Tick ─────────────────────────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        Vec3 currentPos = mc.player.position();
        Vec3 velocity   = mc.player.getDeltaMovement();

        if (rubberBandRecovery.get()) {
            double serverDelta = currentPos.distanceTo(lastServerPos);
            if (serverDelta > rubberBandThreshold.get() && rubberBandCooldown == 0) {
                rubberBandCooldown = recoveryTicks.get();
            }
            if (rubberBandCooldown > 0) {
                rubberBandCooldown--;
                lastPos       = currentPos;
                lastServerPos = currentPos;
                return;
            }
        }

        mc.player.setSprinting(true);

        if (openElytra.get()) {
            mc.player.connection.send(new ServerboundPlayerCommandPacket(
                mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING
            ));
        }

        HighwayAxis axis      = detectHighwayAxis(currentPos, velocity);
        double      tickSpeed = getEffectiveTickSpeed();
        double[]    tv        = getTargetVelocity(velocity, axis, tickSpeed);
        double      newVx     = tv[0];
        double      newVz     = tv[1];

        if (velocityKeepAlive.get()) {
            mc.player.setDeltaMovement(newVx, velocity.y, newVz);
        }

        if (frictionBypass.get()) {
            mc.player.connection.send(new ServerboundMovePlayerPacket.PosRot(
                currentPos.x, currentPos.y, currentPos.z,
                mc.player.getYRot(), mc.player.getXRot(), false, false
            ));
        }

        switch (mode.get()) {
            case INTERPOLATION -> doInterpolation(currentPos, newVx, newVz, axis);
            case BOOST_FLY     -> doBoostFly(currentPos);
            case STEP_HEIGHT   -> doStepHeight(currentPos);
            case GROUND_PHASE  -> doGroundPhase(currentPos, newVx, newVz, axis);
        }

        if (bounceAssist.get() && mc.player.onGround()) {
            ((LivingEntityInvoker) mc.player).invokeJump();
        }

        lastPos       = currentPos;
        lastServerPos = mc.player.position();
    }

    // ─── Mode: Interpolation ──────────────────────────────────────────────────

    private void doInterpolation(Vec3 pos, double vx, double vz, HighwayAxis axis) {
        int    packets = lookaheadPackets.get();
        double step    = lookaheadDistance.get();
        double dip     = groundPhaseDip.get();
        double stagger = yStaggerAmount.get();

        for (int i = 1; i <= packets; i++) {
            double fx = pos.x;
            double fz = pos.z;
            double fy = pos.y;

            if (axis == HighwayAxis.X_AXIS)      { fx += vx * step * i; }
            else if (axis == HighwayAxis.Z_AXIS) { fz += vz * step * i; }
            else                                  { fx += vx * step * i; fz += vz * step * i; }

            if (groundPhase.get() && mc.player.onGround()) fy -= dip * i;
            if (yStagger.get()) fy += staggerUp ? stagger * i : -stagger * i;

            mc.player.connection.send(new ServerboundMovePlayerPacket.PosRot(
                fx, fy, fz,
                mc.player.getYRot(), mc.player.getXRot(), false, false
            ));
        }

        staggerUp = !staggerUp;

        for (int i = 0; i < anchorPackets.get(); i++) {
            mc.player.connection.send(new ServerboundMovePlayerPacket.PosRot(
                pos.x, pos.y, pos.z,
                mc.player.getYRot(), mc.player.getXRot(), mc.player.onGround(), false
            ));
        }
    }

    // ─── Mode: Boost Fly ──────────────────────────────────────────────────────

    private void doBoostFly(Vec3 pos) {
        double step   = lookaheadDistance.get();
        double yawRad = Math.toRadians(mc.player.getYRot());

        for (int i = 1; i <= lookaheadPackets.get(); i++) {
            mc.player.connection.send(new ServerboundMovePlayerPacket.PosRot(
                pos.x + (-Math.sin(yawRad) * step * i),
                pos.y,
                pos.z + ( Math.cos(yawRad) * step * i),
                mc.player.getYRot(), mc.player.getXRot(), false, false
            ));
        }

        for (int i = 0; i < anchorPackets.get(); i++) {
            mc.player.connection.send(new ServerboundMovePlayerPacket.PosRot(
                pos.x, pos.y, pos.z,
                mc.player.getYRot(), mc.player.getXRot(), false, false
            ));
        }
    }

    // ─── Mode: Step Height ────────────────────────────────────────────────────

    private void doStepHeight(Vec3 pos) {
        double yawRad = Math.toRadians(mc.player.getYRot());
        double sx     = -Math.sin(yawRad) * lookaheadDistance.get();
        double sz     =  Math.cos(yawRad) * lookaheadDistance.get();

        mc.player.connection.send(new ServerboundMovePlayerPacket.PosRot(
            pos.x + sx, pos.y + 0.6, pos.z + sz,
            mc.player.getYRot(), mc.player.getXRot(), false, false
        ));
        mc.player.connection.send(new ServerboundMovePlayerPacket.PosRot(
            pos.x + sx * 2, pos.y + 0.6, pos.z + sz * 2,
            mc.player.getYRot(), mc.player.getXRot(), false, false
        ));
        mc.player.connection.send(new ServerboundMovePlayerPacket.PosRot(
            pos.x + sx * 2, pos.y, pos.z + sz * 2,
            mc.player.getYRot(), mc.player.getXRot(), true, false
        ));
    }

    // ─── Mode: Ground Phase ───────────────────────────────────────────────────

    private void doGroundPhase(Vec3 pos, double vx, double vz, HighwayAxis axis) {
        double dip  = groundPhaseDip.get();
        double step = lookaheadDistance.get();

        for (int i = 1; i <= lookaheadPackets.get(); i++) {
            double fx = pos.x;
            double fz = pos.z;

            if (axis == HighwayAxis.X_AXIS)      { fx += vx * step * i; }
            else if (axis == HighwayAxis.Z_AXIS) { fz += vz * step * i; }
            else                                  { fx += vx * step * i; fz += vz * step * i; }

            mc.player.connection.send(new ServerboundMovePlayerPacket.PosRot(
                fx, pos.y - (dip * i), fz,
                mc.player.getYRot(), mc.player.getXRot(), false, false
            ));
        }

        mc.player.connection.send(new ServerboundMovePlayerPacket.PosRot(
            pos.x, pos.y, pos.z,
            mc.player.getYRot(), mc.player.getXRot(), true, false
        ));
    }

    // ─── Velocity helpers ─────────────────────────────────────────────────────

    private double[] getTargetVelocity(Vec3 vel, HighwayAxis axis, double tickSpeed) {
        double newVx = vel.x;
        double newVz = vel.z;

        if (highwayMode.get()) {
            if (axis == HighwayAxis.X_AXIS) {
                double sign = Math.signum(vel.x) != 0 ? Math.signum(vel.x) : 1;
                newVx = sign * tickSpeed;
                newVz = axisCorrection.get() ? 0 : vel.z;
            } else if (axis == HighwayAxis.Z_AXIS) {
                double sign = Math.signum(vel.z) != 0 ? Math.signum(vel.z) : 1;
                newVz = sign * tickSpeed;
                newVx = axisCorrection.get() ? 0 : vel.x;
            } else {
                double h = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
                if (h > 0) { newVx = vel.x / h * tickSpeed; newVz = vel.z / h * tickSpeed; }
            }
        } else {
            double yawRad = Math.toRadians(mc.player.getYRot());
            newVx = -Math.sin(yawRad) * tickSpeed;
            newVz =  Math.cos(yawRad) * tickSpeed;
        }

        return new double[]{ newVx, newVz };
    }

    private double getEffectiveTickSpeed() {
        double base = targetSpeed.get() / 20.0;
        if (timerSync.get()) {
            Timer timer = Modules.get().get(Timer.class);
            if (timer != null && timer.isActive()) base /= timer.getMultiplier();
        }
        return base;
    }

    // ─── Highway axis detection ───────────────────────────────────────────────

    private HighwayAxis detectHighwayAxis(Vec3 pos, Vec3 vel) {
        double absVx = Math.abs(vel.x);
        double absVz = Math.abs(vel.z);

        boolean movingX = absVx > absVz * 3.0;
        boolean movingZ = absVz > absVx * 3.0;

        if (!movingX && !movingZ) return HighwayAxis.NONE;

        double tol = highwayTolerance.get();

        if (movingX) {
            double zOff = Math.abs(pos.z % 1000);
            if (Math.abs(pos.z) < tol || zOff < tol || zOff > 1000 - tol) return HighwayAxis.X_AXIS;
        }
        if (movingZ) {
            double xOff = Math.abs(pos.x % 1000);
            if (Math.abs(pos.x) < tol || xOff < tol || xOff > 1000 - tol) return HighwayAxis.Z_AXIS;
        }

        return movingX ? HighwayAxis.X_AXIS : HighwayAxis.Z_AXIS;
    }
}
