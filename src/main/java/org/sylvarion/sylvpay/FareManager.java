package org.sylvarion.sylvpay;

import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;

/** Fares in GBP units, with directional routes and optional reverse-route fallback. */
public final class FareManager {
    private final SylvPayMain plugin;
    private final File file;
    private YamlConfiguration fares;
    public FareManager(SylvPayMain plugin) {
        this.plugin = plugin;
        file = new File(plugin.getDataFolder(), "fares.yml");
        if (!file.exists()) plugin.saveResource("fares.yml", false);
        reload();
    }
    public void reload() { fares = YamlConfiguration.loadConfiguration(file); plugin.debug().log("FARES", "Loaded fare rules from fares.yml"); }
    public double fare(String from, String to) {
        String path = "fares.routes." + from + "." + to;
        double amount;
        if (from.equals(to)) amount = fares.getDouble("fares.same-station-fare", 0);
        else if (fares.contains(path)) amount = fares.getDouble(path);
        else if (fares.getBoolean("fares.reverse-routes", true)
                && fares.contains("fares.routes." + to + "." + from))
            amount = fares.getDouble("fares.routes." + to + "." + from);
        else amount = fares.getDouble("fares.default-fare", 3);
        if (!Double.isFinite(amount) || amount < 0) throw new IllegalArgumentException("Invalid fare " + from + " -> " + to);
        double result = Math.round(amount * 100.0) / 100.0;
        plugin.debug().log("FARES", "Calculated fare %s -> %s: %.2f",from,to,result);
        return result;
    }
    public long maxJourneyMillis() {
        return Math.max(1, fares.getLong("fares.maximum-journey-minutes", 180)) * 60_000L;
    }
    public double minimumEntryBalance() {
        return Math.max(0, fares.getDouble("fares.minimum-entry-balance", 0));
    }
}
