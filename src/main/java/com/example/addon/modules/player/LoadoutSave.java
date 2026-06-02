package com.example.addon.modules.player;

import com.example.addon.DWAddons;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.registry.RegistryOps;
import net.minecraft.item.ItemStack;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Saves and restores inventory layouts. Commands live in
 * {@link com.example.addon.commands.LoadoutCommand}: {@code .loadout save/load/list/delete <name>}.
 *
 * <p>Layouts store the FULL {@link ItemStack} (item + components) per inventory slot (0-40) in
 * {@code necron/loadouts/<name>.json} (SNBT), so restore can distinguish variants (Efficiency-V vs
 * plain pick, damaged vs pristine, etc.).
 *
 * <p><b>Armor routing.</b> Minecraft 1.21.x indexes the armor inventory slots 36-39 as
 * FEET, LEGS, CHEST, HEAD, but Meteor's {@code SlotUtils} maps those same indices to the armor
 * <i>screen</i> slots in the reverse order (HEAD, CHEST, LEGS, FEET). Saving reads
 * {@code getStack(index)} (Minecraft order), so restoring with a raw {@code move().to(index)} would
 * send every armor piece to the wrong slot (boots → helmet slot, etc.), where the server rejects it.
 * {@link #issueMove} therefore routes armor through {@code toArmor(index-36)} (and offhand through
 * {@code toOffhand()}), which resolve to the correct screen slots.
 *
 * <p><b>Restore is re-verify-with-settle.</b> Every eligible tick it re-scans <i>all</i> targets and
 * issues at most one move for the first slot that doesn't yet match. Because client inventory clicks
 * are applied optimistically (predicted locally, then confirmed or reverted by the server a few ticks
 * later), it does not declare success the instant a slot looks right: once every target matches it
 * keeps re-verifying for {@code verify-settle-ticks} more ticks, so a late server resync that reverts
 * a slot is detected and re-issued. A global timeout and per-slot attempt cap bound the work. It
 * pauses (without cancelling) while a foreign container screen is open and guards against a non-empty
 * cursor.
 */
public class LoadoutSave extends Module {

    /** ItemOnly = item type only; ItemAndNbt = full component match (default). */
    public enum MatchBy { ItemAndNbt, ItemOnly }

    private static final int ARMOR_START = 36;
    private static final int ARMOR_END   = 39;
    private static final int OFFHAND     = 40;
    private static final int MAX_ATTEMPTS = 5; // issued moves per slot before giving up on it

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> loadClickDelay = sgGeneral.add(new IntSetting.Builder()
        .name("load-click-delay")
        .description("Minimum ticks between clicks while restoring (a spacing floor).")
        .defaultValue(3).min(1).max(10).sliderRange(1, 10).build());

    private final Setting<Integer> settleTicks = sgGeneral.add(new IntSetting.Builder()
        .name("verify-settle-ticks")
        .description("After every target matches, keep re-verifying for this many ticks to catch server-side reverts (high-latency servers like 2b2t). Higher = safer but slower to report done.")
        .defaultValue(10).min(0).max(60).sliderRange(0, 40).build());

