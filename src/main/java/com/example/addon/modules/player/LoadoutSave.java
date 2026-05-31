package com.example.addon.modules.player;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Saves and restores inventory layouts. Commands live in
 * {@link com.example.addon.commands.LoadoutCommand}: {@code .loadout save/load/list/delete <name>}.
 *
 * <p>Layouts are stored as item registry ids per inventory slot (0-40) in
 * {@code necron/loadouts/<name>.json} (SNBT). Loading rearranges the current inventory one click
 * per {@code load-click-delay} ticks, re-validating each tick (self-correcting greedy sort).
 *
 * <p>Note: persistence is item-type based, so {@code match-by = ItemAndNbt} currently behaves
 * like {@code ItemOnly} for restore (per-item NBT is not persisted).
 */
public class LoadoutSave extends Module {

    public enum MatchBy { ItemOnly, ItemAndNbt }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> loadClickDelay = sgGeneral.add(new IntSetting.Builder()
        .name("load-click-delay")
        .description("Ticks between clicks while restoring. Higher = safer.")
        .defaultValue(3).min(1).max(10).sliderRange(1, 10).build());

    private final Setting<MatchBy> matchBy = sgGeneral.add(new EnumSetting.Builder<MatchBy>()
        .name("match-by")
        .description("Match by item type only, or exact NBT (NBT currently behaves as ItemOnly for restore).")
        .defaultValue(MatchBy.ItemOnly).build());

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Notify on save/load.")
        .defaultValue(true).build());

    // Active load state: slot index -> desired Item.
    private final Map<Integer, Item> target = new LinkedHashMap<>();
    private final Set<Integer> skipped = new HashSet<>();
    private boolean loading = false;
    private int cooldown = 0;

    public LoadoutSave() {
        super(AddonTemplate.CATEGORY, "loadout-save",
            "Save/restore inventory layouts via .loadout commands. Run with PacketAntiKick.");
    }

    @Override
    public void onActivate() {
        loading = false;
        target.clear();
        skipped.clear();
    }

    // ── Command-facing API ──────────────────────────────────────────────────

    public void saveLoadout(String name) {
        if (mc.player == null) return;
        CompoundTag tag = new CompoundTag();
        for (int i = 0; i <= 40; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            tag.putString(String.valueOf(i), BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        }
        try {
            Path file = loadoutDir().resolve(sanitize(name) + ".json");
            Files.createDirectories(file.getParent());
            Files.writeString(file, tag.toString());
            if (chatFeedback.get()) info("Saved loadout '%s' (%d slots).", name, tag.keySet().size());
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
            CompoundTag tag = TagParser.parseCompoundFully(Files.readString(file));
            target.clear();
            skipped.clear();
            for (String key : tag.keySet()) {
                int slot = Integer.parseInt(key);
                ResourceLocation id = ResourceLocation.tryParse(tag.getStringOr(key, ""));
                if (id == null) continue;
                Item item = BuiltInRegistries.ITEM.getValue(id);
                if (item != Items.AIR) target.put(slot, item);
            }
            loading = !target.isEmpty();
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

    // ── Restore state machine ───────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || !loading) return;
        if (cooldown > 0) { cooldown--; return; }

        // Find the first target slot that isn't already satisfied (and wasn't skipped).
        for (Map.Entry<Integer, Item> e : target.entrySet()) {
            int slot = e.getKey();
            Item desired = e.getValue();
            if (skipped.contains(slot)) continue;
            if (mc.player.getInventory().getItem(slot).getItem() == desired) continue;

            // Find a source slot holding the desired item (not an already-satisfied target).
            int source = findSource(desired, slot);
            if (source == -1) { skipped.add(slot); continue; } // can't satisfy — leave it

            InvUtils.move().from(source).to(slot);
            cooldown = loadClickDelay.get();
            return;
        }

        // Nothing left to fix.
        loading = false;
        if (chatFeedback.get()) info("Loadout restore complete.");
    }

    private int findSource(Item desired, int excludeTargetSlot) {
        for (int i = 0; i <= 40; i++) {
            if (i == excludeTargetSlot) continue;
            if (mc.player.getInventory().getItem(i).getItem() != desired) continue;
            // Don't pull from a slot that's already a satisfied target.
            Item want = target.get(i);
            if (want != null && want == desired) continue;
            return i;
        }
        return -1;
    }

    private Path loadoutDir() {
        return FabricLoader.getInstance().getGameDir().resolve("necron").resolve("loadouts");
    }

    private String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }
}
