package com.example.addon.mixin;

import com.example.addon.modules.FastBreak;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.entity.player.PlayerInventory;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Silent swap core.
 *
 * <p>Redirects every read of {@code Inventory.selected} (the held hotbar slot) while a
 * silent override is active. On 1.21.8 (Mojang mappings) the only place that actually reads
 * the {@code selected} field is {@link Inventory#getSelectedSlot()}; everything else
 * ({@code getSelectedItem()}, {@code Player.getDestroySpeed(...)},
 * {@code BlockState.getDestroyProgress(...)}, the hotbar/HUD, etc.) funnels through that
 * getter — so redirecting this single field read makes all of them observe the override.
 *
 * <p>The real {@code player.getInventory().selected} is never mutated, so the client stays
 * visually on the same slot. {@link FastBreak} sets the override, lets the break calculation
 * (and any packets it sends to the server) see the chosen tool, then clears it again.
 *
 * <p>{@code method = "*"} targets every method in {@code Inventory}; {@code require = 0} keeps
 * the mixin from failing to apply on the methods that don't read the field.
 */
@Mixin(PlayerInventory.class)
public class SilentSwapMixin {

    @ModifyExpressionValue(
        method = "*",
        require = 0,
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/entity/player/PlayerInventory;selectedSlot:I",
            opcode = Opcodes.GETFIELD
        )
    )
    private int meteorAddon$redirectSelectedSlot(int original) {
        if (mc.player == null) return original;
        // Only override the local player's inventory, never other inventories.
        if (((PlayerInventory) (Object) this).player != mc.player) return original;
        int silent = FastBreak.getSilentSlot();
        return silent == -1 ? original : silent;
    }
}