    private final Setting<MatchBy> matchBy = sgGeneral.add(new EnumSetting.Builder<MatchBy>()
        .name("match-by")
        .description("ItemAndNbt = match item AND components (default; distinguishes enchants/damage). ItemOnly = item type only, ignoring enchantments/damage/components.")
        .defaultValue(MatchBy.ItemAndNbt).build());

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Notify on save/load.")
        .defaultValue(true).build());

    // Active load state: slot index -> desired stack.
    private final Map<Integer, ItemStack> target = new LinkedHashMap<>();
    private final Set<Integer> skipped = new HashSet<>(); // targets we gave up on
    private final Set<Integer> placed  = new HashSet<>(); // targets currently satisfied
    private final Map<Integer, Integer> attempts = new HashMap<>();
    private boolean loading = false;
    private int cooldown = 0;
    private int settleCountdown = 0;
    private int globalTimeout = 0;

    public LoadoutSave() {
        super(DWAddons.CATEGORY, "loadout-save",
            "Save/restore inventory layouts via .loadout commands. Run with PacketAntiKick.");
    }

    @Override
    public void onActivate() {
        resetLoad();
    }

    private void resetLoad() {
        loading = false;
        cooldown = 0;
        settleCountdown = 0;
        globalTimeout = 0;
        target.clear();
        skipped.clear();
        placed.clear();
        attempts.clear();
    }

    // ── Command-facing API ──────────────────────────────────────────────────

    public void saveLoadout(String name) {
        if (mc.player == null) return;
        RegistryOps<NbtElement> ops = regOps();
        NbtCompound tag = new NbtCompound();
        int count = 0;
        for (int i = 0; i <= OFFHAND; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            Optional<NbtElement> encoded = ItemStack.CODEC.encodeStart(ops, stack).result();
            if (encoded.isPresent()) { tag.put(String.valueOf(i), encoded.get()); count++; }
        }
        try {
            Path file = loadoutDir().resolve(sanitize(name) + ".json");
            Files.createDirectories(file.getParent());
            Files.writeString(file, tag.toString());
            if (chatFeedback.get()) info("Saved loadout '%s' (%d slots).", name, count);
        } catch (IOException e) {
            error("Failed to save loadout: %s", e.getMessage());
        }
    }

    public void startLoad(String name) {
        if (mc.player == null) return;
        if (!Modules.get().isActive(PacketAntiKick.class)) {
            warning("PacketAntiKick is not active — enable it to avoid inventory kicks.");
        }
        Path file = loadoutDir().resolve(sanitize(name) + ".json");
        if (!Files.exists(file)) { error("Loadout '%s' not found.", name); return; }
        try {
            RegistryOps<NbtElement> ops = regOps();
            NbtCompound tag = StringNbtReader.readCompound(Files.readString(file));
            resetLoad();
            for (String key : tag.getKeys()) {
                int slot;
                try { slot = Integer.parseInt(key); } catch (NumberFormatException e) { continue; }
                NbtElement value = tag.get(key);
                if (value == null) continue;
                ItemStack stack = ItemStack.CODEC.parse(ops, value).result().orElse(ItemStack.EMPTY);
                if (!stack.isEmpty()) target.put(slot, stack);
            }
            loading = !target.isEmpty();
            settleCountdown = settleTicks.get();
            // Generous upper bound so a stuck restore can't loop forever (each move costs up to
            // loadClickDelay+1 ticks; allow MAX_ATTEMPTS per slot plus the settle window).
            globalTimeout = Math.max(200,
                (target.size() * MAX_ATTEMPTS + settleTicks.get() + 20) * (loadClickDelay.get() + 1));
            if (chatFeedback.get()) info("Loading loadout '%s' (%d slots)...", name, target.size());
        } catch (Exception e) {
            error("Failed to read loadout: %s", e.getMessage());
        }
    }

    public List<String> listLoadouts() {
        List<String> names = new ArrayList<>();
        Path dir = loadoutDir();
        if (!Files.isDirectory(dir)) return names;
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
                String f = p.getFileName().toString();
                names.add(f.substring(0, f.length() - 5));
            });
        } catch (IOException ignored) {}
        Collections.sort(names);
        return names;
    }

    public boolean deleteLoadout(String name) {
        try {
            return Files.deleteIfExists(loadoutDir().resolve(sanitize(name) + ".json"));
        } catch (IOException e) {
            return false;
        }
    }

    // ── Restore state machine (re-verify with settle) ───────────────────────

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || !loading) return;

        // Guard C — only operate on the player's own inventory handler. If a chest/other screen is
        // open, "slot 9" resolves to a container slot, not inventory. Pause (don't cancel) until it
        // closes.
        if (mc.currentScreen != null || mc.player.currentScreenHandler != mc.player.playerScreenHandler) return;

        // Guard B — never move with a non-empty cursor (a leftover pickup would corrupt placement).
        if (!mc.player.currentScreenHandler.getCursorStack().isEmpty()) {
            stashCursor();
            return;
        }

        if (cooldown > 0) { cooldown--; return; }
        if (--globalTimeout <= 0) { finish(); return; }

        // Re-scan ALL targets every eligible tick so a server resync (a slot reverted back after the
        // optimistic client click) is detected and re-issued — never assumed-done from one good read.
        int next = -1;
        for (Map.Entry<Integer, ItemStack> e : target.entrySet()) {
            int slot = e.getKey();
            if (skipped.contains(slot)) continue;
            if (matches(slotItem(slot), e.getValue())) { placed.add(slot); continue; }
            placed.remove(slot); // was satisfied, reverted now → needs work again
            if (attempts.getOrDefault(slot, 0) >= MAX_ATTEMPTS) {
                skipped.add(slot);
                if (chatFeedback.get()) warning("Could not restore slot %d after %d attempts — skipping.", slot, MAX_ATTEMPTS);
                continue;
            }
            if (next == -1) next = slot;
        }

        // Everything is placed or permanently skipped. Hold through the settle window before declaring
        // done, so a late server-side revert still gets caught and re-issued.
        if (next == -1) {
            if (--settleCountdown <= 0) { finish(); return; }
            cooldown = 1; // light re-check cadence while settling
            return;
        }
        settleCountdown = settleTicks.get(); // still making changes — reset the stability timer

        ItemStack desired = target.get(next);
        int source = findSource(desired, next);
        if (source == -1) {
            // Item isn't available right now (don't have it / still arriving). Count the attempt so a
            // genuinely-missing item can't spin forever.
            attempts.merge(next, 1, Integer::sum);
            return;
        }

        issueMove(source, next);
        attempts.merge(next, 1, Integer::sum);
        cooldown = loadClickDelay.get();
    }

    private void finish() {
        loading = false;
        if (!chatFeedback.get()) return;
        int done = placed.size();
        int total = target.size();
        if (done >= total) info("Loadout restore complete (%d slots).", done);
        else info("Loadout restore finished: %d/%d placed, %d skipped.", done, total, skipped.size());
    }

    /**
     * Issues a single pickup→place move, routing armor (36-39) and offhand (40) through the dedicated
     * InvUtils helpers. See the class Javadoc: raw {@code from/to(index)} mis-maps armor because
     * SlotUtils orders those indices opposite to {@code PlayerInventory.getStack}.
     */
    private void issueMove(int source, int dest) {
        InvUtils.Action a = InvUtils.move();
        if (isArmor(source)) a.fromArmor(source - ARMOR_START);
        else if (source == OFFHAND) a.fromOffhand();
        else a.from(source);

        if (isArmor(dest)) a.toArmor(dest - ARMOR_START);
        else if (dest == OFFHAND) a.toOffhand();
        else a.to(dest);
    }

    /** Stashes a non-empty cursor into the first empty main/hotbar slot (avoids armor/offhand). */
    private void stashCursor() {
        for (int i = 0; i < ARMOR_START; i++) {
            if (slotItem(i).isEmpty()) {
                InvUtils.click().slot(i);
                return;
            }
        }
        // No empty slot — wait; do NOT drop items.
    }

    private boolean isArmor(int index) {
        return index >= ARMOR_START && index <= ARMOR_END;
    }

    private boolean matches(ItemStack inSlot, ItemStack desired) {
        if (matchBy.get() == MatchBy.ItemOnly) return inSlot.getItem() == desired.getItem();
        return ItemStack.areItemsAndComponentsEqual(inSlot, desired);
    }

    private ItemStack slotItem(int index) {
        return mc.player.getInventory().getStack(index);
    }

    /**
     * Finds a source slot holding {@code desired}, excluding: the destination, slots already placed
     * this run, skipped slots, and slots that are themselves an unsatisfied target for the same
     * stack (so we don't rob one target to fill another).
     */
    private int findSource(ItemStack desired, int excludeTargetSlot) {
        for (int i = 0; i <= OFFHAND; i++) {
            if (i == excludeTargetSlot) continue;
            if (placed.contains(i) || skipped.contains(i)) continue;
            ItemStack stack = slotItem(i);
            if (stack.isEmpty() || !matches(stack, desired)) continue;
            // Don't pull from a slot that itself wants this same stack.
            ItemStack wantHere = target.get(i);
            if (wantHere != null && matches(stack, wantHere)) continue;
            return i;
        }
        return -1;
    }

    private RegistryOps<NbtElement> regOps() {
        return RegistryOps.of(NbtOps.INSTANCE, mc.player.getRegistryManager());
    }

    private Path loadoutDir() {
        return FabricLoader.getInstance().getGameDir().resolve("necron").resolve("loadouts");
    }

    private String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }
}
