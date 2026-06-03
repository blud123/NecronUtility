package com.example.addon.modules.player;

import com.example.addon.DWAddons;
import com.example.addon.mixin.LivingEntityInvoker;
import com.example.addon.modules.NecronConfig;
import com.example.addon.utils.DiscordWebhook;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Vec3d;

/**
 * Lands, builds a platform, opens an ender chest, pulls a fresh elytra + rockets, re-equips, and
 * relaunches when elytra durability or rocket count gets low. A step state machine advances one
 * transition per {@code step-delay} ticks, with conservative defaults.
 *
 * <p><b>Best-effort / highest risk:</b> this is the easiest module to get kicked with. It uses
 * Meteor's {@link BlockUtils}/{@link InvUtils} helpers and verified packet types, but the place →
 * open → pull sequence depends on server timing; verify it in a safe context before trusting it.
 * Keep PacketAntiKick active (it is warned about on activate).
 */
public class AutoElytraRestock extends Module {

    public enum PlatformBlock { Obsidian, Netherrack, Cobblestone }

    private enum State { IDLE, DESCEND, PLACE_PLATFORM, OPEN_CHEST, PULL_ITEMS, EQUIP, RELAUNCH }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> triggerElytraDurability = sgGeneral.add(new IntSetting.Builder()
        .name("trigger-elytra-durability")
        .description("Trigger when elytra durability drops to this.")
        .defaultValue(30).min(1).max(432).sliderRange(1, 432).build());

    private final Setting<Integer> triggerRockets = sgGeneral.add(new IntSetting.Builder()
        .name("trigger-rockets")
        .description("Trigger when firework count drops to this.")
        .defaultValue(4).min(0).max(64).sliderRange(0, 64).build());

    private final Setting<PlatformBlock> platformBlock = sgGeneral.add(new EnumSetting.Builder<PlatformBlock>()
        .name("platform-block")
        .description("Block to stand on.")
        .defaultValue(PlatformBlock.Obsidian).build());

    private final Setting<Integer> stepDelay = sgGeneral.add(new IntSetting.Builder()
        .name("step-delay")
        .description("Ticks between each automation step. Keep >= 5.")
        .defaultValue(5).min(2).max(20).sliderRange(2, 20).build());

    private final Setting<Integer> clicksPerStep = sgGeneral.add(new IntSetting.Builder()
        .name("clicks-per-step")
        .description("Inventory clicks per step. Keep at 1 on 2b2t.")
        .defaultValue(1).min(1).max(4).sliderRange(1, 4).build());

    private final Setting<String> webhookUrl = sgGeneral.add(new StringSetting.Builder()
        .name("webhook-url")
        .description("Status notifications. Falls back to NecronConfig.")
        .defaultValue("").build());

    // Ticks to wait after a completed cycle before re-triggering. Stops a land→pull-nothing→relaunch
    // loop when the ender chest has no spare elytra/rockets to restock from.
    private static final int RETRIGGER_COOLDOWN = 200;

    private State state = State.IDLE;
    private int stepTimer = 0;
    private int retriggerCooldown = 0;
    private BlockPos chestPos = null;

    public AutoElytraRestock() {
        super(DWAddons.CATEGORY, "auto-elytra-restock",
            "Lands, restocks elytra + rockets from an ender chest, and relaunches. Best-effort; run with PacketAntiKick.");
    }

    @Override
    public void onActivate() {
        state = State.IDLE;
        stepTimer = 0;
        retriggerCooldown = 0;
        chestPos = null;
        if (!Modules.get().isActive(PacketAntiKick.class)) {
            warning("PacketAntiKick is not active — strongly recommended for this module.");
        }
    }

