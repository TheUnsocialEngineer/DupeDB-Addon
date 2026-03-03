/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package com.jackywacky.dupedb.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;

import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.command.CommandSource;
import net.minecraft.network.packet.c2s.play.RequestCommandCompletionsC2SPacket;
import net.minecraft.network.packet.s2c.play.CommandSuggestionsS2CPacket;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

public class DupedbSearch extends Command {
    // OAuth: App must be registered with DupeDB superuser. See oauth-integration.md
    private static final String DUPEDB_APP_ID = "jackys-meteor-client-dupedb";
    private static final int OAUTH_CALLBACK_PORT = 38475;
    private static final String OAUTH_REDIRECT_URI = "http://localhost:" + OAUTH_CALLBACK_PORT + "/dupedb-callback";

    private static final String COMPLETION_STARTS = "/:abcdefghijklmnopqrstuvwxyz0123456789-";
    private static final String DUPEDB_STATS_URL = "https://dupedb.net/api/stats";
    private static final String DUPEDB_EXPLOITS_URL = "https://dupedb.net/api/exploits";
    private static final String DUPEDB_OAUTH_AUTHORIZE_URL = "https://dupedb.net/api/oauth/authorize";
    private static final String DUPEDB_SETTINGS_URL = "https://dupedb.net/settings";
    private static final int EXPLOITS_PER_PAGE = 20;
    private static final Path TOKEN_FILE = MeteorClient.FOLDER.toPath().resolve("dupedb_token.txt");

    private static String loadToken() {
        try {
            if (Files.exists(TOKEN_FILE)) {
                return Files.readString(TOKEN_FILE).trim();
            }
        } catch (IOException ignored) {}
        return null;
    }

    private static void saveToken(String token) {
        try {
            Files.createDirectories(TOKEN_FILE.getParent());
            Files.writeString(TOKEN_FILE, token);
        } catch (IOException e) {
            MeteorClient.LOG.error("Failed to save DupeDB token", e);
        }
    }

    private static void clearToken() {
        try {
            if (Files.exists(TOKEN_FILE)) {
                Files.delete(TOKEN_FILE);
            }
        } catch (IOException ignored) {}
    }

