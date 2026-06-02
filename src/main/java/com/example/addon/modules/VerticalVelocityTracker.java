package com.example.addon.modules;

import com.example.addon.DWAddons;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

import java.util.Locale;

/**
 * Purely passive vertical-motion telemetry. It <b>observes and displays</b> vertical velocity and
 * acceleration and never touches the player's movement — no {@code PlayerMoveEvent} handler, no
 * {@code setDeltaMovement}, no {@code IVec3d} writes.
 *
 * <p>Velocity is derived from the real position delta ({@code (y - prevY) * 20}) rather than
 * {@code getDeltaMovement}, which is more accurate for actual movement. Acceleration is the
 * per-tick change in that velocity, scaled to blocks/sec². Values are exposed statically so the
 * companion {@link com.example.addon.hud.VerticalVelocityHud} can render them.
 */
public class VerticalVelocityTracker extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> smoothing = sgGeneral.add(new DoubleSetting.Builder()
        .name("smoothing")
        .description("Exponential moving-average weight for the displayed values. 0 = raw, instantaneous readings.")
        .defaultValue(0.0)
        .min(0.0).max(0.95)
        .sliderRange(0.0, 0.95)
        .build());

    private final Setting<Boolean> showAccel = sgGeneral.add(new BoolSetting.Builder()
        .name("show-acceleration")
        .description("Include vertical acceleration in the info string / HUD.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> actionbar = sgGeneral.add(new BoolSetting.Builder()
        .name("actionbar")
        .description("Also print the readings to the actionbar each tick.")
        .defaultValue(false)
        .build());

    // — Shared, read by the HUD element —
    private static volatile double velocity = 0.0;     // blocks / sec
    private static volatile double acceleration = 0.0; // blocks / sec²
    private static volatile boolean tracking = false;

    public static double velocity()     { return velocity; }
    public static double acceleration() { return acceleration; }
    public static boolean isTracking()  { return tracking; }

    // — Internal state —
    private double prevY = 0.0;
    private double prevVelocity = 0.0;
    private boolean hasPrev = false;

    public VerticalVelocityTracker() {
        super(DWAddons.CATEGORY, "vertical-tracker",
            "Passively tracks and displays vertical velocity and acceleration. Never alters movement.");
    }

    @Override
    public void onActivate() {
        resetState();
    }

    @Override
    public void onDeactivate() {
        tracking = false;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) {
            resetState();
            tracking = false;
            return;
        }

        double y = mc.player.getY();

        // First tick after (re)activation or a null player: seed the baseline, emit nothing.
        if (!hasPrev) {
            prevY = y;
            prevVelocity = 0.0;
            hasPrev = true;
            return;
        }

        double rawVelocity = (y - prevY) * 20.0;          // blocks / sec
        prevY = y;

        double s = smoothing.get();
        double vY = s > 0 ? velocity * s + rawVelocity * (1.0 - s) : rawVelocity;

        double rawAccel = (vY - prevVelocity) * 20.0;     // blocks / sec²
        prevVelocity = vY;
        double aY = s > 0 ? acceleration * s + rawAccel * (1.0 - s) : rawAccel;

        velocity = vY;
        acceleration = aY;
        tracking = true;

        if (actionbar.get()) {
            mc.player.sendMessage(
                net.minecraft.text.Text.literal(infoText(showAccel.get())), true);
        }
    }

    @Override
    public String getInfoString() {
        return isActive() ? infoText(showAccel.get()) : null;
    }

    /** Formats the current readings, e.g. {@code vY: +12.3 b/s  aY: -1.4 b/s²}. */
    public static String infoText(boolean withAccel) {
        String v = String.format(Locale.ROOT, "vY: %+.1f b/s", velocity);
        if (!withAccel) return v;
        return v + String.format(Locale.ROOT, "  aY: %+.1f b/s²", acceleration);
    }

    private void resetState() {
        prevY = 0.0;
        prevVelocity = 0.0;
        hasPrev = false;
        velocity = 0.0;
        acceleration = 0.0;
    }
}
