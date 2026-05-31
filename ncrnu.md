# NecronUtility — Spec v2: Mojmap/1.21.8 Correction + Existing-Module Audit

This supersedes the API-reference portion of v1. **Target: Minecraft 1.21.8, Mojang (Mojmap) mappings, Meteor (Mojmap fork).** The existing modules confirm the project uses Mojmap and the real main class is `com.example.addon.AddonTemplate` with `AddonTemplate.CATEGORY` (the template was not renamed). Use that, not `NecronUtility`.

Two parts:
- **Part A** — corrected API reference (everything in v1 §1 was Yarn-mapped and will NOT compile; use this instead).
- **Part B** — a per-module audit of the five existing modules: concrete bugs, packet-flood kick/ban risks, and exact fixes for Claude Code.

Version rule unchanged: this build is `0.1.0`; bump patch by `0.0.1` per change.

---

# PART A — Mojmap / 1.21.8 API reference (replaces v1 §1)

## A.1 Mapping translation table (Yarn → Mojmap)
Everything in v1 used the left column. Use the right column.

| Concept | v1 (Yarn — WRONG here) | Mojmap (1.21.8 — CORRECT) |
|---|---|---|
| Client class | `MinecraftClient` | `net.minecraft.client.Minecraft` |
| Static `mc` | `MeteorClient.mc` | `MeteorClient.mc` (same; type is now `Minecraft`) |
| World | `mc.world` | `mc.level` (type `net.minecraft.client.multiplayer.ClientLevel`) |
| Send packet | `mc.getNetworkHandler().sendPacket(p)` | `mc.player.connection.send(p)` |
| Chunk type | `WorldChunk` | `net.minecraft.world.level.chunk.LevelChunk` |
| Block entities | `chunk.getBlockEntities()` | `chunk.getBlockEntities()` (same; returns `Map<BlockPos, BlockEntity>`) |
| Block state | `chunk.getBlockState(pos)` / `world.getBlockState(pos)` | `mc.level.getBlockState(pos)` (same name) |
| Blocks registry | `net.minecraft.block.Blocks` | `net.minecraft.world.level.block.Blocks` |
| Block class | `net.minecraft.block.Block` | `net.minecraft.world.level.block.Block` |
| Air variants | `Blocks.AIR / CAVE_AIR / VOID_AIR` | `Blocks.AIR / CAVE_AIR / VOID_AIR` (same names) |
| Inventory | `player.getInventory()` | `player.getInventory()` (same) |
| Selected slot | `inv.selected` | **`inv.getSelectedSlot()` / `inv.setSelectedSlot(i)`** (the bare field `selected` was removed in 1.21.2+) |
| Hotbar item | `inv.getStack(i)` | `inv.getItem(i)` |
| Held-slot packet | `UpdateSelectedSlotC2SPacket` | `ServerboundSetCarriedItemPacket` |
| Inventory click packet | `ClickSlotC2SPacket` | `net.minecraft.network.protocol.game.ServerboundContainerClickPacket` |
| Click type enum | `SlotActionType.QUICK_MOVE` | `net.minecraft.world.inventory.ClickType.QUICK_MOVE` |
| Open menu | `player.currentScreenHandler` | `player.containerMenu` (type `AbstractContainerMenu`; `.slots`, `slot.getItem()`) |
| Open screen | `mc.currentScreen` | `mc.screen` |
| Container screen | `HandledScreen<?>` | `net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>` |
| Block action packet | `PlayerActionC2SPacket` | `ServerboundPlayerActionPacket` |
| Use-item-on-block | `PlayerInteractBlockC2SPacket` | `ServerboundUseItemOnPacket` |
| Move packet | `PlayerMoveC2SPacket.Full` | `ServerboundMovePlayerPacket.PosRot` (see A.5 for 1.21.8 signature) |
| Player command | `ClientCommandC2SPacket` | `ServerboundPlayerCommandPacket` |
| Swing | `HandSwingC2SPacket` | `ServerboundSwingPacket` |
| Dimension key | `World.OVERWORLD / NETHER / END` | `net.minecraft.world.level.Level.OVERWORLD / NETHER / END` |
| Current dimension | `world.getRegistryKey()` | `mc.level.dimension()` |
| Item registry | `net.minecraft.item.Items` | `net.minecraft.world.item.Items` |
| ItemStack | `net.minecraft.item.ItemStack` | `net.minecraft.world.item.ItemStack` |
| Stack damage | `stack.getMaxDamage()-stack.getDamage()` | `stack.getMaxDamage() - stack.getDamageValue()` |
| Equipment slot | `EquipmentSlot.CHEST` | `net.minecraft.world.entity.EquipmentSlot.CHEST` |
| Worn item | `player.getEquippedStack(slot)` | `player.getItemBySlot(slot)` |
| Server entry | `mc.getCurrentServerEntry()` | `mc.getCurrentServer()` (type `ServerData`; field `.ip`, NOT `.address`) |
| Vec3 | `net.minecraft.util.math.Vec3d` | `net.minecraft.world.phys.Vec3` |
| BlockPos | `net.minecraft.util.math.BlockPos` | `net.minecraft.core.BlockPos` (`BlockPos.containing(x,y,z)`) |
| Direction | `net.minecraft.util.math.Direction` | `net.minecraft.core.Direction` |

