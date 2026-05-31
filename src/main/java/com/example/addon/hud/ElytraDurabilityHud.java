package com.example.addon.hud;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/** Shows current elytra durability on screen, coloured by a low threshold. Read-only. */
public class ElytraDurabilityHud extends HudElement {

    public static final HudElementInfo<ElytraDurabilityHud> INFO = new HudElementInfo<>(
        AddonTemplate.HUD_GROUP, "elytra-durability",
        "Shows remaining elytra durability, coloured by threshold.", ElytraDurabilityHud::new);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> showWhenNotWorn = sgGeneral.add(new BoolSetting.Builder()
        .name("show-when-not-worn")
        .description("Show even when no elytra is equipped.")
        .defaultValue(false)
        .build());

    private final Setting<Integer> lowThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("low-threshold")
        .description("Durability at or below which the text turns to the low colour.")
        .defaultValue(30)
        .min(1).max(432)
        .sliderRange(1, 432)
        .build());

    private final Setting<SettingColor> okColor = sgGeneral.add(new ColorSetting.Builder()
        .name("ok-color")
        .description("Colour above the threshold.")
        .defaultValue(new SettingColor(0, 255, 0))
        .build());

    private final Setting<SettingColor> lowColor = sgGeneral.add(new ColorSetting.Builder()
        .name("low-color")
        .description("Colour at or below the threshold.")
        .defaultValue(new SettingColor(255, 0, 0))
        .build());

    private final Setting<String> prefix = sgGeneral.add(new StringSetting.Builder()
        .name("prefix")
        .description("Label before the number.")
        .defaultValue("Elytra: ")
        .build());

    public ElytraDurabilityHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        String text;
        SettingColor color;

        ItemStack chest = mc.player != null
            ? mc.player.getItemBySlot(EquipmentSlot.CHEST) : ItemStack.EMPTY;

        if (mc.player != null && chest.getItem() == Items.ELYTRA) {
            int remaining = chest.getMaxDamage() - chest.getDamageValue();
            text = prefix.get() + remaining;
            color = remaining <= lowThreshold.get() ? lowColor.get() : okColor.get();
        } else {
            if (!showWhenNotWorn.get()) {
                setSize(0, 0);
                return;
            }
            text = prefix.get() + "—";
            color = lowColor.get();
        }

        setSize(renderer.textWidth(text, true), renderer.textHeight(true));
        renderer.text(text, x, y, color, true);
    }
}
