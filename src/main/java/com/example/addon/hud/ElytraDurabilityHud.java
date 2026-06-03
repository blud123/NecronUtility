package com.example.addon.hud;

import com.example.addon.DWAddons;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Shows remaining elytra durability with a configurable display mode (number / percent / both), a
 * three-tier colour threshold, an optional item icon and an optional durability bar. Inherits
 * scale/shadow/alignment/padding/background from {@link DwHud}.
 */
public class ElytraDurabilityHud extends DwHud {

    public static final HudElementInfo<ElytraDurabilityHud> INFO = new HudElementInfo<>(
        DWAddons.HUD_GROUP, "elytra-durability",
        "Shows remaining elytra durability, coloured by threshold, with optional icon and bar.",
        ElytraDurabilityHud::new);

    public enum DisplayMode { Number, Percent, Both }

    private final SettingGroup sgGeneral    = settings.getDefaultGroup();
    private final SettingGroup sgThresholds = settings.createGroup("Thresholds");
    private final SettingGroup sgBar        = settings.createGroup("Bar");

    // ── General ──────────────────────────────────────────────────────────
    private final Setting<DisplayMode> displayMode = sgGeneral.add(new EnumSetting.Builder<DisplayMode>()
        .name("display-mode")
        .description("Number = remaining durability; Percent = % left; Both = number (percent).")
        .defaultValue(DisplayMode.Number)
        .build());

    private final Setting<String> prefix = sgGeneral.add(new StringSetting.Builder()
        .name("prefix")
        .description("Label before the value.")
        .defaultValue("Elytra: ")
        .build());

    private final Setting<Boolean> showIcon = sgGeneral.add(new BoolSetting.Builder()
        .name("show-icon")
        .description("Draw the elytra item icon to the left of the text.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> showWhenNotWorn = sgGeneral.add(new BoolSetting.Builder()
        .name("show-when-not-worn")
        .description("Show even when no elytra is equipped.")
        .defaultValue(false)
        .build());

    // ── Thresholds (three-tier colour) ───────────────────────────────────
    private final Setting<Integer> lowThreshold = sgThresholds.add(new IntSetting.Builder()
        .name("low-threshold")
        .description("Durability at or below which the text uses the low colour.")
        .defaultValue(30)
        .min(1).max(432)
        .sliderRange(1, 432)
        .build());

    private final Setting<Integer> midThreshold = sgThresholds.add(new IntSetting.Builder()
        .name("mid-threshold")
        .description("Durability at or below which the text uses the mid colour (set equal to low to disable the mid tier).")
        .defaultValue(120)
        .min(1).max(432)
        .sliderRange(1, 432)
        .build());

    private final Setting<SettingColor> okColor = sgThresholds.add(new ColorSetting.Builder()
        .name("ok-color")
        .description("Colour above the mid threshold.")
        .defaultValue(new SettingColor(0, 255, 0))
        .build());

    private final Setting<SettingColor> midColor = sgThresholds.add(new ColorSetting.Builder()
        .name("mid-color")
        .description("Colour between the low and mid thresholds.")
        .defaultValue(new SettingColor(255, 200, 0))
        .build());

    private final Setting<SettingColor> lowColor = sgThresholds.add(new ColorSetting.Builder()
        .name("low-color")
        .description("Colour at or below the low threshold (also the not-worn colour).")
        .defaultValue(new SettingColor(255, 0, 0))
        .build());

    // ── Bar ──────────────────────────────────────────────────────────────
    private final Setting<Boolean> showBar = sgBar.add(new BoolSetting.Builder()
        .name("show-bar")
        .description("Draw a durability bar under the text, filled by remaining fraction.")
        .defaultValue(false)
        .build());

    private final Setting<Integer> barWidth = sgBar.add(new IntSetting.Builder()
        .name("bar-width")
        .description("Bar width in pixels.")
        .defaultValue(60)
        .min(10).max(200)
        .sliderRange(20, 120)
        .visible(showBar::get)
        .build());

    private final Setting<Integer> barHeight = sgBar.add(new IntSetting.Builder()
        .name("bar-height")
        .description("Bar height in pixels.")
        .defaultValue(3)
        .min(1).max(12)
        .sliderRange(1, 8)
        .visible(showBar::get)
        .build());

    private final Setting<SettingColor> barTrackColor = sgBar.add(new ColorSetting.Builder()
        .name("bar-track-color")
        .description("Colour of the unfilled part of the bar.")
        .defaultValue(new SettingColor(40, 40, 40, 180))
        .visible(showBar::get)
        .build());

    public ElytraDurabilityHud() {
        super(INFO);
        addAppearanceSettings();
    }

    @Override
    public void render(HudRenderer renderer) {
        ItemStack chest = mc.player != null ? mc.player.getEquippedStack(EquipmentSlot.CHEST) : ItemStack.EMPTY;
        boolean worn = mc.player != null && chest.getItem() == Items.ELYTRA;

        if (!worn && !showWhenNotWorn.get()) {
            setSize(0, 0);
            return;
        }

        boolean sh = shadow.get();
        double sc = scale();
        int b = border.get();

        int remaining = 0, max = 1;
        String text;
        Color color;
        if (worn) {
            max = Math.max(1, chest.getMaxDamage());
            remaining = max - chest.getDamage();
            int pct = (int) Math.round(remaining * 100.0 / max);
            String value = switch (displayMode.get()) {
                case Number  -> String.valueOf(remaining);
                case Percent -> pct + "%";
                case Both    -> remaining + " (" + pct + "%)";
            };
            text = prefix.get() + value;
            color = tierColor(remaining);
        } else {
            text = prefix.get() + "—";
            color = lowColor.get();
        }

        // ── Layout ───────────────────────────────────────────────────────
        double iconSize = (showIcon.get() && worn) ? 16.0 * sc : 0;
        double iconGap  = iconSize > 0 ? 2 : 0;
        double textW = renderer.textWidth(text, sh, sc);
        double textH = renderer.textHeight(sh, sc);
        double rowW = iconSize + iconGap + textW;
        double rowH = Math.max(textH, iconSize);

        boolean bar = showBar.get() && worn;
        double barGap = bar ? 2 : 0;
        double bw = bar ? barWidth.get() : 0;
        double bh = bar ? barHeight.get() : 0;

        double contentW = Math.max(rowW, bw);
        double contentH = rowH + barGap + bh;

        setSize(contentW + b * 2, contentH + b * 2);

        if (background.get()) renderer.quad(x, y, getWidth(), getHeight(), backgroundColor.get());

        // Row (icon + text), aligned within the content box.
        double rowX = x + b + box.alignX(contentW, rowW, alignment.get());
        if (iconSize > 0) {
            renderer.item(chest, (int) Math.round(rowX), (int) Math.round(y + b), (float) sc, false);
        }
        double textX = rowX + iconSize + iconGap;
        double textY = y + b + (rowH - textH) / 2.0; // vertically centre text against the icon
        renderer.text(text, textX, textY, color, sh, sc);

        // Bar.
        if (bar) {
            double barX = x + b + box.alignX(contentW, bw, alignment.get());
            double barY = y + b + rowH + barGap;
            double frac = Math.max(0, Math.min(1.0, remaining / (double) max));
            renderer.quad(barX, barY, bw, bh, barTrackColor.get());
            if (frac > 0) renderer.quad(barX, barY, bw * frac, bh, color);
        }
    }

    private Color tierColor(int remaining) {
        if (remaining <= lowThreshold.get()) return lowColor.get();
        if (remaining <= midThreshold.get()) return midColor.get();
        return okColor.get();
    }
}
