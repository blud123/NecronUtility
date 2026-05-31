package com.example.addon;

import com.example.addon.commands.CommandExample;
import com.example.addon.commands.LoadoutCommand;
import com.example.addon.hud.HudExample;
import com.example.addon.hud.ElytraDurabilityHud;
import com.example.addon.hud.VerticalVelocityHud;
import com.example.addon.modules.Disabler;
import com.example.addon.modules.ElytraBouncePlus;
import com.example.addon.modules.FastBreak;
import com.example.addon.modules.ModuleExample;
import com.example.addon.modules.NecronConfig;
import com.example.addon.modules.Nuker;
import com.example.addon.modules.movement.VelocityBoost;
import com.example.addon.modules.VerticalVelocityTracker;
import com.example.addon.modules.player.AutoElytraRestock;
import com.example.addon.modules.player.LoadoutSave;
import com.example.addon.modules.player.PacketAntiKick;
import com.example.addon.modules.render.ContainerPreview;
import com.example.addon.utils.PacketLimiter;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class AddonTemplate extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Example");
    public static final HudGroup HUD_GROUP = new HudGroup("Example");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Meteor Addon Template");

        // Global per-tick packet budget reset (safety net shared by all modules).
        MeteorClient.EVENT_BUS.subscribe(PacketLimiter.class);

        // Existing modules
        Modules.get().add(new ModuleExample());
        Modules.get().add(new Nuker());
        Modules.get().add(new FastBreak());
        Modules.get().add(new ElytraBouncePlus());
        Modules.get().add(new Disabler());

        // Shared config
        Modules.get().add(new NecronConfig());

        // Movement
        Modules.get().add(new VelocityBoost());
        Modules.get().add(new VerticalVelocityTracker());

        // Player / inventory
        Modules.get().add(new PacketAntiKick());
        Modules.get().add(new AutoElytraRestock());
        Modules.get().add(new LoadoutSave());

        // Render
        Modules.get().add(new ContainerPreview());

        // Commands
        Commands.add(new CommandExample());
        Commands.add(new LoadoutCommand());

        // HUD
        Hud.get().register(HudExample.INFO);
        Hud.get().register(ElytraDurabilityHud.INFO);
        Hud.get().register(VerticalVelocityHud.INFO);
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.example.addon";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("MeteorDevelopment", "meteor-addon-template");
    }
}