**Meteor classes are unchanged** — events (`meteordevelopment.meteorclient.events.*`), settings, `Module`, `Modules`, `Rotations`, `InvUtils`, `BlockUtils`, HUD, `Render3DEvent`, `ShapeMode`, `Color`/`SettingColor`. Only the underlying `net.minecraft.*` types differ. The existing modules already import the right Meteor packages — copy their import style.

## A.2 Module skeleton (Mojmap)
```java
package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class Example extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public Example() {
        super(AddonTemplate.CATEGORY, "example", "Description.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;
        // mc.player.connection.send(...);
    }
}
```

## A.3 Inventory click & PacketAntiKick (Mojmap-correct — supersedes v1 §3.8 code)
```java
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

@EventHandler
private void onSend(PacketEvent.Send event) {
    if (mc.player == null) return;
    if (!(event.packet instanceof ServerboundContainerClickPacket p)) return;

    // 1) invalid QUICK_MOVE on empty/out-of-range slot
    if (blockInvalidQuickMove.get() && p.getClickType() == ClickType.QUICK_MOVE) {
        var menu = mc.player.containerMenu;
        int slot = p.getSlotNum();
        if (menu == null || slot < 0 || slot >= menu.slots.size()
                || menu.getSlot(slot).getItem().isEmpty()) {
            event.cancel();
            return;
        }
    }
    // 2) click with no container screen open
    if (blockNoScreenClicks.get() && !(mc.screen instanceof AbstractContainerScreen)) {
        event.cancel();
        return;
    }
    // 3) per-tick cap handled with a counter reset in TickEvent.Pre
}
```
Verify the accessor names on `ServerboundContainerClickPacket` against the dependency jar — in 1.21.8 they are `getContainerId()`, `getSlotNum()`, `getButtonNum()`, `getClickType()`. If a name differs, fix from the jar, do not guess.

## A.4 Held-item / block-break packets (Mojmap)
```java
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.core.Direction;

mc.player.connection.send(new ServerboundSetCarriedItemPacket(slot));
mc.player.connection.send(new ServerboundPlayerActionPacket(
    ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, Direction.UP));
```
**Sequence-number caveat (1.21.8):** the 3-arg `ServerboundPlayerActionPacket` constructor exists but block-change acknowledgment uses a *sequence* (added 1.19). When you bypass `mc.gameMode` and send raw action packets, the server's sequence tracking can desync → **ghost blocks** (block reappears client-side). This is why the existing Nuker/FastBreak occasionally leave ghosts. If ghosts appear, route breaks through `mc.gameMode.destroyBlock(pos)` / `continueDestroyBlock(...)` which manage the sequence, or supply the correct sequence via the 4-arg constructor.

## A.5 Movement packet (1.21.8 signature — note the extra boolean)
1.21.2+ added a `horizontalCollision` boolean to move packets. The 1.21.8 record is:
```java
new ServerboundMovePlayerPacket.PosRot(
    double x, double y, double z,
    float yRot, float xRot,
    boolean onGround, boolean horizontalCollision)
```
The existing modules already pass 7 args — correct. Do not drop to the old 6-arg form.

## A.6 Elytra durability read (used by ElytraReplace, ElytraDurabilityHud, AutoElytraRestock)
```java
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;

ItemStack chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);
boolean wearingElytra = chest.getItem() == Items.ELYTRA;
int remaining = chest.getMaxDamage() - chest.getDamageValue(); // higher = healthier
```

