# NecronUtility

A Meteor Client addon for 2b2t / anarchy servers — fast mining, elytra logistics, inventory automation, and movement utilities, all tuned to stay within anarchy-server packet limits.

> Modules appear under the **DW Addons** category in Meteor.

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
- *Reach Check* — skip blocks whose nearest point is past *Max Reach* (~6) from your eyes. Breaking out-of-reach blocks is a Grim flag; with the default radius nothing changes, it only trims the unreachable corners that appear at higher radii.
- *Rotate*, *Filter*, *Render* — facing, skip-bedrock/fluids, and the expanding-cube overlay.
- Targets are **sticky**: an in-progress block keeps its slot until it finishes, leaves the radius, or turns to air. Delegates to FastBreak when that module is on.
- **Getting kicked?** Keep Max Blocks at 1–2 and run PacketAntiKick.

### FastBreak
Takes over block breaking with packet-level control and silent tool selection. Reacts to left-clicks and to Nuker.
- *Max Blocks* — 1 = primary only; 2 = allow the primary→delayed-slot demotion (two blocks at once).
- *Swap Tool* — syncs the best tool to the server for the **whole** break (not just near the finish), so breaks register even when you're not holding the tool.
- *Swing* — sends a vanilla arm-swing with each break. Destroy packets with no swing look non-vanilla to Grim; leave on for 2b2t.
- *Render* — a smooth, time-based expanding cube that grows from the block centre to the full box.
- **2b2t reliability:** break actions now carry proper, increasing block-action **sequence ids** (Grim drops out-of-order ones, which used to make breaks silently fail), the synced tool is held stably instead of flipped every tick, and breaks swing like vanilla.
- **Getting kicked?** Keep Max Blocks at 1–2.

### ElytraFlyPlusPlus
Elytra fly with bounce, a **Baritone obstacle passer**, and a chestplate "fake-fly" trick that burns almost no elytra durability.
- *Bounce / Motion-Y Boost / Tunnel Bounce* — diagonal-highway wall-bounce; cancels Y momentum on ground contact to convert it into horizontal speed.
- *Obstacle Passer* — uses Baritone to path around walls, portal traps, and Y drops at a set *Y-Level*.
- *Chestplate FakeFly* — keeps a chestplate visually equipped while flying on a hotbar elytra.
- Requires **Baritone** installed for the obstacle passer.

### Disabler
Disrupts **Grim AntiCheat** state tracking on 2b2t. Several selectable strategies (transaction/keepalive delay, sprint/sneak toggle, ground spoof, state resets, book-payload stall, and the Shoreline GRIM_TRIDENT / GRIM_FIREWORK / GRIM_OVERFLOW item-use desyncs).
- **Exploit-specific and anticheat-version-dependent** — these mirror known techniques and may already be patched by the current Grim build. Not guaranteed to bypass anything; revalidate before trusting it.

