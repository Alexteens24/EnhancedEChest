package com.enhancedechest.lang;

import com.enhancedechest.config.ConfigMigrations;
import com.enhancedechest.config.PluginConfig;
import com.enhancedechest.config.YamlMigrator;
import com.enhancedechest.model.ChestKind;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class LanguageManager {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .build();

    /**
     * MiniMessage instance for <b>player-supplied</b> chest names. Unlike {@link #MINI} (used for trusted
     * language files) it resolves only cosmetic tags — colours, hex, gradients, rainbows and text
     * decorations — and deliberately omits the interactive ones ({@code <click>}, {@code <hover>},
     * {@code <insertion>}, {@code <selector>}, {@code <font>}, …). An unknown/omitted tag is left as
     * literal text (non-strict), so a name can never carry a runnable command or a fake hover, which would
     * otherwise be an exploit vector when an operator sees the name.
     */
    private static final MiniMessage NAME_MINI = MiniMessage.builder()
            .tags(TagResolver.builder()
                    .resolver(StandardTags.color())
                    .resolver(StandardTags.rainbow())
                    .resolver(StandardTags.gradient())
                    .resolver(StandardTags.decorations())
                    .resolver(StandardTags.reset())
                    .build())
            .build();

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private static final Pattern MINI_TAG_PATTERN = Pattern.compile(
            "<(?i:click\\b|hover\\b|newline\\b|br\\b|reset\\b|r\\b|gray\\b|white\\b|red\\b|green\\b|blue\\b|yellow\\b|gold\\b|aqua\\b|purple\\b|black\\b|dark_[a-z_]+\\b|light_[a-z_]+\\b|bold\\b|b\\b|italic\\b|i\\b|underlined\\b|u\\b|strikethrough\\b|st\\b|obfuscated\\b|obf\\b|transition\\b|gradient\\b|rainbow\\b|color\\b|colour\\b|font\\b|#[a-fA-F0-9]{6}\\b|#[a-fA-F0-9]{3}\\b|/(?:click\\b|hover\\b|color\\b|colour\\b|bold\\b|b\\b|italic\\b|i\\b|underlined\\b|u\\b|strikethrough\\b|st\\b|obfuscated\\b|obf\\b|font\\b))"
    );

    private final JavaPlugin plugin;
    private final PluginConfig config;
    private String locale;
    private FileConfiguration messages;
    private FileConfiguration gui;

    // Hot-path values resolved once per (re)load instead of on every message/open.
    private String cachedPrefix;
    private String cachedPrefixMini;
    private String cachedTitleBase;
    private String cachedTitleTemplate;
    private String cachedTitleTemp;

    // Parsed-Component cache for zero-replacement lookups (the overwhelming majority of dialog button
    // labels/tooltips: no {placeholder} substitution means the parsed result is identical every call).
    // Dialogs are rebuilt on essentially every navigation click, each pulling a dozen-plus getGui()/get()
    // values, so without this every click re-tokenizes and re-parses the same static MiniMessage/legacy
    // strings. Keyed separately per file since "messages" and "gui" keys are independent namespaces;
    // cleared on reload() (via load()) so a changed locale/file is never served stale.
    private final Map<String, Component> messageCache = new ConcurrentHashMap<>();
    private final Map<String, Component> guiCache = new ConcurrentHashMap<>();

    public LanguageManager(JavaPlugin plugin, PluginConfig config, String locale) {
        this.plugin = plugin;
        this.config = config;
        this.locale = locale;
        load();
    }

    public void reload(String locale) {
        this.locale = locale;
        load();
    }

    private void load() {
        String base = "language/" + locale + "/";
        if (plugin.getResource(base + "messages.yml") == null) {
            plugin.getSLF4JLogger().warn("Locale '{}' not found, falling back to en_US", locale);
            locale = "en_US";
            base = "language/en_US/";
        }
        saveDefault(base + "messages.yml");
        saveDefault(base + "gui.yml");

        migrateLanguageFile(base + "messages.yml", ConfigMigrations.MESSAGES);
        migrateLanguageFile(base + "gui.yml",      ConfigMigrations.GUI);

        messages = loadFile(base + "messages.yml");
        gui      = loadFile(base + "gui.yml");

        cachedPrefix        = messages.getString("prefix", "[EnhancedEchest] ");
        if (isMiniMessage(cachedPrefix)) {
            cachedPrefixMini = cachedPrefix;
            cachedPrefix     = LEGACY.serialize(MINI.deserialize(cachedPrefix));
        } else {
            cachedPrefixMini = MINI.serialize(LEGACY.deserialize(cachedPrefix));
        }
        cachedTitleBase     = gui.getString("enderchest.title", "Ender Chest");
        cachedTitleTemplate = gui.getString("enderchest.title-numbered", "Ender Chest {index}");
        cachedTitleTemp     = gui.getString("enderchest.title-temp", "Temporary Storage");

        // A (re)load can change any key's raw text (locale switch, or edited file re-read on /ee reload),
        // so every previously parsed Component is now potentially stale.
        messageCache.clear();
        guiCache.clear();
    }

    private void saveDefault(String path) {
        File file = new File(plugin.getDataFolder(), path);
        if (!file.exists()) {
            plugin.saveResource(path, false);
        }
    }

    private void migrateLanguageFile(String relativePath, java.util.List<YamlMigrator.Rename> renames) {
        YamlMigrator.migrate(
                new File(plugin.getDataFolder(), relativePath),
                plugin.getResource(relativePath),
                renames,
                plugin.getSLF4JLogger()
        );
    }

    private FileConfiguration loadFile(String relativePath) {
        File file = new File(plugin.getDataFolder(), relativePath);
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        InputStream stream = plugin.getResource(relativePath);
        if (stream != null) {
            config.setDefaults(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)));
        }
        return config;
    }

    /**
     * Resolves a message key, substitutes {prefix} and any named {placeholders},
     * then parses the result.
     *
     * Format auto-detection (checked after all substitutions):
     *   - Contains '<'  → MiniMessage
     *   - Otherwise     → legacy '&' codes
     *
     * <p>A zero-replacement call is cached by key ({@code {prefix}} is the only substitution, and it is
     * itself stable per load) — the common case for static labels, so a call site invoked repeatedly
     * (e.g. every dialog rebuild) skips the parse after the first call. Callers passing replacements are
     * data-dependent and always parse fresh.
     */
    public Component get(String key, String... replacements) {
        if (replacements.length == 0) {
            return messageCache.computeIfAbsent(key, k -> {
                String raw = messages.getString(k, k);
                boolean isMini = isMiniMessage(raw);
                String prefix = isMini ? cachedPrefixMini : cachedPrefix;
                return parse(raw.replace("{prefix}", prefix), isMini);
            });
        }
        String raw = messages.getString(key, key);
        boolean isMini = isMiniMessage(raw);
        String prefix = isMini ? cachedPrefixMini : cachedPrefix;
        raw = raw.replace("{prefix}", prefix);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            raw = raw.replace("{" + replacements[i] + "}", replacements[i + 1]);
        }
        return parse(raw, isMini);
    }

    /**
     * Resolves the inventory/display title for a chest. A non-blank custom name is shown
     * verbatim as plain text (no player-supplied formatting). Otherwise chest #1 uses the
     * un-numbered base title ("Ender Chest") and chests 2+ use the numbered template.
     */
    public Component getChestTitle(int index, @org.jetbrains.annotations.Nullable String customName) {
        if (customName != null && !customName.isBlank()) {
            return chestName(customName);
        }
        if (index <= 1) {
            return parse(cachedTitleBase);
        }
        return parse(cachedTitleTemplate.replace("{index}", Integer.toString(index)));
    }

    /**
     * Renders a player-supplied chest name to a display {@link Component}. When
     * {@code enderchest.features.rename-colors} is on, colour/hex/gradient formatting is applied —
     * MiniMessage when the name contains {@code <} (via the restricted {@link #NAME_MINI}, so no
     * interactive tags), otherwise legacy {@code &}/{@code &#RRGGBB} codes. When off, the name is shown
     * verbatim. Any parse error falls back to plain text, so a malformed name is never fatal.
     */
    public Component chestName(String name) {
        if (config == null || !config.isRenameColorsEnabled()) {
            return Component.text(name);
        }
        try {
            return isMiniMessage(name) ? NAME_MINI.deserialize(name) : LEGACY.deserialize(name);
        } catch (RuntimeException e) {
            return Component.text(name);
        }
    }

    /**
     * The plain, formatting-stripped text a chest name would <i>display</i> as — used to match against the
     * rename blacklist so colour codes or MiniMessage tags can't be used to smuggle a banned word past the
     * filter (e.g. {@code ad<red>min} or {@code ad&cmin}).
     */
    public String plainChestName(String name) {
        return PLAIN.serialize(chestName(name));
    }

    /**
     * Resolves a chest's display label. Temporary chests get their own dedicated title
     * ({@code enderchest.title-temp}) rather than the numbered "Ender Chest N". Used for inventory
     * window titles and dialog buttons where a chest's kind should be visible.
     */
    public Component getChestLabel(int index, @org.jetbrains.annotations.Nullable String customName, ChestKind kind) {
        if (kind == ChestKind.TEMP) {
            return parse(cachedTitleTemp.replace("{index}", Integer.toString(index)));
        }
        return getChestTitle(index, customName);
    }

    /**
     * Resolves a GUI/dialog label from gui.yml (no prefix), substituting {placeholders}
     * and parsing the result. Used for the /ec list dialog labels.
     *
     * <p>Cached the same way as {@link #get}: a zero-replacement call (most dialog button labels/
     * tooltips/descriptions — the overwhelming majority of {@code gui.yml} keys) is parsed once and
     * reused, which matters here specifically because a dialog is rebuilt on essentially every
     * navigation click, each pulling a dozen-plus of these.
     */
    public Component getGui(String key, String... replacements) {
        if (replacements.length == 0) {
            return guiCache.computeIfAbsent(key, k -> parse(gui.getString(k, k)));
        }
        String raw = gui.getString(key, key);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            raw = raw.replace("{" + replacements[i] + "}", replacements[i + 1]);
        }
        return parse(raw);
    }

    private Component parse(String text) {
        return parse(text, isMiniMessage(text));
    }

    private Component parse(String text, boolean isMini) {
        if (isMini) {
            return MINI.deserialize(text);
        }
        return LEGACY.deserialize(text);
    }

    private static boolean isMiniMessage(String text) {
        if (text == null) {
            return false;
        }
        return MINI_TAG_PATTERN.matcher(text).find();
    }
}
