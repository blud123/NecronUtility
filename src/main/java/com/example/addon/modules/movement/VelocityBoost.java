package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.phys.Vec3;

/**
 * Keybind-triggered speed burst in the look direction, spread over several ticks so each tick's
 * velocity delta stays within 2b2t's movement tolerance. Lets vanilla send the resulting move
 * packet — never crafts raw position packets.
 */
public class VelocityBoost extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Keybind> keybind = sgGeneral.add(new KeybindSetting.Builder()
        .name("keybind")
        .description("Press to trigger a boost.")
        .defaultValue(Keybind.none())
        .action(this::trigger)
        .build());

    private final Setting<Boolean> holdToBoost = sgGeneral.add(new BoolSetting.Builder()
        .name("hold-to-boost")
        .description("Repeatedly triggers the boost while you hold the keybind.")
        .defaultValue(false)
        .build());

    private final Setting<Integer> repeatDelay = sgGeneral.add(new IntSetting.Builder()
        .name("repeat-delay")
        .description("Ticks between each boost while holding the keybind.")
        .defaultValue(10)
        .min(1).max(40)
        .sliderRange(1, 40)
        .visible(holdToBoost::get)
        .build());

    private final Setting<Double> strength = sgGeneral.add(new DoubleSetting.Builder()
        .name("strength")
        .description("Boost magnitude in the look direction.")
        .defaultValue(1.5)
        .min(0.1).max(150.0)
        .sliderRange(0.1, 150.0)
        .build());

    private final Setting<Double> vertical = sgGeneral.add(new DoubleSetting.Builder()
        .name("vertical")
        .description("Extra upward velocity.")
        .defaultValue(0.2)
        .min(-1.0).max(100.0)
        .sliderRange(-1.0, 100.0)
        .build());

    private final Setting<Integer> ticks = sgGeneral.add(new IntSetting.Builder()
        .name("ticks")
        .description("Spread the boost over this many ticks. Higher = safer.")
        .defaultValue(3)
        .min(1).max(40)
        .sliderRange(1, 40)
        .build());

    private final Setting<Boolean> requireElytra = sgGeneral.add(new BoolSetting.Builder()
        .name("require-elytra")
        .description("Only boost while gliding.")
        .defaultValue(true)
        .build());

    private int remaining = 0;
    private int holdTimer = 0;

    public VelocityBoost() {
        super(AddonTemplate.CATEGORY, "velocity-boost",
            "Keybind speed burst in the look direction, spread over ticks to stay within tolerance.");
    }

    @Override
    public void onActivate() {
        remaining = 0;
        holdTimer = 0;
    }

    private void trigger() {
        if (!isActive() || mc.player == null) return;
        if (requireElytra.get() && !mc.player.isFallFlying()) return;

        remaining = ticks.get();
        holdTimer = repeatDelay.get(); // Resets the delay timer so it doesn't double-fire instantly when initially pressed
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        // --- HOLD-TO-BOOST LOGIC ---
        if (holdToBoost.get() && keybind.get().isPressed()) {
            if (holdTimer <= 0) {
                trigger();
            } else {
                holdTimer--;
            }
        } else {
            holdTimer = 0; // Reset if the key is released
        }
        // ---------------------------

        if (remaining <= 0) return;

        if (requireElytra.get() && !mc.player.isFallFlying()) {
            remaining = 0;
            return;
        }

        int n = Math.max(1, ticks.get());
        Vec3 look = mc.player.getLookAngle();
        Vec3 v = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(
            v.x + look.x * strength.get() / n,
            v.y + vertical.get() / n,
            v.z + look.z * strength.get() / n);

        remaining--;
    }
}
