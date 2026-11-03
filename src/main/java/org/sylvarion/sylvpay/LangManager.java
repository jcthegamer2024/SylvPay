package org.sylvarion.sylvpay;

import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Display branding only. Internal plugin IDs, card IDs, commands and Nexo models stay stable. */
public final class LangManager {
    private final SylvPayMain plugin;
    private final File file;
    private YamlConfiguration yaml;
    private static final Pattern BRAND = Pattern.compile("Tap2Go|SylvPay", Pattern.CASE_INSENSITIVE);

    public LangManager(SylvPayMain plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "lang.yml");
        if (!file.exists()) {
            plugin.getDataFolder().mkdirs();
            YamlConfiguration defaults = new YamlConfiguration();
            defaults.set("branding.plugin", "SylvPay");
            defaults.set("branding.card", "Tap2Go");
            try { defaults.save(file); }
            catch (IOException ex) { plugin.getLogger().severe("Could not create lang.yml: " + ex.getMessage()); }
        }
        reload();
    }

    public void reload() { yaml = YamlConfiguration.loadConfiguration(file); }

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
