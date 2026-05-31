package com.example.addon.utils;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Appends waypoints to Xaero's Minimap on-disk waypoint files. Only acts when Xaero is loaded.
 * The line format is version-sensitive; if waypoints don't appear, create one manually and
 * compare the file under {@code XaeroWaypoints/<server>/dim%N/waypoints.txt}.
 */
public final class XaeroWaypointUtil {
    private XaeroWaypointUtil() {}

    public static boolean isXaeroLoaded() {
        FabricLoader fl = FabricLoader.getInstance();
        return fl.isModLoaded("xaerominimap") || fl.isModLoaded("xaerominimapfair");
    }

    /** colorIndex 0-15. dimension: 0=Overworld, -1=Nether, 1=End. */
    public static void addWaypoint(String name, int x, int y, int z, int colorIndex, int dimension) {
        if (!isXaeroLoaded()) return;
        try {
            Path dir = resolveDir(dimension);
            Files.createDirectories(dir);
            Path file = dir.resolve("waypoints.txt");
            String initials = name.isEmpty() ? "X" : name.substring(0, 1).toUpperCase();
            String line = "waypoint:" + name + ":" + initials + ":" + x + ":" + y + ":" + z
                + ":" + (colorIndex & 15) + ":false:0:gui.xaero_default:false:0:false"
                + System.lineSeparator();
            Files.writeString(file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {}
    }

    private static Path resolveDir(int dimension) {
        Minecraft mc = Minecraft.getInstance();
        ServerData sd = mc.getCurrentServer();
        String server = (sd != null) ? "Multiplayer_" + sd.ip.replace(':', '_') : "Singleplayer";
        String dim = switch (dimension) {
            case -1 -> "dim%-1";
            case 1  -> "dim%1";
            default -> "dim%0";
        };
        return FabricLoader.getInstance().getGameDir()
            .resolve("XaeroWaypoints").resolve(server).resolve(dim);
    }
}
