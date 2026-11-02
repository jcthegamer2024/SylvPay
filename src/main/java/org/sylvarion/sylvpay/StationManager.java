package org.sylvarion.sylvpay;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;

import java.io.File;
import java.io.IOException;
import java.util.*;

/** Station definitions and persistent numbered gate assignments. */
public final class StationManager {
    public record Gate(String station, String direction) {}
    public record RegisteredGate(int id, UUID uuid, Gate gate, String world, double x, double y, double z) {}

    private final SylvPayMain plugin;
    private final File stationsFile, gatesFile;
    private YamlConfiguration stations;
    private final Map<UUID, Gate> assignments = new HashMap<>();
    private final Map<UUID, Integer> numbers = new HashMap<>();
    private final Map<UUID, LocationInfo> locations = new HashMap<>();
    private int nextId = 1;

    private record LocationInfo(String world, double x, double y, double z) {
        static LocationInfo of(Location location) {
            return new LocationInfo(location.getWorld().getName(), location.getX(), location.getY(), location.getZ());
        }
    }

    public StationManager(SylvPayMain plugin) {
        this.plugin = plugin;
        stationsFile = new File(plugin.getDataFolder(), "stations.yml");
        gatesFile = new File(plugin.getDataFolder(), "gates.yml");
        if (!stationsFile.exists()) plugin.saveResource("stations.yml", false);
        reload();
    }

    public void reload() {
        stations = YamlConfiguration.loadConfiguration(stationsFile);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(gatesFile);
        assignments.clear();
        numbers.clear();
        locations.clear();
        nextId = Math.max(1, yaml.getInt("next-gate-id", 1));
        ConfigurationSection section = yaml.getConfigurationSection("gates");
        if (section == null) return;
        Set<Integer> used = new HashSet<>();
        Set<Integer> reserved = new HashSet<>();
        for (String key : section.getKeys(false)) {
            int existing = yaml.getInt("gates." + key + ".id", 0);
            if (existing > 0) reserved.add(existing);
        }
        boolean migration = false;
        List<String> keys = new ArrayList<>(section.getKeys(false));
        Collections.sort(keys);
        for (String key : keys) {
            try {
                UUID uuid = UUID.fromString(key);
                String path = "gates." + key;
                String station = yaml.getString(path + ".station", "");
                String direction = yaml.getString(path + ".direction", "");
                if (!hasStation(station) || !(direction.equals("entrance") || direction.equals("exit"))) {
                    plugin.getLogger().warning("Invalid gate assignment " + key + " (station or direction)");
                    continue;
                }
                int id = yaml.getInt(path + ".id", 0);
                if (id <= 0 || used.contains(id)) {
                    id = nextAvailable(reserved);
                    migration = true;
                }
                used.add(id);
                reserved.add(id);
                nextId = Math.max(nextId, id + 1);
                assignments.put(uuid, new Gate(station, direction));
                numbers.put(uuid, id);
                if (yaml.contains(path + ".location.world")) {
                    locations.put(uuid, new LocationInfo(
                            yaml.getString(path + ".location.world", ""),
                            yaml.getDouble(path + ".location.x"),
                            yaml.getDouble(path + ".location.y"),
                            yaml.getDouble(path + ".location.z")));
                } else {
                    Entity entity = Bukkit.getEntity(uuid);
                    if (entity != null) {
                        locations.put(uuid, LocationInfo.of(entity.getLocation()));
                        migration = true;
                    }
                }
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid gate UUID: " + key);
            }
        }
        if (migration || !yaml.contains("next-gate-id")) {
            try { save(); }
            catch (IOException ex) { plugin.getLogger().severe("Cannot migrate gates.yml: " + ex.getMessage()); }
        }
    }

    private int nextAvailable(Set<Integer> used) {
        while (used.contains(nextId)) nextId++;
        return nextId++;
    }

    public boolean hasStation(String id) { return id != null && stations.isConfigurationSection("stations." + id); }
    public String stationName(String id) { return stations.getString("stations." + id + ".name", id); }
    public Set<String> stationIds() {
        ConfigurationSection section = stations.getConfigurationSection("stations");
        return section == null ? Set.of() : new TreeSet<>(section.getKeys(false));
    }
    public Integer stationZone(String id) {
        if (!hasStation(id)) return null;
        String path = "stations." + id + ".zone";
        if (!stations.isInt(path)) return null;
        int zone = stations.getInt(path);
        return zone > 0 ? zone : null;
    }

    public Gate gate(ItemDisplay entity) { return assignments.get(entity.getUniqueId()); }
    public Integer gateNumber(ItemDisplay entity) { return numbers.get(entity.getUniqueId()); }

    /**
     * Rebind a replaced Nexo furniture entity to an old registration at the same
     * physical position. Only orphaned UUIDs qualify, and ambiguous matches are
     * rejected rather than assigning a gate to the wrong station/direction.
     * The original numerical ID is retained.
     */
    public boolean recoverAt(ItemDisplay replacement) throws IOException {
        UUID replacementUuid = replacement.getUniqueId();
        if (assignments.containsKey(replacementUuid)) return true;
        Location location = replacement.getLocation();
        UUID candidate = null;
        for (var entry : assignments.entrySet()) {
            UUID previous = entry.getKey();
            LocationInfo saved = locations.get(previous);
            if (saved == null || !saved.world().equals(location.getWorld().getName())) continue;
            double dx = saved.x() - location.getX();
            double dy = saved.y() - location.getY();
            double dz = saved.z() - location.getZ();
            if (dx * dx + dy * dy + dz * dz > 0.01) continue;
            // Never steal the registration of an existing live furniture entity.
            Entity old = Bukkit.getEntity(previous);
            if (old != null && old.isValid()) continue;
            if (candidate != null) {
                plugin.getLogger().warning("Ambiguous orphaned gates at " + location + "; refusing automatic recovery.");
                return false;
            }
            candidate = previous;
        }
        if (candidate == null) return false;
        int preservedId = numbers.get(candidate);
        transfer(candidate, replacementUuid);
        plugin.getLogger().info("Recovered gate #" + preservedId + " for replacement furniture " + replacementUuid);
        return true;
    }


