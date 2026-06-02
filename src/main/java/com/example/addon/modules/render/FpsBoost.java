package com.example.addon.modules.render;

import com.example.addon.DWAddons;
import meteordevelopment.meteorclient.systems.modules.Module;
import net.minecraft.client.option.CloudRenderMode;
import net.minecraft.client.option.GraphicsMode;
import net.minecraft.particle.ParticlesMode;

public class FpsBoost extends Module {

    public FpsBoost() {
        super(DWAddons.CATEGORY, "fps-boost", "Sets every graphics option to minimum for maximum FPS.");
    }

    // saved values
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

    @Override
    public void onActivate() {
        var opts = mc.options;

        // Save everything
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

        // Slam everything to minimum
        opts.getViewDistance().setValue(2);
        opts.getSimulationDistance().setValue(5);
        opts.getMaxFps().setValue(260);           // 260 = "Unlimited" in vanilla
        opts.getGraphicsMode().setValue(GraphicsMode.FAST);
        opts.getCloudRenderMode().setValue(CloudRenderMode.OFF);
        opts.getParticles().setValue(ParticlesMode.MINIMAL);
        opts.getEntityShadows().setValue(false);
        opts.getBobView().setValue(false);
        opts.getBiomeBlendRadius().setValue(0);
        opts.getEnableVsync().setValue(false);
        opts.getEntityDistanceScaling().setValue(0.5);
        opts.getMipmapLevels().setValue(0);       // no texture filtering
        opts.getAo().setValue(false);             // no ambient occlusion (smooth lighting)
        opts.getGlintSpeed().setValue(0.0);       // no enchant glint animation
        opts.getGlintStrength().setValue(0.0);
        opts.getDistortionEffectScale().setValue(0.0); // no nausea/portal warp
        opts.getFovEffectScale().setValue(0.0);   // no FOV zoom on speed/slow
        opts.getDarknessEffectScale().setValue(0.0);   // no darkness effect flicker
        opts.getDamageTiltStrength().setValue(0.0);    // no screen tilt on damage

        mc.options.write();
        mc.reloadResourcesConcurrently();         // applies mipmap change
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

        mc.options.write();
        mc.reloadResourcesConcurrently();
        if (mc.worldRenderer != null) mc.worldRenderer.reload();
    }
}
