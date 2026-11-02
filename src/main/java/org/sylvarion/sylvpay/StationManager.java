package org.sylvarion.sylvpay;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ItemDisplay;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Station definitions and persistent assignments of individual placed furniture entities. */
public final class StationManager {
    public record Gate(String station, String direction) {}
    private final SylvPayMain plugin;
    private final File stationsFile, gatesFile;
    private YamlConfiguration stations;
    private final Map<UUID, Gate> assignments = new HashMap<>();

    public StationManager(SylvPayMain plugin) {
        this.plugin = plugin;
        stationsFile = new File(plugin.getDataFolder(), "stations.yml");
        gatesFile = new File(plugin.getDataFolder(), "gates.yml");
        if (!stationsFile.exists()) plugin.saveResource("stations.yml", false);
        reload();
    }

    public void reload() {
        stations = YamlConfiguration.loadConfiguration(stationsFile);
        YamlConfiguration gates = YamlConfiguration.loadConfiguration(gatesFile);
        assignments.clear();
        ConfigurationSection section = gates.getConfigurationSection("gates");
        if (section == null) return;
        for (String id : section.getKeys(false)) {
            try {
                String path = "gates." + id;
                String station = gates.getString(path + ".station", "");
                String direction = gates.getString(path + ".direction", "");
                if (hasStation(station) && (direction.equals("entrance") || direction.equals("exit")))
                    assignments.put(UUID.fromString(id), new Gate(station, direction));
                else plugin.getLogger().warning("Invalid gate assignment " + id + " (station or direction)");
            } catch (IllegalArgumentException e) { plugin.getLogger().warning("Invalid gate UUID: " + id); }
        }
    }

    public boolean hasStation(String id) { return stations.isConfigurationSection("stations." + id); }
    public String stationName(String id) { return stations.getString("stations." + id + ".name", id); }
    public Gate gate(ItemDisplay entity) { return assignments.get(entity.getUniqueId()); }
    public void assign(ItemDisplay entity, String station, String direction) throws IOException {
        if (!hasStation(station) || !(direction.equals("entrance") || direction.equals("exit")))
            throw new IllegalArgumentException("Unknown station or direction");
        assignments.put(entity.getUniqueId(), new Gate(station, direction));
        save();
    }
    public void unassign(ItemDisplay entity) throws IOException { assignments.remove(entity.getUniqueId()); save(); }
    public void forget(ItemDisplay entity) {
        if (assignments.remove(entity.getUniqueId()) != null) {
            try { save(); } catch (IOException ex) { plugin.getLogger().severe("Cannot save gates.yml: " + ex.getMessage()); }
        }
    }
    private void save() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        for (var e : assignments.entrySet()) {
            String p = "gates." + e.getKey();
            yaml.set(p + ".station", e.getValue().station());
            yaml.set(p + ".direction", e.getValue().direction());
        }
        yaml.save(gatesFile);
    }
}
