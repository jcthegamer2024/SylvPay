package org.sylvarion.sylvpay;

import java.util.Locale;
import java.util.logging.Level;

/** Runtime-toggleable diagnostic logging. No passwords, full inventory contents or SQL connection strings. */
public final class DebugManager {
    private final SylvPayMain plugin;
    private volatile boolean enabled;

    public DebugManager(SylvPayMain plugin) {
        this.plugin = plugin;
        reload();
    }

    public boolean enabled() { return enabled; }
    public void reload() { enabled = plugin.getConfig().getBoolean("debug.enabled", false); }

    public void setEnabled(boolean value) {
        enabled = value;
        plugin.getConfig().set("debug.enabled", value);
        plugin.saveConfig();
        plugin.getLogger().info("Diagnostic debug logging " + (value ? "ENABLED" : "DISABLED") + ".");
    }

    public void log(String category, String format, Object... args) {
        if (!enabled) return;
        try {
            plugin.getLogger().info("[DEBUG] [" + category.toUpperCase(Locale.ROOT) + "] " +
                    String.format(Locale.ROOT, format, args));
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING, "Debug formatting failed in " + category, ex);
        }
    }
}
