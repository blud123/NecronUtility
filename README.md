# NecronUtility

A Meteor Client addon for 2b2t / anarchy servers — fast mining, elytra logistics, inventory automation, and movement utilities, all tuned to stay within anarchy-server packet limits.

> Modules appear under the **Example** category in Meteor (the template category was kept).

---

## Install

1. Install [Fabric Loader](https://fabricmc.net/) for Minecraft **1.21.8**.
2. Install [Meteor Client](https://meteorclient.com/) (1.21.8 build).
3. Drop `NecronUtility` into your `mods` folder.

---

## ⚠️ Avoiding Packet Kicks (read this first)

2b2t disconnects clients that send **invalid inventory-click packets**. The most common cause is a `QUICK_MOVE` (shift-click) on a slot a tick *after* it emptied — automation modules do this when inventory state shifts under them. A few of these and you're kicked.

**Enable `PacketAntiKick` whenever you run any inventory automation** (AutoElytraRestock, LoadoutSave). It cancels invalid clicks *before they leave the client*. The inventory modules will warn you in chat if you activate them without it.

The other kick source is **packet flooding** — sending many movement/action packets per tick. NecronUtility's movement modules use single-packet techniques, and a global `PacketLimiter` caps self-generated packets per tick across the whole addon. You still need to respect the safe defaults below.

### Safe defaults for 2b2t

| Module | Setting | Safe value |
|---|---|---|
| Nuker | Max Blocks | 1–2 |
| FastBreak | Max Blocks | 1–2 |
| AutoElytraRestock | Step Delay | ≥ 5 ticks |
| AutoElytraRestock | Clicks Per Step | 1 |
| LoadoutSave | Load Click Delay | ≥ 3 ticks |

---

## Modules

### Nuker
Breaks every block in a radius around you, packet-side. Tuned for 2b2t lag.
- *Radius* — break radius (the offset sphere is precomputed and only rebuilt when this or *Sort Mode* changes).
- *Max Blocks* — **simultaneous in-progress** breaks, 1–2. The server only tracks one active + one delayed destroy, so 3+ get rejected.
- *Sort Mode* — Closest / Lowest / Highest target ordering.
- *Auto Tool* — silently holds the best tool for the whole break.
- *Packet Mine* — instant START+STOP for blocks that break in a single tick.
- *Only Exposed* — skip fully-buried blocks.
- *Rotate*, *Filter*, *Render* — facing, skip-bedrock/fluids, and the expanding-cube overlay.
- Targets are **sticky**: an in-progress block keeps its slot until it finishes, leaves the radius, or turns to air. Delegates to FastBreak when that module is on.
- **Getting kicked?** Keep Max Blocks at 1–2 and run PacketAntiKick.

### FastBreak
Takes over block breaking with packet-level control and silent tool selection. Reacts to left-clicks and to Nuker.
- *Max Blocks* — 1 = primary only; 2 = allow the primary→delayed-slot demotion (two blocks at once).
- *Swap Tool* — syncs the best tool to the server for the **whole** break (not just near the finish), so breaks register even when you're not holding the tool.
- *Render* — a smooth, time-based expanding cube that grows from the block centre to the full box.
- **Getting kicked?** Keep Max Blocks at 1–2.

### VelocityBoost
Gives a burst of speed in your look direction — useful for escaping fights or crossing gaps.
- *Keybind* — trigger key.
- *Strength* — boost power.
- *Vertical* — extra lift.
- *Ticks* — spread duration. **Higher is safer against speed kicks.**
- *Require Elytra* — only while gliding.
- **Getting kicked?** Only if Strength is high with Ticks=1. Keep Ticks ≥ 3.

### VerticalVelocityTracker
Purely passive telemetry — observes and displays your vertical velocity and acceleration and **never alters movement**.
- *Smoothing* — exponential moving-average weight (0 = raw readings).
- *Show Acceleration* — include vertical acceleration in the readout.
- *Actionbar* — also print the readings to the actionbar.
- Pairs with the **Vertical Velocity** HUD element (`vY: +12.3 b/s  aY: -1.4 b/s²`).
- **Getting kicked?** No — read-only.

### PacketAntiKick
Prevents the most common 2b2t inventory kick: stale click packets for a container that is no longer open. It matches each click's container id against the open menu and cancels mismatches. Safe with shulkers — it no longer cancels QUICK_MOVE on a predicted-empty slot (that desynced shulker shift-clicks); it only blocks out-of-range slot indices.
- *Block Invalid QuickMove* — cancels stale clicks and out-of-range QUICK_MOVE clicks (the main protection).
- *Block No-Screen Clicks* — cancels clicks on external containers when no container is open (your own inventory is always allowed).
- *Max Clicks Per Tick* — hard ceiling.
- *Log* — debug output.
- **Keep this on whenever using any inventory automation.**

### AutoElytraRestock
Lands, restocks from your ender chest, and relaunches automatically when running low on elytra or rockets.
- *Trigger Elytra Durability / Trigger Rockets* — when to restock.
- *Platform Block* — what to stand on.
- *Step Delay* — ticks between each action. **Keep ≥ 5.**
- *Clicks Per Step* — **keep at 1 on 2b2t.**
- **Getting kicked?** This is the riskiest module. Max the delays and keep PacketAntiKick enabled. The place → open → pull sequence is best-effort; verify it in a safe spot first.

### ContainerPreview
Shows what's inside a shulker box when you hover over it in your inventory.
- *Preview Shulkers / Preview Other* — which container items to show.
- *Show Counts* — display stack sizes (rendered by the built-in grid component).
- *Scale* — popup size hint.
- **Getting kicked?** No — read-only.

### LoadoutSave
Saves and restores inventory layouts.
- Commands: `.loadout save <name>`, `.loadout load <name>`, `.loadout list`, `.loadout delete <name>`
- *Load Click Delay* — ticks between rearranging clicks. **Higher is safer.**
- *Match By* — Item Only (any sword fits) or Item And NBT (exact enchanted item; currently behaves as Item Only for restore since layouts persist item type).
- **Getting kicked?** Keep Load Click Delay ≥ 3 and run PacketAntiKick. Saving never sends packets.

### NecronConfig
Always-on config holder (no gameplay toggle needed).
- *Default Webhook URL* — global fallback Discord webhook used by any module whose own webhook field is blank.

---

## HUD: Elytra Durability
Shows your elytra's remaining durability, turning red when low. Add it from the HUD editor tab.
- *Show When Not Worn* — show even without elytra equipped.
- *Low Threshold* — when to go red.
- *OK / Low Color* — the two colours.
- *Prefix* — label text.

---

## Discord Webhooks

Several modules can ping a Discord channel on a find.

1. In Discord: **Server Settings → Integrations → Webhooks → New Webhook**.
2. Copy the webhook URL.
3. Paste it into a module's **Webhook URL** setting, or into **NecronConfig → Default Webhook URL** to use it as a global default for every module whose own field is blank.

A blank URL is a silent no-op. Requests are sent fully asynchronously and never block the game.

---

## Xaero Waypoints

Waypoint-adding settings require **Xaero's Minimap**. Waypoints are appended to Xaero's on-disk waypoint files and appear after a **map reload or rejoin**. The line format is version-sensitive: if waypoints don't show up, create one manually in-game and compare it against the file under `XaeroWaypoints/<server>/dim%N/waypoints.txt`.

---

## Building

```bash
./gradlew build
```

The built jar lands in `build/libs/`. Requires JDK 21.
