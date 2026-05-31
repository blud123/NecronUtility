package com.example.addon.modules.render;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.render.TooltipDataEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.tooltip.ContainerTooltipComponent;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows the contents of a shulker box / container item when hovered in an inventory, by reading
 * its {@link DataComponents#CONTAINER} component and supplying Meteor's
 * {@link ContainerTooltipComponent} (an item grid) via {@link TooltipDataEvent}. Read-only — no
 * packets.
 *
 * <p>Note: the grid render (including stack counts) is handled by Meteor's built-in tooltip
 * component, so {@code show-counts} / {@code scale} are advisory and do not re-scale that grid.
 */
public class ContainerPreview extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> previewShulkers = sgGeneral.add(new BoolSetting.Builder()
        .name("preview-shulkers")
        .description("Preview shulker box contents on hover.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> previewOther = sgGeneral.add(new BoolSetting.Builder()
        .name("preview-other")
        .description("Preview other container items (chests held as items, etc).")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> showCounts = sgGeneral.add(new BoolSetting.Builder()
        .name("show-counts")
        .description("Show stack counts (rendered by the built-in grid component).")
        .defaultValue(true)
        .build());

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Popup size hint (advisory; grid is drawn by the built-in component).")
        .defaultValue(1.0)
        .min(0.5).max(2.0)
        .sliderRange(0.5, 2.0)
        .build());

    public ContainerPreview() {
        super(AddonTemplate.CATEGORY, "container-preview",
            "Previews shulker/container contents on hover in inventories. Read-only.");
    }

    @EventHandler
    private void onTooltipData(TooltipDataEvent event) {
        ItemStack stack = event.itemStack;
        if (stack == null || stack.isEmpty()) return;

        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        if (contents == null) return;

        boolean isShulker = stack.getItem() instanceof BlockItem bi
            && bi.getBlock() instanceof ShulkerBoxBlock;
        if (isShulker) {
            if (!previewShulkers.get()) return;
        } else {
            if (!previewOther.get()) return;
        }

        List<ItemStack> items = new ArrayList<>();
        contents.stream().forEach(items::add);
        if (items.isEmpty()) return;
        if (items.size() > 27) items = items.subList(0, 27);

        event.tooltipData = new ContainerTooltipComponent(
            items.toArray(new ItemStack[0]),
            new Color(255, 255, 255));
    }
}