    private static String dupedbFetch(String urlString, Consumer<String> debug) {
        try {
            URL url = URI.create(urlString).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0");
            conn.setRequestProperty("Accept", "application/json, text/html, application/xhtml+xml, application/xml;q=0.9, */*;q=0.8");
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.5");
            String token = loadToken();
            if (token != null && !token.isBlank()) {
                conn.setRequestProperty("X-App-Token", token);
            }
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setInstanceFollowRedirects(true);

            int code = conn.getResponseCode();
            java.io.InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = "";
            if (stream != null) {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(stream))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null) sb.append(line);
                    body = sb.toString();
                }
            }

            if (code != 200) {
                String preview = body.length() > 20 ? body.substring(0, 20) + "..." : body;
                debug.accept("HTTP " + code + ", body: " + preview);
                return null;
            }
            return body;
        } catch (Exception e) {
            debug.accept("Exception: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private int ticks = 0;
    private boolean tick = false;
    private boolean pluginsOnly = false;
    private final Set<String> plugins = ConcurrentHashMap.newKeySet();

    public DupedbSearch() {
        super("DupeDB", "Search DupeDB for exploits matching server plugins.");
        MeteorClient.EVENT_BUS.subscribe(this);
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(literal("search").executes(ctx -> {
            if (mc.isIntegratedServerRunning()) {
                error("Must be connected to a multiplayer server.");
                return SINGLE_SUCCESS;
            }
            startSearch(false);
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("plugins").executes(ctx -> {
            if (mc.isIntegratedServerRunning()) {
                error("Must be connected to a multiplayer server.");
                return SINGLE_SUCCESS;
            }
            startSearch(true);
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("query").then(argument("search", StringArgumentType.greedyString()).executes(ctx -> {
            String search = StringArgumentType.getString(ctx, "search").trim();
            if (search.isBlank()) {
                error("Search term cannot be empty.");
                return 0;
            }
            queryDupedb(search);
            return SINGLE_SUCCESS;
        })));

        builder.then(literal("login").executes(ctx -> {
            info("Opening browser for DupeDB OAuth authorization...");
            new Thread(() -> runOAuthLogin()).start();
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("revoke").executes(ctx -> {
            clearToken();
            info("Token cleared. To revoke on DupeDB's side, visit your account settings.");
            try {
                Desktop.getDesktop().browse(URI.create(DUPEDB_SETTINGS_URL));
                info("Opened DupeDB settings in browser.");
            } catch (Exception e) {
                info("Visit %s to revoke app access.", DUPEDB_SETTINGS_URL);
            }
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("status").executes(ctx -> {
            String token = loadToken();
            if (token != null && !token.isBlank()) {
                info("DupeDB OAuth: (highlight)Authenticated(default)");
            } else {
                info("DupeDB OAuth: (red)Not authenticated(default). Run (highlight)%s login(default) to authenticate.", toString());
            }
            return SINGLE_SUCCESS;
        }));
    }

    private void runOAuthLogin() {
        HttpServer server = null;
        try {
            String authUrl = DUPEDB_OAUTH_AUTHORIZE_URL + "?app_id=" + URLEncoder.encode(DUPEDB_APP_ID, StandardCharsets.UTF_8)
                + "&redirect_uri=" + URLEncoder.encode(OAUTH_REDIRECT_URI, StandardCharsets.UTF_8);

            AtomicReference<String> tokenReceived = new AtomicReference<>();
            AtomicReference<String> errorReceived = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);

            server = HttpServer.create(new InetSocketAddress("127.0.0.1", OAUTH_CALLBACK_PORT), 0);
            server.createContext("/dupedb-callback", exchange -> {
                try {
                    exchange.getRequestBody().close();
                    Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
                    String code = params.get("code");
                    String error = params.get("error");

                    String html;
                    if (error != null) {
                        errorReceived.set(error);
                        html = "<html><body><h1>Authorization denied</h1><p>Error: " + escapeHtml(error) + "</p><p>You can close this window.</p></body></html>";
                    } else if (code != null && !code.isBlank()) {
                        tokenReceived.set(code.trim());
                        html = "<html><body><h1>Success!</h1><p>You are now authenticated. You can close this window and return to Minecraft.</p></body></html>";
                    } else {
                        html = "<html><body><h1>No response</h1><p>Missing code or error. You can close this window.</p></body></html>";
                    }

                byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
                } finally {
                    latch.countDown();
                }
            });
            server.setExecutor(null);
            server.start();

            Thread.sleep(300);
            mc.execute(() -> info("Callback server ready on port %d. Opening browser...", OAUTH_CALLBACK_PORT));
            Desktop.getDesktop().browse(URI.create(authUrl));

            if (!latch.await(5, TimeUnit.MINUTES)) {
                mc.execute(() -> error("OAuth timeout. Complete the login in your browser and try again."));
                return;
            }

            String token = tokenReceived.get();
            String error = errorReceived.get();

            mc.execute(() -> {
                if (token != null) {
                    saveToken(token);
                    info("DupeDB OAuth: Successfully authenticated!(default) You can now use (highlight)%s search(default) and (highlight)%s query(default).", toString(), toString());
                } else if (error != null) {
                    error("DupeDB OAuth failed: %s", error);
                } else {
                    error("DupeDB OAuth: No response received.");
                }
            });
        } catch (java.net.BindException e) {
            mc.execute(() -> error("Port %d in use. Close other DupeDB login windows and retry.", OAUTH_CALLBACK_PORT));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            mc.execute(() -> error("OAuth login interrupted."));
        } catch (Exception e) {
            mc.execute(() -> error("OAuth login failed: %s", e.getMessage()));
            MeteorClient.LOG.error("DupeDB OAuth error", e);
        } finally {
            if (server != null) server.stop(0);
        }
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null) return map;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                String value = eq < pair.length() - 1 ? URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8) : "";
                map.put(key, value);
            }
        }
        return map;
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private void queryDupedb(String searchTerm) {
        info("Searching DupeDB for '%s'...", searchTerm);
        new Thread(() -> {
            try {
                String encoded = URLEncoder.encode(searchTerm, StandardCharsets.UTF_8);
                Consumer<String> debug = msg -> mc.execute(() -> info("[DupeDB debug] %s", msg));

                List<JsonObject> allExploits = new ArrayList<>();
                int page = 1;
                int totalPages = 1;

                do {
                    String url = DUPEDB_EXPLOITS_URL + "?page=" + page + "&limit=" + EXPLOITS_PER_PAGE
                        + "&search=" + encoded + "&sort=date_submitted&order=desc";
                    String json = dupedbFetch(url, debug);
                    if (json == null) break;

                    JsonObject response = JsonParser.parseString(json).getAsJsonObject();
                    JsonArray exploits = response.has("exploits") ? response.getAsJsonArray("exploits") : null;
                    if (exploits == null || exploits.isEmpty()) break;

                    for (JsonElement elem : exploits) {
                        if (elem.isJsonObject()) allExploits.add(elem.getAsJsonObject());
                    }

                    if (response.has("pagination")) {
                        JsonObject pagination = response.getAsJsonObject("pagination");
                        totalPages = pagination.has("pages") ? pagination.get("pages").getAsInt() : 1;
                    }
                    page++;
                    if (page <= totalPages) Thread.sleep(200);
                } while (page <= totalPages);

                if (allExploits.isEmpty()) {
                    mc.execute(() -> info("No exploits found for '%s'.", searchTerm));
                    return;
                }

                mc.execute(() -> {
                    info("Found %d exploit(s) for '%s':", allExploits.size(), searchTerm);
                    for (JsonObject exploit : allExploits) {
                        String id = exploit.has("id") ? exploit.get("id").getAsString() : null;
                        if (id == null) continue;
                        String name = exploit.has("name") ? exploit.get("name").getAsString() : "Exploit #" + id;
                        String link = "https://dupedb.net/exploit/" + id;
                        MutableText text = Text.literal(name)
                            .setStyle(Style.EMPTY
                                .withClickEvent(new ClickEvent.OpenUrl(URI.create(link)))
                                .withUnderline(true));
                        info(text);
                    }
                });
            } catch (Exception e) {
                mc.execute(() -> error("Failed to query DupeDB: %s", e.getMessage()));
                e.printStackTrace();
            }
        }).start();
    }

    private void startSearch(boolean pluginsOnly) {
        this.pluginsOnly = pluginsOnly;
        ticks = 0;
        tick = true;
        plugins.clear();

        info(pluginsOnly ? "Gathering server plugins..." : "Gathering server plugins (same as server command)...");
        Random random = new Random();
        new Thread(() -> {
            for (int i = 0; i < COMPLETION_STARTS.length(); i++) {
                final char c = COMPLETION_STARTS.charAt(i);
                mc.execute(() -> {
                    if (mc.player != null && mc.player.networkHandler != null) {
                        mc.player.networkHandler.sendPacket(
                            new RequestCommandCompletionsC2SPacket(random.nextInt(200), String.valueOf(c))
                        );
                    }
                });
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }).start();
    }

    private void onPluginsReady() {
        tick = false;
        List<String> pluginList = new ArrayList<>(plugins);
        pluginList.sort(String.CASE_INSENSITIVE_ORDER);

        if (pluginList.isEmpty()) {
            error("No plugins found. Make sure you have permission to view the command tree.");
            return;
        }

        if (pluginsOnly) {
            info("Found %d plugin(s):", pluginList.size());
            for (String name : pluginList) {
                info("  %s", name);
            }
            return;
        }

        info("Found %d plugins. Querying DupeDB...", pluginList.size());

        new Thread(() -> searchDupedb(pluginList)).start();
    }

    private void searchDupedb(List<String> serverPlugins) {
        try {
            Consumer<String> debug = msg -> mc.execute(() -> info("[DupeDB debug] %s", msg));
            String statsJson = dupedbFetch(DUPEDB_STATS_URL, debug);
            if (statsJson == null) {
                mc.execute(() -> error("Failed to fetch DupeDB stats. Run (highlight)%s login(default) to authenticate.", toString()));
                return;
            }

            JsonObject stats = JsonParser.parseString(statsJson).getAsJsonObject();
            int totalExploits = stats.has("total") ? stats.get("total").getAsInt() : 0;

            List<JsonObject> allExploits = new ArrayList<>();
            int page = 1;
            int fetched = 0;

            while (fetched < totalExploits || (totalExploits == 0 && page == 1)) {
                String url = DUPEDB_EXPLOITS_URL + "?page=" + page + "&limit=" + EXPLOITS_PER_PAGE
                    + "&sort=date_submitted&order=desc";
                String exploitsJson = dupedbFetch(url, debug);

                if (exploitsJson == null) break;

                JsonElement parsed = JsonParser.parseString(exploitsJson);
                JsonArray exploitsArray;

                if (parsed.isJsonArray()) {
                    exploitsArray = parsed.getAsJsonArray();
                } else if (parsed.isJsonObject()) {
                    JsonObject obj = parsed.getAsJsonObject();
                    if (obj.has("exploits")) {
                        exploitsArray = obj.getAsJsonArray("exploits");
                    } else if (obj.has("data")) {
                        exploitsArray = obj.getAsJsonArray("data");
                    } else if (obj.has("items")) {
                        exploitsArray = obj.getAsJsonArray("items");
                    } else {
                        break;
                    }
                } else {
                    break;
                }

                if (exploitsArray == null || exploitsArray.isEmpty()) break;

                for (JsonElement elem : exploitsArray) {
                    if (elem.isJsonObject()) allExploits.add(elem.getAsJsonObject());
                }
                fetched += exploitsArray.size();
                if (exploitsArray.size() < EXPLOITS_PER_PAGE) break;
                page++;
                if (totalExploits == 0 && page > 10) break;
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            Set<String> pluginNamesLower = new HashSet<>();
            for (String p : serverPlugins) {
                pluginNamesLower.add(p.toLowerCase());
            }

            List<MatchResult> matches = new ArrayList<>();
            for (JsonObject exploit : allExploits) {
                boolean isPluginSpecific = false;
                if (exploit.has("is_plugin_specific")) {
                    JsonElement e = exploit.get("is_plugin_specific");
                    isPluginSpecific = e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()
                        ? e.getAsInt() != 0 : e.getAsBoolean();
                }
                if (!isPluginSpecific) continue;

                String pluginName = exploit.has("plugin_name") ? exploit.get("plugin_name").getAsString() : null;
                if (pluginName == null || pluginName.isBlank()) continue;

                String pluginVersion = exploit.has("plugin_version") && !exploit.get("plugin_version").isJsonNull()
                    ? exploit.get("plugin_version").getAsString() : null;
                if (pluginVersion != null) pluginVersion = pluginVersion.trim();

                boolean nameMatches = pluginNamesLower.contains(pluginName.toLowerCase());
                if (!nameMatches) continue;

                String exploitId = exploit.has("id") ? exploit.get("id").getAsString()
                    : exploit.has("_id") ? exploit.get("_id").getAsString() : null;
                if (exploitId == null) continue;

                String exploitTitle = exploit.has("name") ? exploit.get("name").getAsString()
                    : exploit.has("title") ? exploit.get("title").getAsString() : "Exploit #" + exploitId;

                boolean versionConfirmed = (pluginVersion == null || pluginVersion.isEmpty() || "*".equals(pluginVersion));

                matches.add(new MatchResult(
                    "https://dupedb.net/exploit/" + exploitId,
                    exploitTitle,
                    versionConfirmed
                ));
            }

            mc.execute(() -> {
                if (matches.isEmpty()) {
                    info("No plugin-specific dupes found matching your server plugins.");
                    return;
                }
                info("Found %d matching exploit(s):", matches.size());
                for (MatchResult match : matches) {
                    MutableText line = Text.literal(match.title)
                        .setStyle(Style.EMPTY
                            .withClickEvent(new ClickEvent.OpenUrl(URI.create(match.url)))
                            .withUnderline(true));
                    MutableText status = match.versionConfirmed
                        ? Text.literal(" confirmed").formatted(Formatting.GREEN)
                        : Text.literal(" match").formatted(Formatting.YELLOW);
                    info(line.append(status));
                }
            });
        } catch (Exception e) {
            mc.execute(() -> error("Failed to search DupeDB: %s", e.getMessage()));
            e.printStackTrace();
        }
    }

    private static class MatchResult {
        final String url;
        final String title;
        final boolean versionConfirmed;

        MatchResult(String url, String title, boolean versionConfirmed) {
            this.url = url;
            this.title = title;
            this.versionConfirmed = versionConfirmed;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!tick) return;
        ticks++;
        if (ticks >= 150) {
            onPluginsReady();
        }
    }

    @EventHandler
    private void onReadPacket(PacketEvent.Receive event) {
        if (!tick) return;
        if (event.packet instanceof CommandSuggestionsS2CPacket packet) {
            Suggestions matches = packet.getSuggestions();
            if (matches == null || matches.isEmpty()) return;

            for (Suggestion suggestion : matches.getList()) {
                String text = suggestion.getText();
                String[] parts = text.split(":");
                if (parts.length > 1) {
                    String pluginName = parts[0].replace("/", "").trim();
                    if (!pluginName.isEmpty()) {
                        plugins.add(pluginName.toLowerCase());
                    }
                }
            }
        }
    }
}
