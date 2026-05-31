package com.example.addon.utils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Minimal, dependency-free Discord webhook sender. Fully async — calls never block the game
 * thread, and a blank/null URL is a silent no-op. JSON is hand-escaped (no extra deps).
 */
public final class DiscordWebhook {
    private static final HttpClient CLIENT =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private DiscordWebhook() {}

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) switch (c) {
            case '"'  -> b.append("\\\"");
            case '\\' -> b.append("\\\\");
            case '\n' -> b.append("\\n");
            case '\r' -> b.append("\\r");
            case '\t' -> b.append("\\t");
            default   -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
        }
        return b.toString();
    }

    public static void sendMessage(String url, String content) {
        if (url == null || url.isBlank()) return;
        post(url, "{\"content\":\"" + escape(content) + "\"}");
    }

    public static void sendEmbed(String url, String title, String description, int color) {
        if (url == null || url.isBlank()) return;
        post(url, "{\"embeds\":[{\"title\":\"" + escape(title)
            + "\",\"description\":\"" + escape(description)
            + "\",\"color\":" + (color & 0xFFFFFF) + "}]}");
    }

    private static void post(String url, String json) {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
            .header("Content-Type", "application/json").timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        CompletableFuture.runAsync(() -> {
            try { CLIENT.send(req, HttpResponse.BodyHandlers.discarding()); }
            catch (Exception ignored) {}
        });
    }
}