    @Override
    public void onDeactivate() {
        state = State.IDLE;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (state == State.IDLE) {
            if (retriggerCooldown > 0) { retriggerCooldown--; return; }
            if (shouldTrigger()) {
                state = State.DESCEND;
                stepTimer = 0;
                DiscordWebhook.sendMessage(NecronConfig.resolveWebhook(webhookUrl.get()),
                    "AutoElytraRestock triggered — landing to restock.");
            }
            return;
        }

        // Gate transitions by step-delay.
        if (stepTimer > 0) { stepTimer--; return; }
        stepTimer = stepDelay.get();

        switch (state) {
            case DESCEND -> {
                // Wait until grounded (stop gliding naturally on contact).
                if (mc.player.isOnGround()) state = State.PLACE_PLATFORM;
            }
            case PLACE_PLATFORM -> {
                FindItemResult block = InvUtils.findInHotbar(platformItem());
                BlockPos below = mc.player.getBlockPos().down();
                if (block.found() && mc.world.getBlockState(below).isAir()) {
                    BlockUtils.place(below, block, true, 50);
                }
                state = State.OPEN_CHEST;
            }
            case OPEN_CHEST -> {
                FindItemResult ec = InvUtils.findInHotbar(Items.ENDER_CHEST);
                Direction facing = mc.player.getHorizontalFacing();
                BlockPos target = mc.player.getBlockPos().offset(facing);
                if (ec.found() && mc.world.getBlockState(target).isAir()) {
                    BlockUtils.place(target, ec, true, 50);
                    chestPos = target;
                }
                if (chestPos != null) {
                    Vec3d hit = Vec3d.ofCenter(chestPos);
                    BlockUtils.interact(new BlockHitResult(hit, Direction.UP, chestPos, false),
                        Hand.MAIN_HAND, true);
                }
                state = State.PULL_ITEMS;
            }
            case PULL_ITEMS -> {
                if (!(mc.currentScreen instanceof HandledScreen)) {
                    // Screen didn't open (or already closed) — bail to equip with whatever we have.
                    state = State.EQUIP;
                    return;
                }
                int pulled = 0;
                pulled += shiftPullFromContainer(s -> s.getItem() == Items.ELYTRA, clicksPerStep.get());
                if (pulled < clicksPerStep.get()) {
                    pulled += shiftPullFromContainer(s -> s.getItem() == Items.FIREWORK_ROCKET,
                        clicksPerStep.get() - pulled);
                }
                if (pulled == 0) {
                    // Nothing left to pull — close and equip.
                    closeScreen();
                    state = State.EQUIP;
                }
            }
            case EQUIP -> {
                FindItemResult spare = InvUtils.find(s -> s.getItem() == Items.ELYTRA, 0, 35);
                ItemStack worn = mc.player.getEquippedStack(EquipmentSlot.CHEST);
                if (spare.found() && worn.getItem() != Items.ELYTRA) {
                    InvUtils.move().from(spare.slot()).toArmor(2);
                } else if (spare.found() && elytraRemaining(worn) <= triggerElytraDurability.get()) {
                    InvUtils.move().from(spare.slot()).toArmor(2);
                }
                state = State.RELAUNCH;
            }
            case RELAUNCH -> {
                closeScreen();
                ((LivingEntityInvoker) mc.player).invokeJump();
                mc.player.networkHandler.sendPacket(new ClientCommandC2SPacket(
                    mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
                DiscordWebhook.sendMessage(NecronConfig.resolveWebhook(webhookUrl.get()),
                    "AutoElytraRestock complete — relaunched.");
                retriggerCooldown = RETRIGGER_COOLDOWN;
                state = State.IDLE;
            }
            default -> state = State.IDLE;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private boolean shouldTrigger() {
        ItemStack chest = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        boolean elytraLow = chest.getItem() == Items.ELYTRA
            && elytraRemaining(chest) <= triggerElytraDurability.get();
        FindItemResult rockets = InvUtils.find(Items.FIREWORK_ROCKET);
        boolean rocketsLow = rockets.count() <= triggerRockets.get();
        return elytraLow || rocketsLow;
    }

    private int elytraRemaining(ItemStack stack) {
        return stack.getMaxDamage() - stack.getDamage();
    }

    private Item platformItem() {
        return switch (platformBlock.get()) {
            case Obsidian -> Items.OBSIDIAN;
            case Netherrack -> Items.NETHERRACK;
            case Cobblestone -> Items.COBBLESTONE;
        };
    }

    /** Shift-clicks up to {@code max} matching stacks out of the upper (non-player) container. */
    private int shiftPullFromContainer(java.util.function.Predicate<ItemStack> match, int max) {
        if (max <= 0) return 0;
        ScreenHandler menu = mc.player.currentScreenHandler;
        int done = 0;
        for (int i = 0; i < menu.slots.size() && done < max; i++) {
            Slot slot = menu.slots.get(i);
            if (slot.inventory == mc.player.getInventory()) continue; // skip player inventory
            if (!match.test(slot.getStack())) continue;
            InvUtils.shiftClick().slotId(i);
            done++;
        }
        return done;
    }

    private void closeScreen() {
        if (mc.player.currentScreenHandler != null && mc.player.currentScreenHandler.syncId != 0) {
            mc.player.networkHandler.sendPacket(new CloseHandledScreenC2SPacket(mc.player.currentScreenHandler.syncId));
        }
        mc.setScreen(null);
    }
}