## A.7 Xaero util — Mojmap server-entry fix (replaces the `getCurrentServerEntry` line in v1 §2.3)
```java
import net.minecraft.client.multiplayer.ServerData;
ServerData sd = mc.getCurrentServer();
String server = (sd != null) ? "Multiplayer_" + sd.ip.replace(':', '_') : "Singleplayer";
```
Dimension via `mc.level.dimension() == net.minecraft.world.level.Level.NETHER` etc.

Everything else in v1 (DiscordWebhook util — pure Java, unchanged; module list; README requirements; build order) still stands once the types above are substituted.

---

# PART B — Audit of the five existing modules

Severity scale: 🔴 will get you kicked/banned and/or doesn't work · 🟠 real bug · 🟡 minor.

## B.0 The core problem across the movement modules
2b2t runs **Grim AntiCheat**, which is *prediction-based*: it simulates your movement server-side each tick and compares. Three of these modules try to gain speed by **sending many position packets per tick** ("interpolation", "position flood", "lookahead"). That approach is from the 1.8 NCP era and **does not beat Grim** — Grim validates each `ServerboundMovePlayerPacket` against physics, so extra/ahead positions either get ignored or trip a setback (rubberband). The modules' own "rubberband recovery" settings are evidence they're constantly being setback. On top of that, the raw packet *rate* (100–200+ move packets/sec vs vanilla's 20) is independently kick-worthy on Paper-based servers.

**Net:** the flood approach is both ineffective and a ban risk. The fixes below convert them to the only thing that survives Grim — *modifying the single legitimate movement packet within tolerance* — and otherwise tell you to cut the floods.

---

## B.1 `PacketFastFly` — 🔴 highest risk, core approach broken
**Packet budget (INTERPOLATION, defaults):** per tick it sends `START_FALL_FLYING` (1) + friction PosRot (1) + lookahead (6) + anchor (2) = **~10 packets/tick ≈ 200/sec**, plus the vanilla move packet. That is ~10× a normal client.

**Concrete problems & fixes:**
1. 🔴 **START_FALL_FLYING every tick** (`openElytra`). Re-sending the toggle each tick when already gliding is abnormal and pointless. **Fix:** send it only on the rising edge — when `!mc.player.isFallFlying()` — and add a `≥10-tick` cooldown. Track with a boolean + tick counter.
2. 🔴 **Position flood (lookahead + anchor).** This is the part Grim setbacks. **Fix:** delete `doInterpolation`/`doBoostFly`/`doGroundPhase` flood loops. Replace with a single intercepted-packet nudge (see ElytraBouncePlus desync mode B.2.fix). If you keep a mode that sends extra packets, hard-cap total extra move packets to **1 per tick** and gate behind an explicit "I know this rubberbands" toggle defaulted off.
3. 🔴 **`y-stagger` / `ground-phase dip` "to confuse position validation".** Grim isn't confused by these; sub-block Y jitter just adds invalid deltas. **Fix:** remove both settings and their code.
4. 🟠 **`bounceAssist` jumps every tick on ground** (`invokeJump()` unconditionally when `onGround`). Jumping 20×/sec is a flag. **Fix:** only jump when grounded *and* a cooldown (e.g. ≥3 ticks) has elapsed, and only if vertical velocity ≈ 0.
5. 🟠 **`setDeltaMovement` + `setSprinting(true)` every tick** fights the client predictor. Keep `velocityKeepAlive` but apply it at most once/tick (already is) and only while actually fall-flying.
6. 🟡 **`detectHighwayAxis` uses `pos % 1000`** — 2b2t highways are on axis lines and at `±n*1000` ring roads, but the main highways are the **x=0 and z=0 axes and the diagonals**, not every 1000 blocks. The modulo will false-positive. **Fix:** detect "on highway" as `abs(pos.z) < tol` (X highway) or `abs(pos.x) < tol` (Z highway) only; drop the `%1000` clause unless you specifically want ring roads.

**Recommendation:** this module duplicates ElytraBouncePlus but worse. Either delete it, or gut it to a thin wrapper over the desync technique. Don't ship the flood version.

---

## B.2 `ElytraBouncePlus` — 🟠 better design, but flood path + toggle spam
This one is salvageable because it already contains the correct approach (`packetDesync` = modify the existing packet). Problems:

1. 🔴 **`START_FALL_FLYING` every tick** while `enabled()` — same as B.1.1. **Fix:** edge-trigger + cooldown; only when `!isFallFlying()`.
2. 🔴 **Flood path** (`sendGroundPhaseFlood` = `positionFlood` 4 + a snapback; `sendAirFlood` = 4; plus `packetBurst` 3 against a wall). Worst case ~8 extra move packets/tick. **Fix:** make `packetDesync` the **default and only** path. Delete `sendGroundPhaseFlood`, `sendAirFlood`, and the `packetBurst` loop, or gate them behind a default-off "legacy flood" toggle with a clear "rubberbands on Grim" warning in the description.
3. 🟠 **`onlyWhileColliding` default true but `horizontalCollision` is read once** — fine, but `motion-y-boost` zeroes Y every tick on ground at >20 b/s, which can cause stutter. **Fix:** add a small hysteresis (only zero Y if Y velocity magnitude > 0.1) so you're not no-oping.
4. 🟡 **`detectHighwayAxis`** has the same `%1000` false-positive as B.1.6 — apply the same fix and share one helper between the two modules (move it to a `utils/HighwayUtil.java`).
5. 🟡 **Sprint toggled on every tick** in two places — harmless but redundant; set once on activate and on landing.

**After fixes**, desync mode sends **zero extra packets** (it only edits the one move packet the client already sends), which is the only sane footprint for 2b2t.

---

## B.3 `Nuker` — 🟠 one real flood/never-break bug, otherwise solid
The comments show prior fixes (tick-count eviction, START-once). Remaining issues:

1. 🔴 **`packetMine` on non-instant blocks = infinite START+STOP spam.** The packet-mine path sends `START_DESTROY_BLOCK` + `STOP_DESTROY_BLOCK` in the same tick and assumes the block breaks instantly. For obsidian (or anything not one-shot with the held tool), it never breaks, so **every tick** it re-sends START+STOP on up to `blocksPerTick` blocks — a continuous flood that also never completes. On 2b2t obsidian is the main thing you'd nuke, so this fires constantly. **Fix:** in the packetMine branch, first compute `delta = FastBreak.breakDeltaForSlot(state, pos, bestSlot)`; only use the instant START+STOP path if `delta >= 1.0f` (one-tick break). Otherwise fall through to the multi-tick `startedBreaks` path. This single guard eliminates the flood and makes Nuker actually break hard blocks.
2. 🟠 **`Rotations.rotate(yaw, pitch, null)`** — passing `null` as the callback may not match the 1.21.8 Meteor signature (it may be `(double, double, int priority, Runnable)` or `(double,double,Runnable)`). **Fix:** confirm the signature in the Meteor jar; if it needs a `Runnable`, pass `() -> {}` not `null`, or use the overload without a callback. Also, rotating toward every block every tick when `blocksPerTick>1` sends a burst of look deltas — acceptable, but consider rotating only to the first target.
3. 🟠 **Sequence numbers** — raw START/STOP with the 3-arg constructor can ghost-block (see A.4). If you see blocks reappear, switch the multi-tick path to `mc.gameMode.continueDestroyBlock`/`destroyBlock` which manage sequence, or pass an incrementing sequence.
4. 🟡 **`blocksPerTick` max 4** — fine, default 2 is good for 2b2t. Keep the README note. No change.
5. 🟡 **`onlyExposed` uses `isAir()`** which also treats cave_air/void_air as exposed — correct for "is there a gap", so fine here (unlike PortalFinder which must distinguish them).

---

## B.4 `FastBreak` — 🟡 minor
Low packet footprint, `lastServerSlot` guard prevents slot-packet spam — good. Notes:

1. 🟡 **Near-finish tool sync then `restoreSlot()` same tick** can emit a SetCarriedItem pair (swap + restore) every tick during the final `serverSwapTicks` window. At most ~2 slot packets/tick for a couple ticks per block — acceptable, but you can avoid the restore on the finishing tick (you already re-apply before `sendFinish`).
2. 🟡 **Double-break via STOP "hand-off"** relies on the server's delayed-destroy slot behaving as in older versions. On 1.21.8 + Grim this may not double-break reliably. Not a kick risk; just verify it actually breaks two blocks before trusting it. If it doesn't, treat double-break as best-effort and document it.
3. 🟡 **Ghost blocks** — same sequence caveat (A.4); the class comment already acknowledges not removing blocks client-side, which is the right call. Keep that.

No urgent change. This is the cleanest of the five.

---

## B.5 `Disabler` — 🔴 ban-risk and largely ineffective; trim it
Be aware of what this module actually does and the realistic outcome on 2b2t:

1. 🔴 **`bookPayload`** — sends a 40-page book, 255 chars/page (~10 KB) explicitly to "stall Grim's processing thread." This is a server-stressing payload, not a movement bypass. Modern servers reject oversized/edit-book packets (Paper has book size limits and will kick "Book too large" / flag illegal book edits), and 2b2t specifically filters these. The realistic result is a **kick or ban**, not a disable. **Recommendation: remove this method entirely.** I'm not going to help tune it to hit the thread harder — making a server-DoS payload more effective isn't something I'll do, and it's the fastest way to lose the account.
2. 🔴 **`teleportDesync`** — sends fabricated `ServerboundAcceptTeleportationPacket` IDs the server never issued. The server validates teleport ACK IDs; bogus/extra ACKs are an immediate desync flag and a known ban trigger. **Recommendation: remove.**
3. 🟠 **`sprintToggle` / `slotDesync`** — bursts of START/STOP sprint and slot-cycle packets each pulse. Against Grim these don't open a usable speed window (Grim recomputes per packet); they just raise your packet rate. Mostly harmless, mostly useless. **Fix if kept:** lower `sprintToggleCount` to 1 and `pulseInterval` higher; honestly, leave it off.
4. 🟡 **`actionDesync` ABORT_DESTROY_BLOCK with sequence 0** — repeated zero-sequence actions can desync the block ack system (A.4). Minor.

**Overall:** the only parts that are merely-noisy rather than ban-bait are sprint/slot/swing, and they don't accomplish anything against Grim. The book and teleport-ACK methods are the ones that get accounts banned. My recommendation is to strip `Disabler` down to nothing useful remains — i.e. remove it, or keep only an off-by-default sprint-reset and document that it does not defeat Grim. I won't provide a "stronger" disabler.

---

## B.6 Cross-cutting fix: add a shared packet budget
Several modules independently flood. Add `utils/PacketLimiter.java` and route every *self-initiated* (non-vanilla) packet through it, so the addon can never exceed a safe global rate even if two modules run at once.

```java
package com.example.addon.utils;

import net.minecraft.network.protocol.Packet;
import static meteordevelopment.meteorclient.MeteorClient.mc;

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
}
```
Call `PacketLimiter.resetTick()` once per tick from the main addon (hook a single `TickEvent.Pre` in `AddonTemplate` or a tiny always-on system). Then in modules, replace `mc.player.connection.send(p)` with `PacketLimiter.send(p)` for *extra* packets you generate (not for the one vanilla move packet). This is a safety net, not a license to flood — keep the per-module limits too.

---

## B.7 Standardize the `selected`/`getSelectedSlot()` inconsistency
🟠 `Nuker` uses `mc.player.getInventory().selected` while `FastBreak` uses `getInventory().getSelectedSlot()`. In 1.21.8 Mojmap the field accessor is **`getSelectedSlot()` / `setSelectedSlot(int)`**; the bare `selected` field is gone. If the project compiles today, an access-widener or older snapshot is masking it. **Fix:** replace every `getInventory().selected` with `getInventory().getSelectedSlot()` and every `inv.selected = x` with `inv.setSelectedSlot(x)` for forward-safety.

---

# PART C — Priority order for Claude Code
1. **Compile-correctness first:** apply Part A mappings to any v1-derived code you generate, and apply B.7 (`getSelectedSlot()`) project-wide.
2. **Stop the floods (kick/ban):** B.3.1 (Nuker packetMine guard), B.2.2 (ElytraBouncePlus default to desync, delete floods), B.1 (gut/remove PacketFastFly), B.5 (remove Disabler book + teleport methods).
3. **Stop the toggle spam:** B.1.1 / B.2.1 edge-trigger START_FALL_FLYING.
4. **Add safety net:** B.6 PacketLimiter, wired into a single per-tick reset.
5. **Robustness:** A.4 sequence handling if ghost blocks appear (Nuker/FastBreak).
6. Then proceed with the v1 new-module list (StashFinder, NewChunkDetector, etc.) using Part A types throughout.

Bump the addon version `0.0.1` per change as you go.
