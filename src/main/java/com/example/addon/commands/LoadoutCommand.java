package com.example.addon.commands;

import com.example.addon.modules.player.LoadoutSave;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.command.CommandSource;

import java.util.List;

/** {@code .loadout save|load|list|delete <name>} — drives the {@link LoadoutSave} module. */
public class LoadoutCommand extends Command {

    public LoadoutCommand() {
        super("loadout", "Save and restore inventory layouts.");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(literal("save").then(argument("name", StringArgumentType.word()).executes(ctx -> {
            module().saveLoadout(StringArgumentType.getString(ctx, "name"));
            return SINGLE_SUCCESS;
        })));

        builder.then(literal("load").then(argument("name", StringArgumentType.word()).executes(ctx -> {
            module().startLoad(StringArgumentType.getString(ctx, "name"));
            return SINGLE_SUCCESS;
        })));

        builder.then(literal("list").executes(ctx -> {
            List<String> names = module().listLoadouts();
            if (names.isEmpty()) info("No saved loadouts.");
            else info("Loadouts: %s", String.join(", ", names));
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("delete").then(argument("name", StringArgumentType.word()).executes(ctx -> {
            String name = StringArgumentType.getString(ctx, "name");
            info(module().deleteLoadout(name) ? "Deleted loadout '" + name + "'." : "Loadout '" + name + "' not found.");
            return SINGLE_SUCCESS;
        })));
    }

    private LoadoutSave module() {
        return Modules.get().get(LoadoutSave.class);
    }
}
