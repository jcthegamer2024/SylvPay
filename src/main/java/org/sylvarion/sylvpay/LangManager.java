package org.sylvarion.sylvpay;

import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Configurable messages and display branding; internal IDs stay stable. */
public final class LangManager {
    private final SylvPayMain plugin;
    private final File file;
    private YamlConfiguration yaml;
    private static final Pattern BRAND = Pattern.compile("Tap2Go|SylvPay", Pattern.CASE_INSENSITIVE);

    public LangManager(SylvPayMain plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "lang.yml");
        reload();
    }

    public void reload() {
        yaml = YamlConfiguration.loadConfiguration(file);
        boolean save = !file.exists();
        // Preserve legacy custom messages, while explicit lang.yml values take priority.
        for (String section : new String[]{"messages", "faregates.messages", "validators.messages"}) {
            var legacy = plugin.getConfig().getConfigurationSection(section);
            if (legacy == null) continue;
            for (String key : legacy.getKeys(false)) {
                String path = section + "." + key;
                if (!yaml.isSet(path)) {
                    yaml.set(path, legacy.get(key));
                    save = true;
                }
            }
        }
        try (var stream = plugin.getResource("lang.yml")) {
            if (stream != null) yaml.setDefaults(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)));
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not load default lang.yml: " + ex.getMessage());
        }
        if (save) {
            yaml.options().copyDefaults(true);
            try { yaml.save(file); }
            catch (IOException ex) { plugin.getLogger().severe("Could not save lang.yml: " + ex.getMessage()); }
        }
    }

    public String message(String path, String fallback) { return yaml.getString(path, fallback); }

    public String brand(String source) {
        if (source == null || source.isEmpty()) return source;
        String pluginName = yaml.getString("branding.plugin", "SylvPay");
        String cardName = yaml.getString("branding.card", "Tap2Go");
        Matcher matcher = BRAND.matcher(source);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String matched = matcher.group();
            String replacement = matched.equalsIgnoreCase("Tap2Go") ? cardName : pluginName;
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
