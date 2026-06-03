package com.example.addon.hud;

import com.example.addon.DWAddons;
import com.example.addon.modules.movement.PacketTracker;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows Moves/sec, Chunks/sec and server position overrides from {@link PacketTracker}. Each metric
 * is individually toggleable, can be laid out inline or one-per-line, and the moves metric turns the
 * warn colour when it exceeds the module's oversend limit. Scale/shadow/alignment/padding/background
 * come from {@link DwHud}.
 */
public class PacketTrackerHud extends DwHud {

    public static final HudElementInfo<PacketTrackerHud> INFO = new HudElementInfo<>(
        DWAddons.HUD_GROUP, "packet-tracker",
        "Displays Moves/sec, Chunks/sec, and Server Position Overrides for movement debugging.",
        PacketTrackerHud::new);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgColor   = settings.createGroup("Color");

    // ── Content ──────────────────────────────────────────────────────────
    private final Setting<Boolean> showMoves = sgGeneral.add(new BoolSetting.Builder()
        .name("show-moves")
        .description("Show outgoing move packets per second.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> showChunks = sgGeneral.add(new BoolSetting.Builder()
        .name("show-chunks")
        .description("Show incoming chunks per second.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> showOverrides = sgGeneral.add(new BoolSetting.Builder()
        .name("show-overrides")
        .description("Show the cumulative server position-override (rubberband) count.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> labels = sgGeneral.add(new BoolSetting.Builder()
        .name("labels")
        .description("Show the metric labels (Moves/s, Chunks/s, Overrides).")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> multiline = sgGeneral.add(new BoolSetting.Builder()
        .name("multiline")
        .description("Put each metric on its own line instead of one inline row.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> showWhenInactive = sgGeneral.add(new BoolSetting.Builder()
        .name("show-when-inactive")
        .description("Keep visible (zeroed) when the Packet Tracker module is off.")
        .defaultValue(false)
        .build());

    // ── Color ────────────────────────────────────────────────────────────
    private final Setting<SettingColor> normalColor = sgColor.add(new ColorSetting.Builder()
        .name("color")
        .description("Default text colour.")
        .defaultValue(new SettingColor(255, 255, 255))
        .build());

    private final Setting<SettingColor> warnColor = sgColor.add(new ColorSetting.Builder()
        .name("warn-color")
        .description("Colour for the moves metric when it exceeds the module's oversend-limit.")
        .defaultValue(new SettingColor(255, 80, 80))
        .build());

    public PacketTrackerHud() {
        super(INFO);
        addAppearanceSettings();
    }

    @Override
    public void render(HudRenderer renderer) {
        if (!PacketTracker.isTracking() && !showWhenInactive.get()) {
            setSize(0, 0);
            return;
        }
        if (!showMoves.get() && !showChunks.get() && !showOverrides.get()) {
            setSize(0, 0);
            return;
        }

        boolean oversending = PacketTracker.movePacketsPerSec > PacketTracker.oversendThreshold;
        Color moves = oversending ? warnColor.get() : normalColor.get();
        Color normal = normalColor.get();

        String movesText     = (labels.get() ? "Moves/s: " : "") + PacketTracker.movePacketsPerSec;
        String chunksText    = (labels.get() ? "Chunks/s: " : "") + PacketTracker.chunksPerSec;
        String overridesText = (labels.get() ? "Overrides: " : "") + PacketTracker.positionOverrides;

        List<Line> lines = new ArrayList<>();
        if (multiline.get()) {
            if (showMoves.get())     lines.add(new Line(movesText, moves));
            if (showChunks.get())    lines.add(new Line(chunksText, normal));
            if (showOverrides.get()) lines.add(new Line(overridesText, normal));
        } else {
            StringBuilder sb = new StringBuilder();
            if (showMoves.get()) sb.append(movesText);
            if (showChunks.get()) { if (sb.length() > 0) sb.append("  |  "); sb.append(chunksText); }
            if (showOverrides.get()) { if (sb.length() > 0) sb.append("  |  "); sb.append(overridesText); }
            // Inline row uses the warn colour only when the moves metric is shown and over the limit.
            lines.add(new Line(sb.toString(), showMoves.get() && oversending ? warnColor.get() : normal));
        }

        renderLines(renderer, lines);
    }
}
