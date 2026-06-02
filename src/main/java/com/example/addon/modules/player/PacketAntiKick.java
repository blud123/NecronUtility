package com.example.addon.modules.player;

import com.example.addon.DWAddons;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.util.math.BlockPos;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.Vec3d;

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

    // ── Extra outbound kick guards (default off to preserve current behavior) ──

    private final Setting<Boolean> validateClickSlot = sgGeneral.add(new BoolSetting.Builder()
        .name("validate-click-slot")
        .description("Cancel container clicks with an out-of-range slot index or negative button, for ALL click types (generalizes the shift-click check).")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> validateCarriedSlot = sgGeneral.add(new BoolSetting.Builder()
        .name("validate-carried-slot")
        .description("Cancel SetCarriedItem (hotbar-select) packets whose slot is outside 0-8.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> validateReach = sgGeneral.add(new BoolSetting.Builder()
        .name("validate-reach")
        .description("Cancel block-targeting packets (UseItemOn, destroy actions) aimed beyond reach — a common 'illegal action' kick.")
        .defaultValue(false)
        .build());

    private final Setting<Double> maxReach = sgGeneral.add(new DoubleSetting.Builder()
        .name("max-reach")
        .description("Reach distance (blocks) for the reach guard.")
        .defaultValue(6.0)
        .min(1.0).max(12.0)
        .sliderRange(3.0, 8.0)
        .visible(validateReach::get)
        .build());

    private final Setting<Boolean> log = sgGeneral.add(new BoolSetting.Builder()
        .name("log")
        .description("Print to chat what was cancelled (for debugging).")
        .defaultValue(false)
        .build());

    private int clicksThisTick = 0;

    public PacketAntiKick() {
        super(DWAddons.CATEGORY, "packet-anti-kick",
            "Cancels invalid inventory-click packets that cause 2b2t kicks. Keep on when using inventory automation.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        clicksThisTick = 0;
    }

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        if (mc.player == null) return;

        if (event.packet instanceof ClickSlotC2SPacket p) {
            handleClick(event, p);
        } else if (event.packet instanceof UpdateSelectedSlotC2SPacket p) {
            // Out-of-range hotbar select. The server rejects slots outside 0-8 with a kick.
            if (validateCarriedSlot.get() && (p.getSelectedSlot() < 0 || p.getSelectedSlot() > 8)) {
                cancel(event, "SetCarriedItem out-of-range slot " + p.getSelectedSlot());
            }
        } else if (validateReach.get()) {
            // Block-targeting packets aimed beyond reach trip "illegal action" kicks.
            if (event.packet instanceof PlayerInteractBlockC2SPacket p) {
                BlockPos pos = p.getBlockHitResult().getBlockPos();
                if (beyondReach(pos)) cancel(event, "UseItemOn beyond reach @ " + pos.toShortString());
            } else if (event.packet instanceof PlayerActionC2SPacket p && isDestroy(p)) {
                // Only destroy actions carry a real block pos; RELEASE_USE_ITEM/DROP use sentinels.
                if (beyondReach(p.getPos())) cancel(event, "destroy action beyond reach @ " + p.getPos().toShortString());
            }
        }
    }

    private void handleClick(PacketEvent.Send event, ClickSlotC2SPacket p) {
        ScreenHandler menu = mc.player.currentScreenHandler;

        // 1) Stale / wrong-container click: the packet targets a container that is no longer the open
        // one (e.g. a queued automation click that lands a tick after the menu changed/closed). This
        // — not a shift-click on an empty slot — is the real source of the 2b2t inventory kick. We
        // match the packet's containerId against the currently open menu's id. (Both fields are
        // public on AbstractContainerMenu in 1.21.8 Mojang mappings — verified against the MC jar.)
        if (blockInvalidQuickMove.get() && (menu == null || p.syncId() != menu.syncId)) {
            cancel(event, "stale click for container " + p.syncId());
            return;
        }

        // 2) Generalized slot/button validity for ALL click types. -999 is Minecraft's legitimate
        // "outside the window" sentinel (used to drop by clicking off-screen), so it's allowed.
        if (validateClickSlot.get()) {
            if (menu == null) { cancel(event, "click with no open menu"); return; }
            int slot = p.slot();
            boolean validSlot = slot == -999 || (slot >= -1 && slot < menu.slots.size());
            if (!validSlot || p.button() < 0) {
                cancel(event, "invalid click slot " + slot + " / button " + p.button());
                return;
            }
        }

        // 3) QUICK_MOVE on a genuinely out-of-range slot index. We deliberately do NOT test
        // isEmpty(): on Send the client has already applied the predicted shift-click locally, so a
        // legitimate shulker shift-click looks like a QUICK_MOVE on a now-empty slot — cancelling
        // that desyncs the client from the server. An empty-slot QUICK_MOVE is a harmless no-op.
        if (blockInvalidQuickMove.get() && p.actionType() == SlotActionType.QUICK_MOVE) {
            int slot = p.slot();
            if (slot < 0 || slot >= menu.slots.size()) {
                cancel(event, "QUICK_MOVE on invalid slot " + slot);
                return;
            }
        }

        // 4) click on an external container with no screen open. containerId 0 is the player's
        // own inventory, which is always valid to click silently (no screen needed) — that's how
        // LoadoutSave / AutoElytraRestock manage items, so never cancel those.
        if (blockNoScreenClicks.get() && p.syncId() != 0
                && !(mc.currentScreen instanceof HandledScreen)) {
            cancel(event, "container click with no screen open");
            return;
        }

        // 5) per-tick hard cap
        clicksThisTick++;
        if (clicksThisTick > maxClicksPerTick.get()) {
            cancel(event, "exceeded max-clicks-per-tick (" + maxClicksPerTick.get() + ")");
        }
    }

    private boolean isDestroy(PlayerActionC2SPacket p) {
        PlayerActionC2SPacket.Action a = p.getAction();
        return a == PlayerActionC2SPacket.Action.START_DESTROY_BLOCK
            || a == PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK
            || a == PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK;
    }

    private boolean beyondReach(BlockPos pos) {
        Vec3d eye = mc.player.getEyePos();
        double dx = (pos.getX() + 0.5) - eye.x;
        double dy = (pos.getY() + 0.5) - eye.y;
        double dz = (pos.getZ() + 0.5) - eye.z;
        double max = maxReach.get();
        return dx * dx + dy * dy + dz * dz > max * max;
    }

    private void cancel(PacketEvent.Send event, String reason) {
        event.cancel();
        if (log.get()) info("Cancelled: %s", reason);
    }
}
