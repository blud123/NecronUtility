package com.example.addon.utils;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.network.protocol.Packet;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Global per-tick budget for self-initiated (non-vanilla) packets, shared across every
 * Necron module. It is a safety net — modules still keep their own per-module limits — so
 * that even if two modules run at once the addon can never exceed a sane packet rate.
 *
 * <p>Wired in {@link com.example.addon.AddonTemplate#onInitialize()} by subscribing this
 * class to Meteor's event bus; {@link #onTick} resets the counter once per tick at
 * {@link EventPriority#HIGHEST} so the reset always runs before any module consumes budget.
 */
public final class PacketLimiter {
    private static int sentThisTick = 0;
    private static int maxPerTick   = 8; // global cap across all Necron modules

    private PacketLimiter() {}

    public static void resetTick() { sentThisTick = 0; }
    public static void setMaxPerTick(int n) { maxPerTick = Math.max(1, n); }

    /** Returns true if the packet was sent, false if the per-tick budget is exhausted. */
    public static boolean send(Packet<?> packet) {
        if (mc.player == null || mc.player.connection == null) return false;
        if (sentThisTick >= maxPerTick) return false;
        mc.player.connection.send(packet);
        sentThisTick++;
        return true;
    }

    // Reset the budget at the very start of each tick, before module tick handlers run.
    @EventHandler(priority = EventPriority.HIGHEST)
    private static void onTick(TickEvent.Pre event) {
        resetTick();
    }
}
