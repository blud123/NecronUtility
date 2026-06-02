package com.example.addon.modules.movement;

import com.example.addon.DWAddons;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.VehicleMoveC2SPacket;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * Blink: withholds outgoing movement packets so the server keeps you pinned at the position it last
 * received, while your client keeps moving freely. Releasing the queue makes the server "catch up"
 * (flush) or, if cleared, snaps you back to the pin (the next live packet triggers a server set-back).
 *
 * Design notes that make this actually usable rather than a toy:
 *  - Only movement packets are held (player + optionally vehicle). Keep-alives, interactions, chat,
 *    etc. all flow normally, so you don't get a timeout/keep-alive kick while blinked.
 *  - A re-entrancy guard means the flush itself isn't re-captured by the send hook.
 *  - A queue cap auto-flushes before the server's withholding/timeout limits bite. Flushing a very
 *    large backlog at once makes the catch-up exceed the vanilla "moved too quickly" cap and get set
 *    back, so the cap is a correctness feature, not just safety.
 *  - The frozen server-side position is rendered so you can see where the server still thinks you are.
 */
public class Blink extends Module {

    public enum Release {
        Flush,  // send the queued packets: server walks you forward to your real position
        Clear   // drop the queue: server keeps you at the pin, you rubberband back to it
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Boolean> cancelVehicle = sgGeneral.add(new BoolSetting.Builder()
        .name("cancel-vehicle")
        .description("Also hold vehicle movement packets, so blinking works while riding.")
        .defaultValue(true)
        .build());

    private final Setting<Release> onDisable = sgGeneral.add(new EnumSetting.Builder<Release>()
        .name("on-disable")
        .description("What to do with the queued packets when the module is turned off.")
        .defaultValue(Release.Flush)
        .build());

    private final Setting<Integer> maxQueued = sgGeneral.add(new IntSetting.Builder()
        .name("max-queued")
        .description("Auto-flush once this many packets are held (0 = unlimited). Guards against timeout kicks and oversized catch-up set-backs.")
        .defaultValue(60)
        .min(0).max(400)
        .sliderRange(0, 200)
        .build());

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Draw a box at the frozen server-side position.")
        .defaultValue(true)
        .build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the box is rendered.")
        .defaultValue(ShapeMode.Both)
        .visible(render::get)
        .build());

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("Fill colour of the box.")
        .defaultValue(new SettingColor(120, 160, 255, 50))
        .visible(render::get)
        .build());

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Outline colour of the box.")
        .defaultValue(new SettingColor(120, 160, 255, 220))
        .visible(render::get)
        .build());

    private final Queue<Packet<?>> queue = new ArrayDeque<>();
    private boolean releasing = false;   // re-entrancy guard so flush() isn't re-captured
    private Vec3d pin;                   // server-side position the player is frozen at

    public Blink() {
        super(DWAddons.CATEGORY, "blink",
            "Withholds movement packets so the server keeps you frozen while you move client-side.");
    }

    @Override
    public void onActivate() {
        queue.clear();
        pin = mc.player != null ? mc.player.getPos() : null;
    }

    @Override
    public void onDeactivate() {
        if (onDisable.get() == Release.Flush) flush();
        else queue.clear();
        pin = null;
    }

    @EventHandler
    private void onSendPacket(PacketEvent.Send event) {
        if (releasing) return;

        boolean isMove = event.packet instanceof PlayerMoveC2SPacket
            || (cancelVehicle.get() && event.packet instanceof VehicleMoveC2SPacket);
        if (!isMove) return;

        // First held packet establishes the pin from the position the server currently believes.
        if (pin == null && mc.player != null) pin = mc.player.getPos();

        queue.add(event.packet);
        event.cancel();

        if (maxQueued.get() > 0 && queue.size() >= maxQueued.get()) {
            // Flush before the backlog is large enough to trip withholding/timeout limits, then
            // re-pin so blinking continues seamlessly in bursts.
            flush();
            if (mc.player != null) pin = mc.player.getPos();
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || pin == null) return;

        double x = pin.x, y = pin.y, z = pin.z;
        event.renderer.box(
            x - 0.3, y, z - 0.3,
            x + 0.3, y + 1.8, z + 0.3,
            sideColor.get(), lineColor.get(), shapeMode.get(), 0
        );
    }

    /** Sends every queued packet in order, guarded so the send hook doesn't re-capture them. */
    private void flush() {
        if (mc.getNetworkHandler() == null) {
            queue.clear();
            return;
        }
        releasing = true;
        Packet<?> packet;
        while ((packet = queue.poll()) != null) {
            mc.getNetworkHandler().sendPacket(packet);
        }
        releasing = false;
    }

    @Override
    public String getInfoString() {
        return String.valueOf(queue.size());
    }
}