    public List<RegisteredGate> registeredGates() {
        List<RegisteredGate> result = new ArrayList<>();
        for (var entry : assignments.entrySet()) {
            UUID uuid = entry.getKey();
            LocationInfo loc = locations.get(uuid);
            Entity entity = Bukkit.getEntity(uuid);
            if (entity != null) loc = LocationInfo.of(entity.getLocation());
            result.add(new RegisteredGate(numbers.get(uuid), uuid, entry.getValue(),
                    loc == null ? "unknown" : loc.world(),
                    loc == null ? 0 : loc.x(), loc == null ? 0 : loc.y(), loc == null ? 0 : loc.z()));
        }
        result.sort(Comparator.comparingInt(RegisteredGate::id));
        return result;
    }

    public void assign(ItemDisplay entity, String station, String direction) throws IOException {
        if (!hasStation(station) || !(direction.equals("entrance") || direction.equals("exit")))
            throw new IllegalArgumentException("Unknown station or direction");
        UUID uuid = entity.getUniqueId();
        Gate oldGate = assignments.put(uuid, new Gate(station, direction));
        Integer oldNumber = numbers.get(uuid);
        LocationInfo oldLocation = locations.put(uuid, LocationInfo.of(entity.getLocation()));
        if (oldNumber == null) numbers.put(uuid, nextId++);
        try { save(); }
        catch (IOException ex) {
            if (oldGate == null) assignments.remove(uuid); else assignments.put(uuid, oldGate);
            if (oldNumber == null) numbers.remove(uuid); else numbers.put(uuid, oldNumber);
            if (oldLocation == null) locations.remove(uuid); else locations.put(uuid, oldLocation);
            throw ex;
        }
    }

    public void unassign(ItemDisplay entity) throws IOException { removeUuid(entity.getUniqueId()); }
    public boolean delete(int id) throws IOException {
        for (var entry : numbers.entrySet()) {
            if (entry.getValue() == id) { removeUuid(entry.getKey()); return true; }
        }
        return false;
    }
    private void removeUuid(UUID uuid) throws IOException {
        Gate oldGate = assignments.remove(uuid);
        Integer oldId = numbers.remove(uuid);
        LocationInfo oldLocation = locations.remove(uuid);
        try { save(); }
        catch (IOException ex) {
            if (oldGate != null) assignments.put(uuid, oldGate);
            if (oldId != null) numbers.put(uuid, oldId);
            if (oldLocation != null) locations.put(uuid, oldLocation);
            throw ex;
        }
    }
    public void forget(ItemDisplay entity) {
        if (!assignments.containsKey(entity.getUniqueId())) return;
        try { removeUuid(entity.getUniqueId()); }
        catch (IOException ex) { plugin.getLogger().severe("Cannot save gates.yml: " + ex.getMessage()); }
    }

    /** Preserve the numerical ID and physical position when Nexo replaces a furniture entity. */
    public void transfer(UUID previous, UUID replacement) throws IOException {
        Gate assignment = assignments.get(previous);
        if (assignment == null) throw new IllegalArgumentException("Gate is not assigned: " + previous);
        if (previous.equals(replacement)) return;
        if (assignments.containsKey(replacement)) throw new IllegalArgumentException("Replacement already assigned: " + replacement);
        Integer id = numbers.get(previous);
        LocationInfo oldLocation = locations.get(previous);
        Entity replacementEntity = Bukkit.getEntity(replacement);
        LocationInfo newLocation = replacementEntity == null ? oldLocation : LocationInfo.of(replacementEntity.getLocation());
        assignments.remove(previous);
        numbers.remove(previous);
        locations.remove(previous);
        assignments.put(replacement, assignment);
        numbers.put(replacement, id);
        if (newLocation != null) locations.put(replacement, newLocation);
        try { save(); }
        catch (IOException ex) {
            assignments.remove(replacement);
            numbers.remove(replacement);
            locations.remove(replacement);
            assignments.put(previous, assignment);
            numbers.put(previous, id);
            if (oldLocation != null) locations.put(previous, oldLocation);
            throw ex;
        }
    }

    private void save() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("next-gate-id", nextId);
        for (var entry : assignments.entrySet()) {
            UUID uuid = entry.getKey();
            String path = "gates." + uuid;
            yaml.set(path + ".id", numbers.get(uuid));
            yaml.set(path + ".station", entry.getValue().station());
            yaml.set(path + ".direction", entry.getValue().direction());
            LocationInfo loc = locations.get(uuid);
            if (loc != null) {
                yaml.set(path + ".location.world", loc.world());
                yaml.set(path + ".location.x", loc.x());
                yaml.set(path + ".location.y", loc.y());
                yaml.set(path + ".location.z", loc.z());
            }
        }
        yaml.save(gatesFile);
    }
}
