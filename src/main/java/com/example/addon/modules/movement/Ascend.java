package com.example.addon.modules.movement;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Ascend: one module, selectable vertical-ascent method. This is a RESEARCH RIG, not a working
 * "fly to 2 billion" cheat. Each method demonstrates a different movement regime so you can pair it
 * with MovementProbe and observe which server reaction it triggers (set-back vs trusted velocity).
 *
 * Findings baked in from analysis (see prompt background):
 *  - Vanilla rejects per-move deltas over ~10 b/t (normal) / ~17 b/t (gliding) -> set-back.
 *  - Per-tick physics: v = v*drag + accel; pos += v. drag < 1, so launches decay exponentially.
 *  - Server REJECTS client position claims but TRUSTS velocity it applies itself.
 *  - Only COLLISION is server-trusted; CLIENT_DESYNC is local-only (F3 readout, server pins you).
 *
 * Yarn mappings, Minecraft 1.21.8.
 */
public class Ascend extends Module {

    public enum Method {
        LINEAR,        // constant velocity -> linear altitude (lowest flag if small)
        QUADRATIC,     // additive boost, no drag -> quadratic altitude (demonstrates regime)
        EXPONENTIAL,   // multiplicative velocity -> exponential, crosses cap in ~3s
        PACKET_FLOOD,  // assert higher Y via raw packets -> rejected client claim, insta set-back
        CLIENT_DESYNC, // "Y boost": crank client Y, suppress moves -> F3 shows billions, local only
        ELYTRA_GLIDE,  // eased, clamped, glide-shaped ascent -> stays inside tolerance, slow
        COLLISION      // server-applied (shulker lid / burrow elevator) -> trusted but height-bounded
    }

    public enum CollisionSource { SHULKER, BURROW }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgMethod  = settings.createGroup("Method Params");

    private final Setting<Method> method = sgGeneral.add(new EnumSetting.Builder<Method>()
        .name("method")
        .description("Which ascent method to run. Pair with MovementProbe to see its server reaction.")
        .defaultValue(Method.ELYTRA_GLIDE)
        .build());

