package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;

/**
 * Global addon settings. Not a gameplay module — it holds the fallback Discord webhook URL that
 * any module uses when its own webhook field is blank. Its settings are readable whether or not
 * the module is toggled on, so {@link #getDefaultWebhook()} works regardless of active state.
 */
public class NecronConfig extends Module {

    private static NecronConfig INSTANCE;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<String> defaultWebhookUrl = sgGeneral.add(new StringSetting.Builder()
        .name("default-webhook-url")
        .description("Global fallback Discord webhook URL used by any module whose own webhook field is blank.")
        .defaultValue("")
        .build());

    public NecronConfig() {
        super(AddonTemplate.CATEGORY, "necron-config",
            "Global NecronUtility settings (fallback Discord webhook). Always-on config holder.");
        INSTANCE = this;
    }

    /** The configured global fallback webhook URL, or "" if none / not yet constructed. */
    public static String getDefaultWebhook() {
        return INSTANCE != null ? INSTANCE.defaultWebhookUrl.get() : "";
    }

    /** Returns {@code own} if non-blank, otherwise the global fallback. */
    public static String resolveWebhook(String own) {
        if (own != null && !own.isBlank()) return own;
        return getDefaultWebhook();
    }
}
