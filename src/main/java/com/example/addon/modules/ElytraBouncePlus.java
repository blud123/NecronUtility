package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import com.example.addon.mixin.EntityAccessor;
import com.example.addon.mixin.LivingEntityInvoker;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.world.Timer;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class ElytraBouncePlus extends Module {

    public enum Mode { DIAGONAL, CARDINAL }

    private final SettingGroup sg = settings.getDefaultGroup();

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

    private Vec3 lastPos = null;

    public ElytraBouncePlus() {
        super(AddonTemplate.CATEGORY, "elytra-bounce-plus", "Elytra highway speed for diagonal and cardinal 2b2t tunnels.");
    }

    /** Called by both mixins to gate all injections. */
    public boolean enabled() {
        return isActive() && mc.player != null;
    }

    @Override
    public void onActivate() {
        if (mc.player == null) return;
        lastPos = mc.player.position();
        mc.player.setSprinting(true);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        mc.player.setSprinting(true);

        if (lockYaw.get()) {
            ((EntityAccessor) mc.player).setYRot(yaw.get().floatValue());
            ((EntityAccessor) mc.player).setYRotO(yaw.get().floatValue());
        }
        if (lockPitch.get()) {
            ((EntityAccessor) mc.player).setXRot(pitch.get().floatValue());
            ((EntityAccessor) mc.player).setXRotO(pitch.get().floatValue());
        }

        if (((EntityAccessor) mc.player).isOnGroundAccessor()) ((LivingEntityInvoker) mc.player).invokeJump();

        // Keep the server in elytra-validation mode every tick so it accepts our velocity.
        mc.player.connection.send(new ServerboundPlayerCommandPacket(
            mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
    }

    @EventHandler
    private void onPlayerMove(PlayerMoveEvent event) {
        if (mc.player == null) return;
        if (event.type != MoverType.SELF) return;
        if (!enabled()) return;
        if (!motionYBoost.get()) return;

        // Cardinal mode never needs wall contact; diagonal mode respects the toggle.
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
}