### StashFinder
Logs chunks dense with storage block-entities — the core of 2b2t stash hunting. Scans every chunk as it streams in (no block sweep, no XaeroPlus dependency) and persists finds per-server, per-dimension.
- *Storage Blocks / Minimum Count* — which block-entities to count and how many in one chunk make a stash.
- *Shulker Instant-Hit* — a lone shulker in the wild counts immediately (rare and valuable).
- *Min Distance From Spawn* — skip the spawn storage clutter (default 1000).
- *Dimension* — only record finds in the chosen dimension.
- *Ignore Trial Chambers* — skip vault/copper-tuff loot blocks so generated structures don't spam.
- *Only Visited Chunks* — skip storage in freshly-generated chunks (reuses ChunkFinder's palette detection) to avoid false hits.
- *Alerts* — chat, Meteor toast, a ping sound, a Discord webhook (falls back to NecronConfig's default URL), and **Xaero waypoints written straight to disk** (no XaeroPlus needed).
- Finds are saved to `meteor-client/dw-addons/stash-finder/<server>/stashes.{json,csv}` and listed in the module GUI with **Info / Goto (Baritone) / Delete / Clear**.
- **Getting kicked?** No — read-only (webhooks are sent off-thread, never to the MC server).

### ChunkFinder
Native new/old-chunk detection for stash-hunting recon — **no XaeroPlus or any other mod required**. As you travel it classifies each chunk that streams in and alerts/renders the class you're hunting.
- Two independent detectors (a from-scratch port of XaeroPlus's algorithms):
  - **Fresh vs Visited** — by *palette compaction*. The server sends a freshly-generated chunk before it saves it; on save the palette is compacted. An uncompacted palette = generated this session (nobody's been here); a compacted one = a player has loaded it before. **Visited** chunks are the signal you want.
  - **Old-gen vs Modern** — by scanning for 1.18+ blocks (deepslate/copper/etc.). No modern blocks = pre-1.18 (e.g. 1.12) terrain.
- *Target* — which class is a "hit": **Visited** (default), Fresh, OldGen, or Modern.
- *Travel axis* — *Off-Axis Only* (default) reports a hit only when it's off your line of travel by *Off-Axis Distance* chunks — i.e. a **trail leading off a highway toward a base**. Direction is auto-derived from your movement (or a fixed yaw).
- *Min Distance From Spawn* / *Dimension* — skip the explored area around spawn; restrict to one dimension.
- *Alerts* — throttled chat / sound / toast, optional Discord webhook and on-disk Xaero waypoints.
- *Render* — colour-coded markers drawn on hit chunks (last 4096 kept).
- **Getting kicked?** No — read-only; it only inspects chunk data the server already sent you.

### QueueAlert
Reads your **2b2t queue position** from the tab-list header and fires a loud, AFK-friendly alert the moment you actually get through — so you can leave during a multi-hour queue and still hear it land.
- *Early Warn At* — also alert once when your position drops to this or below (0 = off), to give you time to get back.
- *Actionbar* — live `Queue #N  ETA m:ss` readout while queued (ETA is self-calibrating from how fast the queue is moving).
- *Alerts* — in-game sound (repeatable), an **OS beep that's audible while minimised/alt-tabbed**, a toast, chat, and an optional Discord webhook (ping your phone). Falls back to NecronConfig's default webhook URL.
- "Through the queue" is detected when position updates stop (with a fast path once you're confirmed in-world); disconnects/kicks reset state so they can't fake a successful join.
- **Getting kicked?** No — it only reads the tab list; it sends nothing to the server.

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

### Blink
Withholds your outgoing movement packets so the server keeps you frozen where it last saw you while you keep moving client-side. Releasing the queue either **flushes** (server walks you forward) or **clears** (you rubberband back to the pin).
- *Cancel Vehicle*, *On Disable* (Flush / Clear), *Max Queued* (auto-flushes before timeout / oversized-catchup set-backs), *Render* (box at the frozen server position).
- **Getting kicked?** Keep Max Queued at the default so the backlog never trips the timeout or "moved too quickly" caps.

### VerticalYBoost
Shulker Y-axis exploit: floods upward position packets and walks your server-side Y up, launching you skyward. Optionally gated to only run while standing on an **open shulker box**. Exploit-specific; expect a set-back when it stops.

### Diagnostics (read-only)
A set of passive observers for reverse-engineering server behaviour — none of them alter movement.
- **PacketTracker** — Moves/sec, Chunks/sec, and position-override counters; warns on oversend (configurable limit) and correlates set-backs with chunk-generation stalls. Pairs with the **Packet Tracker** HUD.
- **PacketLogger** — per-packet-type C2S logger (chat/file) plus a disconnect ring-buffer dump (see what you sent right before a kick) and the rubberband capture below.
- **RubberbandLogger** — snapshots bps + height + full state at each anti-cheat set-back into shareable CSV/text logs for offline correlation.
- **MovementProbe** — per-tick motion + packet telemetry to a CSV, classifying set-backs / applied-velocity / kicks to find the real per-move caps.
- **Ascend** — research rig with selectable vertical-ascent methods (linear / quadratic / exponential / packet-flood / client-desync / elytra-glide / collision). Pair with MovementProbe to see which server reaction each triggers.

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

### FpsBoost
Lowers graphics options for more FPS and restores your originals on disable. Every knob is configurable.
- *Render Distance* — defaults aggressive (2), **raise to ~5+ for elytra highway travel** so you can see obstacles ahead.
- *Simulation Distance*, *Max FPS*, *Entity Distance*, *Disable VSync*.
- *Visual* group — fast graphics, no clouds/particles/shadows/view-bob/AO, zeroed biome blend, mipmaps, and screen effects. Toggles left off leave that option untouched.

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

## HUD elements
Add these from the HUD editor tab (under the **DW Addons** group).

### Elytra Durability
Shows your elytra's remaining durability, turning red when low.
- *Show When Not Worn* — show even without elytra equipped.
- *Low Threshold* — when to go red.
- *OK / Low Color* — the two colours.
- *Prefix* — label text.

### Vertical Velocity
Renders the VerticalVelocityTracker's live `vY` / `aY` readings. *Show Acceleration*, *Show When Inactive*, *Color*.

### Packet Tracker
Renders PacketTracker's `Moves/s | Chunks/s | Overrides`, turning to the warn colour above the module's oversend-limit. *Show When Inactive*, *Color*, *Warn Color*.

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
