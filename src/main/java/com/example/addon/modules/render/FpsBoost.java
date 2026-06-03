package com.example.addon.modules.render;

import com.example.addon.DWAddons;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import net.minecraft.client.option.CloudRenderMode;
import net.minecraft.client.option.GraphicsMode;
import net.minecraft.particle.ParticlesMode;

/**
 * Lowers graphics options for more FPS, restoring the originals on disable.
 *
 * <p>Every knob is now a setting instead of a hardcoded minimum. The defaults are still aggressive
 * (the module's purpose is FPS), but two of them matter specifically for 2b2t:
 * <ul>
 *   <li><b>render-distance</b> — the old build forced this to 2, which is unusable for elytra
 *       highway travel: you can't see (or let the obstacle passer path around) walls/portal traps
 *       until you're on top of them. Raise it to ~5+ when flying highways.</li>
 *   <li><b>simulation-distance</b> — kept separate so you can drop tick-load without blinding
 *       yourself.</li>
 * </ul>
 *
 * <p>Each visual toggle that is left off simply leaves your current value untouched, and the heavy
 * {@code reloadResourcesConcurrently()} (needed only for a mipmap change) is skipped unless the
 * mipmap toggle is actually on.
 */
public class FpsBoost extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgVisual  = settings.createGroup("Visual");

    // ── Distances / limits ───────────────────────────────────────────────
    private final Setting<Integer> renderDistance = sgGeneral.add(new IntSetting.Builder()
        .name("render-distance")
        .description("View distance (chunks) while active. Raise to ~5+ for elytra highway travel so you can see obstacles ahead.")
        .defaultValue(2)
        .min(2).max(32)
        .sliderRange(2, 16)
        .build());

    private final Setting<Integer> simulationDistance = sgGeneral.add(new IntSetting.Builder()
        .name("simulation-distance")
        .description("Simulation distance (chunks) while active.")
        .defaultValue(5)
        .min(5).max(32)
        .sliderRange(5, 16)
        .build());

    private final Setting<Integer> maxFps = sgGeneral.add(new IntSetting.Builder()
        .name("max-fps")
        .description("FPS limit while active. 260 = Unlimited.")
        .defaultValue(260)
        .min(30).max(260)
        .sliderRange(30, 260)
        .build());

    private final Setting<Double> entityDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("entity-distance")
        .description("Entity render-distance scaling while active (vanilla range 0.5–5.0).")
        .defaultValue(0.5)
        .min(0.5).max(5.0)
        .sliderRange(0.5, 5.0)
        .build());

    private final Setting<Boolean> disableVsync = sgGeneral.add(new BoolSetting.Builder()
        .name("disable-vsync")
        .description("Turn VSync off (uncaps FPS to the limit above).")
        .defaultValue(true)
        .build());

    // ── Visual toggles (each off = leave your current value alone) ────────
    private final Setting<Boolean> fastGraphics = sgVisual.add(new BoolSetting.Builder()
        .name("fast-graphics").description("Use FAST graphics mode.").defaultValue(true).build());

    private final Setting<Boolean> disableClouds = sgVisual.add(new BoolSetting.Builder()
        .name("disable-clouds").description("Turn clouds off.").defaultValue(true).build());

    private final Setting<Boolean> minimalParticles = sgVisual.add(new BoolSetting.Builder()
        .name("minimal-particles").description("Set particles to MINIMAL.").defaultValue(true).build());

    private final Setting<Boolean> disableEntityShadows = sgVisual.add(new BoolSetting.Builder()
        .name("disable-entity-shadows").description("Turn entity shadows off.").defaultValue(true).build());

    private final Setting<Boolean> disableViewBob = sgVisual.add(new BoolSetting.Builder()
        .name("disable-view-bob").description("Turn view bobbing off.").defaultValue(true).build());

    private final Setting<Boolean> disableAo = sgVisual.add(new BoolSetting.Builder()
        .name("disable-ao").description("Turn smooth lighting (ambient occlusion) off.").defaultValue(true).build());

    private final Setting<Boolean> zeroBiomeBlend = sgVisual.add(new BoolSetting.Builder()
        .name("zero-biome-blend").description("Set biome blend radius to 0.").defaultValue(true).build());

    private final Setting<Boolean> disableMipmaps = sgVisual.add(new BoolSetting.Builder()
        .name("disable-mipmaps").description("Set mipmap levels to 0. Triggers a one-off resource reload on toggle.").defaultValue(true).build());

    private final Setting<Boolean> disableScreenEffects = sgVisual.add(new BoolSetting.Builder()
        .name("disable-screen-effects").description("Zero the glint / distortion / FOV / darkness / damage-tilt effect scales.").defaultValue(true).build());

    public FpsBoost() {
        super(DWAddons.CATEGORY, "fps-boost", "Lowers graphics options for more FPS. Every knob is configurable; raise render-distance for highway travel.");
    }

    // ── Saved originals ───────────────────────────────────────────────────
    private int    savedRenderDistance;
    private int    savedSimulationDistance;
    private int    savedFpsLimit;
    private GraphicsMode savedGraphics;
    private CloudRenderMode savedClouds;
    private ParticlesMode savedParticles;
    private boolean savedEntityShadows;
    private boolean savedBobView;
    private int    savedBiomeBlend;
    private boolean savedVsync;
    private double  savedEntityDistance;
    private int    savedMipmapLevels;
    private boolean savedAo;
    private double  savedGlintSpeed;
    private double  savedGlintStrength;
    private double  savedDistortionEffect;
    private double  savedFovEffect;
    private double  savedDarknessEffect;
    private double  savedDamageTilt;
    private boolean appliedMipmaps; // whether activate actually changed mipmaps (so deactivate reloads to match)

    @Override
    public void onActivate() {
        var opts = mc.options;

        // Save everything first so restore is exact regardless of which toggles are on.
        savedRenderDistance     = opts.getViewDistance().getValue();
        savedSimulationDistance = opts.getSimulationDistance().getValue();
        savedFpsLimit           = opts.getMaxFps().getValue();
        savedGraphics           = opts.getGraphicsMode().getValue();
        savedClouds             = opts.getCloudRenderMode().getValue();
        savedParticles          = opts.getParticles().getValue();
        savedEntityShadows      = opts.getEntityShadows().getValue();
        savedBobView            = opts.getBobView().getValue();
        savedBiomeBlend         = opts.getBiomeBlendRadius().getValue();
        savedVsync              = opts.getEnableVsync().getValue();
        savedEntityDistance     = opts.getEntityDistanceScaling().getValue();
        savedMipmapLevels       = opts.getMipmapLevels().getValue();
        savedAo                 = opts.getAo().getValue();
        savedGlintSpeed         = opts.getGlintSpeed().getValue();
        savedGlintStrength      = opts.getGlintStrength().getValue();
        savedDistortionEffect   = opts.getDistortionEffectScale().getValue();
        savedFovEffect          = opts.getFovEffectScale().getValue();
        savedDarknessEffect     = opts.getDarknessEffectScale().getValue();
        savedDamageTilt         = opts.getDamageTiltStrength().getValue();

        // Apply configured values.
        opts.getViewDistance().setValue(renderDistance.get());
        opts.getSimulationDistance().setValue(simulationDistance.get());
        opts.getMaxFps().setValue(maxFps.get());
        opts.getEntityDistanceScaling().setValue(entityDistance.get());
        if (disableVsync.get())          opts.getEnableVsync().setValue(false);
        if (fastGraphics.get())          opts.getGraphicsMode().setValue(GraphicsMode.FAST);
        if (disableClouds.get())         opts.getCloudRenderMode().setValue(CloudRenderMode.OFF);
        if (minimalParticles.get())      opts.getParticles().setValue(ParticlesMode.MINIMAL);
        if (disableEntityShadows.get())  opts.getEntityShadows().setValue(false);
        if (disableViewBob.get())        opts.getBobView().setValue(false);
        if (disableAo.get())             opts.getAo().setValue(false);
        if (zeroBiomeBlend.get())        opts.getBiomeBlendRadius().setValue(0);
        if (disableMipmaps.get())        opts.getMipmapLevels().setValue(0);
        if (disableScreenEffects.get()) {
            opts.getGlintSpeed().setValue(0.0);
            opts.getGlintStrength().setValue(0.0);
            opts.getDistortionEffectScale().setValue(0.0);
            opts.getFovEffectScale().setValue(0.0);
            opts.getDarknessEffectScale().setValue(0.0);
            opts.getDamageTiltStrength().setValue(0.0);
        }

        opts.write();
        appliedMipmaps = disableMipmaps.get();
        if (appliedMipmaps) mc.reloadResourcesConcurrently(); // mipmap change needs a resource reload
        if (mc.worldRenderer != null) mc.worldRenderer.reload();
    }

    @Override
    public void onDeactivate() {
        var opts = mc.options;

        opts.getViewDistance().setValue(savedRenderDistance);
        opts.getSimulationDistance().setValue(savedSimulationDistance);
        opts.getMaxFps().setValue(savedFpsLimit);
        opts.getGraphicsMode().setValue(savedGraphics);
        opts.getCloudRenderMode().setValue(savedClouds);
        opts.getParticles().setValue(savedParticles);
        opts.getEntityShadows().setValue(savedEntityShadows);
        opts.getBobView().setValue(savedBobView);
        opts.getBiomeBlendRadius().setValue(savedBiomeBlend);
        opts.getEnableVsync().setValue(savedVsync);
        opts.getEntityDistanceScaling().setValue(savedEntityDistance);
        opts.getMipmapLevels().setValue(savedMipmapLevels);
        opts.getAo().setValue(savedAo);
        opts.getGlintSpeed().setValue(savedGlintSpeed);
        opts.getGlintStrength().setValue(savedGlintStrength);
        opts.getDistortionEffectScale().setValue(savedDistortionEffect);
        opts.getFovEffectScale().setValue(savedFovEffect);
        opts.getDarknessEffectScale().setValue(savedDarknessEffect);
        opts.getDamageTiltStrength().setValue(savedDamageTilt);

        opts.write();
        // Reload if we changed mipmaps on activate, even if the toggle was flipped off meanwhile.
        if (appliedMipmaps) mc.reloadResourcesConcurrently();
        if (mc.worldRenderer != null) mc.worldRenderer.reload();
    }
}
