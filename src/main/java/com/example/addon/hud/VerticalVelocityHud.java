package com.example.addon.hud;

import com.example.addon.DWAddons;
import com.example.addon.modules.VerticalVelocityTracker;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

/**
 * Shows the {@link VerticalVelocityTracker}'s live vertical velocity and acceleration on screen.
 * Read-only — it just renders the values the (passive) tracker module publishes.
 */
public class VerticalVelocityHud extends HudElement {

    public static final HudElementInfo<VerticalVelocityHud> INFO = new HudElementInfo<>(
        DWAddons.HUD_GROUP, "vertical-velocity",
        "Shows vertical velocity and acceleration from the Vertical Tracker module.",
        VerticalVelocityHud::new);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> showAccel = sgGeneral.add(new BoolSetting.Builder()
        .name("show-acceleration")
        .description("Include vertical acceleration.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> showWhenInactive = sgGeneral.add(new BoolSetting.Builder()
        .name("show-when-inactive")
        .description("Keep the element visible (showing zeros) when the tracker module is off.")
        .defaultValue(false)
        .build());

    private final Setting<SettingColor> color = sgGeneral.add(new ColorSetting.Builder()
        .name("color")
        .description("Text colour.")
        .defaultValue(new SettingColor(255, 255, 255))
        .build());

    public VerticalVelocityHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        if (!VerticalVelocityTracker.isTracking() && !showWhenInactive.get()) {
            setSize(0, 0);
            return;
        }

        String text = VerticalVelocityTracker.infoText(showAccel.get());
        setSize(renderer.textWidth(text, true), renderer.textHeight(true));
        renderer.text(text, x, y, color.get(), true);
    }
}
