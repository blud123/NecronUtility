package com.example.addon.hud;

import com.example.addon.DWAddons;
import com.example.addon.modules.movement.PacketTracker;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

public class PacketTrackerHud extends HudElement {

    public static final HudElementInfo<PacketTrackerHud> INFO = new HudElementInfo<>(
        DWAddons.HUD_GROUP, "packet-tracker",
        "Displays Moves/sec, Chunks/sec, and Server Position Overrides for movement debugging.",
        PacketTrackerHud::new);

    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Boolean> showWhenInactive = sg.add(new BoolSetting.Builder()
        .name("show-when-inactive")
        .description("Keep visible (zeroed) when Packet Tracker module is off.")
        .defaultValue(false)
        .build());

    private final Setting<SettingColor> normalColor = sg.add(new ColorSetting.Builder()
        .name("color")
        .description("Default text colour.")
        .defaultValue(new SettingColor(255, 255, 255))
        .build());

    private final Setting<SettingColor> warnColor = sg.add(new ColorSetting.Builder()
        .name("warn-color")
        .description("Colour when move packets exceed 20/sec (possible oversend).")
        .defaultValue(new SettingColor(255, 80, 80))
        .build());

    public PacketTrackerHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        if (!PacketTracker.isTracking() && !showWhenInactive.get()) {
            setSize(0, 0);
            return;
        }

        String text = PacketTracker.hudText();
        boolean oversending = PacketTracker.movePacketsPerSec > 20;

        setSize(renderer.textWidth(text, true), renderer.textHeight(true));
        renderer.text(text, x, y, oversending ? warnColor.get() : normalColor.get(), true);
    }
}