    private final Setting<Keybind> resetKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("resync-key")
        .description("CLIENT_DESYNC only: snap client position back to the last server-acked spot.")
        .defaultValue(Keybind.none())
        .action(this::resync)
        .build());

    private final Setting<Boolean> requireElytra = sgGeneral.add(new BoolSetting.Builder()
        .name("require-elytra")
        .description("Gate velocity methods to only run while gliding.")
        .defaultValue(false)
        .visible(() -> {
            Method m = method.get();
            return m == Method.LINEAR || m == Method.EXPONENTIAL || m == Method.QUADRATIC;
        })
        .build());

    // --- shared / per-method params (shown via visible) ---
    private final Setting<Double> speed = sgMethod.add(new DoubleSetting.Builder()
        .name("speed")
        .description("Target upward speed in blocks/tick (LINEAR, ELYTRA_GLIDE).")
        .defaultValue(0.6).min(0.0).max(8.0).sliderRange(0.0, 8.0)
        .visible(() -> method.get() == Method.LINEAR || method.get() == Method.ELYTRA_GLIDE)
        .build());

    private final Setting<Double> boost = sgMethod.add(new DoubleSetting.Builder()
        .name("boost")
        .description("Velocity added per tick (QUADRATIC).")
        .defaultValue(0.08).min(0.0).max(5.0).sliderRange(0.0, 5.0)
        .visible(() -> method.get() == Method.QUADRATIC)
        .build());

    private final Setting<Double> growth = sgMethod.add(new DoubleSetting.Builder()
        .name("growth")
        .description("Per-tick velocity multiplier > 1 (EXPONENTIAL).")
        .defaultValue(1.05).min(1.0).max(2.0).sliderRange(1.0, 2.0)
        .visible(() -> method.get() == Method.EXPONENTIAL)
        .build());

    private final Setting<Double> cap = sgMethod.add(new DoubleSetting.Builder()
        .name("cap")
        .description("Hard clamp on vertical velocity (EXPONENTIAL, ELYTRA_GLIDE).")
        .defaultValue(3.0).min(0.1).max(50.0).sliderRange(0.1, 50.0)
        .visible(() -> method.get() == Method.EXPONENTIAL || method.get() == Method.ELYTRA_GLIDE)
        .build());

    private final Setting<Double> accel = sgMethod.add(new DoubleSetting.Builder()
        .name("accel")
        .description("Lerp factor toward target velocity (ELYTRA_GLIDE).")
        .defaultValue(0.15).min(0.01).max(1.0).sliderRange(0.01, 1.0)
        .visible(() -> method.get() == Method.ELYTRA_GLIDE)
        .build());

    private final Setting<Double> forwardSpeed = sgMethod.add(new DoubleSetting.Builder()
        .name("forward-speed")
        .description("Horizontal glide speed so the vector reads as a glide (LINEAR, ELYTRA_GLIDE).")
        .defaultValue(0.8).min(0.0).max(2.0).sliderRange(0.0, 2.0)
        .visible(() -> method.get() == Method.LINEAR || method.get() == Method.ELYTRA_GLIDE)
        .build());

    private final Setting<Double> bps = sgMethod.add(new DoubleSetting.Builder()
        .name("blocks-per-second")
        .description("Upward speed asserted via raw packets (PACKET_FLOOD).")
        .defaultValue(1000.0).min(1.0).sliderMax(1000000.0)
        .visible(() -> method.get() == Method.PACKET_FLOOD)
        .build());

    private final Setting<Integer> packetsPerTick = sgMethod.add(new IntSetting.Builder()
        .name("packets-per-tick")
        .description("Position packets asserted each tick (PACKET_FLOOD).")
        .defaultValue(10).min(1).max(20).sliderRange(1, 20)
        .visible(() -> method.get() == Method.PACKET_FLOOD)
        .build());

    private final Setting<Double> desyncSpeed = sgMethod.add(new DoubleSetting.Builder()
        .name("desync-speed")
        .description("Client-side blocks/tick added to local Y (CLIENT_DESYNC). Local only.")
        .defaultValue(10000.0).min(1.0).sliderMax(10000000.0)
        .visible(() -> method.get() == Method.CLIENT_DESYNC)
        .build());

    private final Setting<CollisionSource> collisionSource = sgMethod.add(new EnumSetting.Builder<CollisionSource>()
        .name("collision-source")
        .description("Which server-applied source to ride (COLLISION).")
        .defaultValue(CollisionSource.SHULKER)
        .visible(() -> method.get() == Method.COLLISION)
        .build());

    // --- state ---
    private double expSpeed;       // EXPONENTIAL persisted velocity
    private double packetServerY;  // PACKET_FLOOD walked-up Y
    private Vec3d lastServerPos;   // for CLIENT_DESYNC resync
    private boolean suppressing;   // CLIENT_DESYNC: cancel outgoing move packets
    private double lastBurrowY;    // COLLISION/BURROW: detect server teleport steps

    public Ascend() {
        super(AddonTemplate.CATEGORY, "ascend",
            "Selectable vertical-ascent method. Research rig - pair with MovementProbe.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null) return;
        expSpeed = 0.05;
        packetServerY = mc.player.getY();
        lastServerPos = mc.player.getPos();
        lastBurrowY = mc.player.getY();
        suppressing = method.get() == Method.CLIENT_DESYNC;
    }

    @Override
    public void onDeactivate() {
        suppressing = false;
        if (mc.player != null) {
            mc.player.setNoGravity(false);
            resync();
        }
    }

    /** Snap the client back to where the server last acknowledged us. */
    private void resync() {
        if (mc.player == null || lastServerPos == null) return;
        mc.player.setPosition(lastServerPos.x, lastServerPos.y, lastServerPos.z);
        mc.player.setVelocity(0, 0, 0);
    }

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        // CLIENT_DESYNC: drop real movement so the server keeps us pinned while client Y runs away.
        if (suppressing && event.packet instanceof PlayerMoveC2SPacket) event.cancel();
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        // Track the last server-acked position for clean resync (match by simple name, version-proof).
        String n = event.packet.getClass().getSimpleName();
        if ((n.contains("PlayerPosition") || n.contains("Teleport")) && mc.player != null) {
            // The teleport is applied around now; capture next tick in onTick to be safe.
            lastServerPos = mc.player.getPos();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        // keep suppression state in sync with the selected method
        suppressing = method.get() == Method.CLIENT_DESYNC;

        switch (method.get()) {
            case LINEAR        -> tickLinear();
            case QUADRATIC     -> tickQuadratic();
            case EXPONENTIAL   -> tickExponential();
            case PACKET_FLOOD  -> tickPacketFlood();
            case CLIENT_DESYNC -> tickClientDesync();
            case ELYTRA_GLIDE  -> tickElytraGlide();
            case COLLISION     -> tickCollision();
        }
    }

    private boolean gateElytra() {
        return !requireElytra.get() || mc.player.isGliding();
    }

    // 1. constant velocity -> linear altitude
    private void tickLinear() {
        if (!gateElytra()) return;
        Vec3d v = mc.player.getVelocity();
        double nx = v.x, nz = v.z;
        Vec3d look = mc.player.getRotationVector();
        double h = Math.sqrt(look.x * look.x + look.z * look.z);
        if (forwardSpeed.get() > 0 && h > 1e-5) {
            nx = look.x / h * forwardSpeed.get();
            nz = look.z / h * forwardSpeed.get();
        }
        mc.player.setVelocity(nx, speed.get(), nz);
    }

    // 2. additive boost, no drag -> quadratic altitude
    private void tickQuadratic() {
        if (!gateElytra()) return;
        mc.player.setNoGravity(true);
        Vec3d v = mc.player.getVelocity();
        mc.player.setVelocity(v.x, v.y + boost.get(), v.z);
    }

    // 3. multiplicative velocity -> exponential altitude
    private void tickExponential() {
        if (!gateElytra()) return;
        expSpeed *= growth.get();
        expSpeed = Math.min(expSpeed, cap.get());
        Vec3d v = mc.player.getVelocity();
        mc.player.setVelocity(v.x, expSpeed, v.z);
    }

    // 4. raw position-packet flood -> rejected client claim, insta set-back
    private void tickPacketFlood() {
        double yPerTick = bps.get() / 20.0;
        int n = packetsPerTick.get();
        double yPerPacket = yPerTick / n;
        for (int i = 1; i <= n; i++) {
            mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.Full(
                mc.player.getX(),
                packetServerY + yPerPacket * i,
                mc.player.getZ(),
                mc.player.getYaw(),
                mc.player.getPitch(),
                false,
                false));
        }
        packetServerY += yPerTick;
        mc.player.setPosition(mc.player.getX(), packetServerY, mc.player.getZ());
    }

    // 5. "Y boost": client Y runs away, server pinned. F3 climbs; local only.
    private void tickClientDesync() {
        double y = mc.player.getY() + desyncSpeed.get();
        mc.player.setPosition(mc.player.getX(), y, mc.player.getZ());
        mc.player.setVelocity(mc.player.getVelocity().x, 0, mc.player.getVelocity().z);
    }

    // 6. eased + clamped glide-shaped ascent -> inside tolerance, slow
    private void tickElytraGlide() {
        if (mc.player.isGliding()) {
            Vec3d v = mc.player.getVelocity();
            double target = Math.min(speed.get(), cap.get());
            double ny = MathHelper.clamp(MathHelper.lerp(accel.get(), v.y, target), -cap.get(), cap.get());
            double nx = v.x, nz = v.z;
            Vec3d look = mc.player.getRotationVector();
            double h = Math.sqrt(look.x * look.x + look.z * look.z);
            if (forwardSpeed.get() > 0 && h > 1e-5) {
                nx = look.x / h * forwardSpeed.get();
                nz = look.z / h * forwardSpeed.get();
            }
            mc.player.setVelocity(nx, ny, nz);
        }
    }

    // 7. server-applied (trusted) sources. Module detects/logs; it does NOT fabricate velocity.
    private void tickCollision() {
        if (collisionSource.get() == CollisionSource.SHULKER) {
            if (isOnOpenShulker()) {
                // Server applies the lid-push velocity itself; nothing to send. Ceiling = one push,
                // then drag decays it. We only report it via the info string.
                lastBurrowY = mc.player.getY();
            }
        } else { // BURROW elevator
            double y = mc.player.getY();
            if (y - lastBurrowY > 0.4) {
                info(String.format("burrow teleport step: %.2f -> %.2f", lastBurrowY, y));
            }
            lastBurrowY = y;
        }
    }

    private boolean isOnOpenShulker() {
        if (mc.player == null || mc.world == null) return false;
        BlockPos below = BlockPos.ofFloored(mc.player.getX(), mc.player.getY() - 0.1, mc.player.getZ());
        if (!(mc.world.getBlockState(below).getBlock() instanceof ShulkerBoxBlock)) return false;
        if (mc.world.getBlockEntity(below) instanceof ShulkerBoxBlockEntity shulker) {
            return shulker.getAnimationProgress(1f) > 0f;
        }
        return false;
    }

    @Override
    public String getInfoString() {
        return method.get().name().toLowerCase();
    }
}
