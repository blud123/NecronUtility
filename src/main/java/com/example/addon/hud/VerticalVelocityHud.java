package com.example.addon.hud;

import com.example.addon.DWAddons;
import com.example.addon.modules.VerticalVelocityTracker;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shows the {@link VerticalVelocityTracker}'s live vertical velocity and acceleration. Formats the
 * values itself (decimals / units / labels / per-line layout) so it no longer depends on the
 * module's info string, and inherits scale/shadow/alignment/padding/background from {@link DwHud}.
 */
public class VerticalVelocityHud extends DwHud {

    public static final HudElementInfo<VerticalVelocityHud> INFO = new HudElementInfo<>(
        DWAddons.HUD_GROUP, "vertical-velocity",
        "Shows vertical velocity and acceleration from the Vertical Tracker module.",
        VerticalVelocityHud::new);

    public enum ColorMode { Static, ByDirection }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgColor   = settings.createGroup("Color");

    // ── Content ──────────────────────────────────────────────────────────
    private final Setting<Boolean> showVelocity = sgGeneral.add(new BoolSetting.Builder()
        .name("show-velocity")
        .description("Show vertical velocity (b/s).")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> showAccel = sgGeneral.add(new BoolSetting.Builder()
        .name("show-acceleration")
        .description("Show vertical acceleration (b/s²).")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> multiline = sgGeneral.add(new BoolSetting.Builder()
        .name("multiline")
        .description("Put velocity and acceleration on separate lines.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> labels = sgGeneral.add(new BoolSetting.Builder()
        .name("labels")
        .description("Prefix each value with a label (vY / aY).")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> units = sgGeneral.add(new BoolSetting.Builder()
        .name("units")
        .description("Append the b/s and b/s² unit suffixes.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> decimals = sgGeneral.add(new IntSetting.Builder()
        .name("decimals")
        .description("Decimal places shown.")
        .defaultValue(1)
        .min(0).max(3)
        .sliderRange(0, 3)
        .build());

    private final Setting<Boolean> showWhenInactive = sgGeneral.add(new BoolSetting.Builder()
        .name("show-when-inactive")
        .description("Keep the element visible (showing zeros) when the tracker module is off.")
        .defaultValue(false)
        .build());

    // ── Color ────────────────────────────────────────────────────────────
    private final Setting<ColorMode> colorMode = sgColor.add(new EnumSetting.Builder<ColorMode>()
        .name("color-mode")
        .description("Static = one colour; ByDirection = colour each value by its sign (up / down / ~0).")
        .defaultValue(ColorMode.Static)
        .build());

    private final Setting<SettingColor> color = sgColor.add(new ColorSetting.Builder()
        .name("color")
        .description("Text colour (Static mode).")
        .defaultValue(new SettingColor(255, 255, 255))
        .visible(() -> colorMode.get() == ColorMode.Static)
        .build());

    private final Setting<SettingColor> upColor = sgColor.add(new ColorSetting.Builder()
        .name("up-color")
        .description("Colour when the value is positive (rising).")
        .defaultValue(new SettingColor(80, 255, 80))
        .visible(() -> colorMode.get() == ColorMode.ByDirection)
        .build());

    private final Setting<SettingColor> downColor = sgColor.add(new ColorSetting.Builder()
        .name("down-color")
        .description("Colour when the value is negative (falling).")
        .defaultValue(new SettingColor(255, 80, 80))
        .visible(() -> colorMode.get() == ColorMode.ByDirection)
        .build());

    private final Setting<SettingColor> zeroColor = sgColor.add(new ColorSetting.Builder()
        .name("zero-color")
        .description("Colour when the value is near zero.")
        .defaultValue(new SettingColor(200, 200, 200))
        .visible(() -> colorMode.get() == ColorMode.ByDirection)
        .build());

    public VerticalVelocityHud() {
        super(INFO);
        addAppearanceSettings();
    }

    @Override
    public void render(HudRenderer renderer) {
        if (!VerticalVelocityTracker.isTracking() && !showWhenInactive.get()) {
            setSize(0, 0);
            return;
        }
        if (!showVelocity.get() && !showAccel.get()) {
            setSize(0, 0);
            return;
        }

        double v = VerticalVelocityTracker.velocity();
        double a = VerticalVelocityTracker.acceleration();

        String velText = (labels.get() ? "vY: " : "") + num(v) + (units.get() ? " b/s" : "");
        String accText = (labels.get() ? "aY: " : "") + num(a) + (units.get() ? " b/s²" : "");

        List<Line> lines = new ArrayList<>();
        if (multiline.get()) {
            if (showVelocity.get()) lines.add(new Line(velText, colorFor(v)));
            if (showAccel.get())    lines.add(new Line(accText, colorFor(a)));
        } else {
            StringBuilder sb = new StringBuilder();
            if (showVelocity.get()) sb.append(velText);
            if (showAccel.get()) {
                if (sb.length() > 0) sb.append("  ");
                sb.append(accText);
            }
            // Single-line colour follows velocity when shown, else acceleration.
            lines.add(new Line(sb.toString(), colorFor(showVelocity.get() ? v : a)));
        }

        renderLines(renderer, lines);
    }

    private String num(double value) {
        return String.format(Locale.ROOT, "%+." + decimals.get() + "f", value);
    }

    private Color colorFor(double value) {
        if (colorMode.get() == ColorMode.Static) return color.get();
        if (Math.abs(value) < 0.05) return zeroColor.get();
        return value > 0 ? upColor.get() : downColor.get();
    }
}
