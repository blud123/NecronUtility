package com.example.addon.utils;

import net.minecraft.util.math.Vec3d;

/**
 * Shared cardinal-highway detection for the movement modules.
 *
 * <p>2b2t's main highways run along the {@code x=0} and {@code z=0} axis lines (plus the
 * diagonals). The old per-module detection used {@code pos % 1000}, which false-positives on
 * open terrain because it treats every 1000-block interval as a highway. This helper instead
 * only claims an axis when the player is both travelling predominantly along it AND within
 * {@code tol} blocks of that axis's centerline.
 */
public final class HighwayUtil {

    public enum Axis { X_AXIS, Z_AXIS, NONE }

    private HighwayUtil() {}

    /**
     * @param pos current player position
     * @param vel current player velocity
     * @param tol how close (blocks) to the x=0 / z=0 centerline counts as "on the highway"
     * @return the highway axis the player is travelling along, or {@link Axis#NONE}
     */
    public static Axis detect(Vec3d pos, Vec3d vel, double tol) {
        double absVx = Math.abs(vel.x);
        double absVz = Math.abs(vel.z);

        boolean movingX = absVx > absVz * 3.0;
        boolean movingZ = absVz > absVx * 3.0;

        if (movingX && Math.abs(pos.z) < tol) return Axis.X_AXIS;
        if (movingZ && Math.abs(pos.x) < tol) return Axis.Z_AXIS;
        return Axis.NONE;
    }
}
