package com.example.addon;

import com.example.addon.commands.LoadoutCommand;
import com.example.addon.hud.ElytraDurabilityHud;
import com.example.addon.hud.PacketTrackerHud;
import com.example.addon.hud.VerticalVelocityHud;
import com.example.addon.modules.Disabler;
import com.example.addon.modules.ElytraFlyPlusPlus;
import com.example.addon.modules.FastBreak;
import com.example.addon.modules.NecronConfig;
import com.example.addon.modules.Nuker;
import com.example.addon.modules.movement.Ascend;
import com.example.addon.modules.movement.Blink;
import com.example.addon.modules.movement.MovementProbe;
import com.example.addon.modules.movement.PacketLogger;
import com.example.addon.modules.movement.PacketTracker;
import com.example.addon.modules.movement.RubberbandLogger;
import com.example.addon.modules.movement.VelocityBoost;
import com.example.addon.modules.movement.VerticalYBoost;
import com.example.addon.modules.VerticalVelocityTracker;
import com.example.addon.modules.misc.QueueAlert;
import com.example.addon.modules.player.AutoElytraRestock;
import com.example.addon.modules.player.LoadoutSave;
import com.example.addon.modules.player.PacketAntiKick;
import com.example.addon.modules.render.ContainerPreview;
import com.example.addon.modules.render.FpsBoost;
import com.example.addon.modules.world.ChunkFinder;
import com.example.addon.modules.world.StashFinder;
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

public class DWAddons extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("DW Addons");
    public static final HudGroup HUD_GROUP = new HudGroup("DW Addons");

    @Override
    public void onInitialize() {
        LOG.info("Initializing DW Addons");

        // Global per-tick packet budget reset (safety net shared by all modules).
        MeteorClient.EVENT_BUS.subscribe(PacketLimiter.class);

        // Combat / world
        Modules.get().add(new Nuker());
        Modules.get().add(new FastBreak());
        Modules.get().add(new ElytraFlyPlusPlus());
        Modules.get().add(new Disabler());

        // Shared config
        Modules.get().add(new NecronConfig());

        // Movement
        Modules.get().add(new Ascend());
        Modules.get().add(new MovementProbe());
        Modules.get().add(new Blink());
        Modules.get().add(new VelocityBoost());
        Modules.get().add(new PacketTracker());
        Modules.get().add(new PacketLogger());
        Modules.get().add(new RubberbandLogger());
        Modules.get().add(new VerticalYBoost());
        Modules.get().add(new VerticalVelocityTracker());

        // Player / inventory
        Modules.get().add(new PacketAntiKick());
        Modules.get().add(new AutoElytraRestock());
        Modules.get().add(new LoadoutSave());

        // World / stash hunting
        Modules.get().add(new StashFinder());
        Modules.get().add(new ChunkFinder());

        // Misc
        Modules.get().add(new QueueAlert());

        // Render
        Modules.get().add(new ContainerPreview());
        Modules.get().add(new FpsBoost());

        // Commands
        Commands.add(new LoadoutCommand());

        // HUD
        Hud.get().register(ElytraDurabilityHud.INFO);
        Hud.get().register(VerticalVelocityHud.INFO);
        Hud.get().register(PacketTrackerHud.INFO);
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
        return new GithubRepo("deWinterr", "NecronUtility");
    }
}
