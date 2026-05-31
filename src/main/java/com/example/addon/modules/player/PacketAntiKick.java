package com.example.addon.modules.player;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;

/**
 * Cancels invalid inventory-click packets before they leave the client — the most common 2b2t
 * inventory kick. Automation modules occasionally QUICK_MOVE (shift-click) a slot a tick after
 * it emptied; a few of those and 2b2t disconnects you. This intercepts them on
 * {@link PacketEvent.Send}.
 *
 * <p>{@link ServerboundContainerClickPacket} is a record in 1.21.8 — accessors are
 * {@code clickType()}, {@code slotNum()}, {@code containerId()} (verified against the MC jar,
 * not the {@code getSlotNum()} form some references assume).
 */
public class PacketAntiKick extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> blockInvalidQuickMove = sgGeneral.add(new BoolSetting.Builder()
        .name("block-invalid-quickmove")
        .description("Cancel out-of-range QUICK_MOVE clicks and stale clicks for containers that are no longer open.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> blockNoScreenClicks = sgGeneral.add(new BoolSetting.Builder()
        .name("block-no-screen-clicks")
        .description("Cancel inventory clicks when no container screen is open.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> maxClicksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("max-clicks-per-tick")
        .description("Hard cap on click packets per tick.")
        .defaultValue(8)
        .min(1).max(20)
        .sliderRange(1, 20)
        .build());

    private final Setting<Boolean> log = sgGeneral.add(new BoolSetting.Builder()
        .name("log")
        .description("Print to chat what was cancelled (for debugging).")
        .defaultValue(false)
        .build());

    private int clicksThisTick = 0;

    public PacketAntiKick() {
        super(AddonTemplate.CATEGORY, "packet-anti-kick",
            "Cancels invalid inventory-click packets that cause 2b2t kicks. Keep on when using inventory automation.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        clicksThisTick = 0;
    }

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        if (mc.player == null) return;
        if (!(event.packet instanceof ServerboundContainerClickPacket p)) return;

        AbstractContainerMenu menu = mc.player.containerMenu;

        // 1) Stale click: the packet targets a container that is no longer the open one (e.g. a
        // queued automation click that lands a tick after the menu changed/closed). This — not a
        // shift-click on an empty slot — is the real source of the 2b2t inventory kick. We match
        // the packet's containerId against the currently open menu's id. (Both fields are public
        // on AbstractContainerMenu in 1.21.8 Mojang mappings — verified against the MC jar.)
        if (blockInvalidQuickMove.get() && (menu == null || p.containerId() != menu.containerId)) {
            cancel(event, "stale click for container " + p.containerId());
            return;
        }

        // 2) QUICK_MOVE on a genuinely out-of-range slot index. We deliberately do NOT test
        // isEmpty(): on Send the client has already applied the predicted shift-click locally, so a
        // legitimate shulker shift-click looks like a QUICK_MOVE on a now-empty slot — cancelling
        // that desyncs the client from the server. An empty-slot QUICK_MOVE is a harmless no-op.
        if (blockInvalidQuickMove.get() && p.clickType() == ClickType.QUICK_MOVE) {
            int slot = p.slotNum();
            if (slot < 0 || slot >= menu.slots.size()) {
                cancel(event, "QUICK_MOVE on invalid slot " + slot);
                return;
            }
        }

        // 3) click on an external container with no screen open. containerId 0 is the player's
        // own inventory, which is always valid to click silently (no screen needed) — that's how
        // LoadoutSave / AutoElytraRestock manage items, so never cancel those.
        if (blockNoScreenClicks.get() && p.containerId() != 0
                && !(mc.screen instanceof AbstractContainerScreen)) {
            cancel(event, "container click with no screen open");
            return;
        }

        // 4) per-tick hard cap
        clicksThisTick++;
        if (clicksThisTick > maxClicksPerTick.get()) {
            cancel(event, "exceeded max-clicks-per-tick (" + maxClicksPerTick.get() + ")");
        }
    }

    private void cancel(PacketEvent.Send event, String reason) {
        event.cancel();
        if (log.get()) info("Cancelled: %s", reason);
    }
}
