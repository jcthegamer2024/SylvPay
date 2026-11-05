package org.sylvarion.sylvpay;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.*;

/** Persistent Nexo validator assignments; shares CardDatabase journeys with fare gates. */
public final class ValidatorManager {
    public record RegisteredValidator(int id, UUID uuid, String station, String world, double x, double y, double z) {}
    private record Point(String world, double x, double y, double z) {
        static Point of(Location l) { return new Point(l.getWorld().getName(), l.getX(), l.getY(), l.getZ()); }
        boolean near(Location l) {
            if (!world.equals(l.getWorld().getName())) return false;
            double dx = x-l.getX(), dy = y-l.getY(), dz = z-l.getZ();
            return dx*dx+dy*dy+dz*dz <= 0.01;
        }
    }
    private final SylvPayMain plugin;
    private final File file;
    private final Map<UUID, String> assignments = new HashMap<>();
    private final Map<UUID, Integer> ids = new HashMap<>();
    private final Map<UUID, Point> points = new HashMap<>();
    private final Map<UUID, Long> lastTap = new HashMap<>();
    private int nextId = 1;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public ValidatorManager(SylvPayMain plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "validators.yml");
        reload();
    }
    public void reload() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        assignments.clear(); ids.clear(); points.clear();
        nextId = Math.max(1, yaml.getInt("next-validator-id", 1));
        ConfigurationSection section = yaml.getConfigurationSection("validators");
        if (section == null) return;
        Set<Integer> used = new HashSet<>();
        for (String key : section.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                String path = "validators."+key;
                String station = yaml.getString(path+".station", "");
                if (!plugin.stations().hasStation(station)) { plugin.getLogger().warning("Ignoring validator at unknown station: "+station); continue; }
                int id = yaml.getInt(path+".id", 0);
                if (id < 1 || !used.add(id)) { while (used.contains(nextId)) nextId++; id = nextId++; used.add(id); }
                nextId = Math.max(nextId, id+1);
                assignments.put(uuid, station); ids.put(uuid, id);
                if (yaml.contains(path+".location.world")) points.put(uuid, new Point(
                    yaml.getString(path+".location.world", ""), yaml.getDouble(path+".location.x"),
                    yaml.getDouble(path+".location.y"), yaml.getDouble(path+".location.z")));
            } catch (IllegalArgumentException ex) { plugin.getLogger().warning("Invalid validator UUID: "+key); }
        }
    }
    public String station(ItemDisplay display) { return display == null ? null : assignments.get(display.getUniqueId()); }
    public Integer number(ItemDisplay display) { return display == null ? null : ids.get(display.getUniqueId()); }
    public void assign(ItemDisplay display, String station) throws IOException {
        if (!plugin.stations().hasStation(station)) throw new IllegalArgumentException("Unknown station");
        UUID uuid = display.getUniqueId();
        String old = assignments.put(uuid, station);
        Integer oldId = ids.get(uuid);
        Point oldPoint = points.put(uuid, Point.of(display.getLocation()));
        if (oldId == null) ids.put(uuid, nextId++);
        try { save(); } catch (IOException ex) {
            if (old == null) assignments.remove(uuid); else assignments.put(uuid, old);
            if (oldId == null) ids.remove(uuid); else ids.put(uuid, oldId);
            if (oldPoint == null) points.remove(uuid); else points.put(uuid, oldPoint);
            throw ex;
        }
    }
    public boolean remove(ItemDisplay display) throws IOException { return removeUuid(display.getUniqueId()); }
    public boolean delete(int id) throws IOException {
        for (var e : new HashMap<>(ids).entrySet()) if (e.getValue() == id) return removeUuid(e.getKey());
        return false;
    }
    private boolean removeUuid(UUID uuid) throws IOException {
        if (!assignments.containsKey(uuid)) return false;
        String station = assignments.remove(uuid);
        Integer id = ids.remove(uuid);
        Point point = points.remove(uuid);
        try { save(); return true; } catch (IOException ex) {
            assignments.put(uuid, station); ids.put(uuid, id);
            if (point != null) points.put(uuid, point);
            throw ex;
        }
    }
    /** Recover Nexo furniture replaced at the same coordinates, but never steal live assignments. */
    public boolean recoverAt(ItemDisplay display) throws IOException {
        if (station(display) != null) return true;
        UUID candidate = null;
        for (var e : assignments.entrySet()) {
            Point point = points.get(e.getKey());
            if (point == null || !point.near(display.getLocation())) continue;
            Entity old = Bukkit.getEntity(e.getKey());
            if (old != null && old.isValid()) continue;
            if (candidate != null) { plugin.getLogger().warning("Ambiguous validator recovery at "+display.getLocation()); return false; }
            candidate = e.getKey();
        }
        if (candidate == null) return false;
        UUID current = display.getUniqueId();
        String assigned = assignments.remove(candidate);
        Integer id = ids.remove(candidate);
        Point point = points.remove(candidate);
        assignments.put(current, assigned); ids.put(current, id); points.put(current, Point.of(display.getLocation()));
        try { save(); return true; } catch (IOException ex) {
            assignments.remove(current); ids.remove(current); points.remove(current);
            assignments.put(candidate, assigned); ids.put(candidate, id); points.put(candidate, point);
            throw ex;
        }
    }
    public List<RegisteredValidator> registered() {
        List<RegisteredValidator> result = new ArrayList<>();
        for (var e : assignments.entrySet()) {
            Point p = points.get(e.getKey());
            Entity entity = Bukkit.getEntity(e.getKey());
            if (entity != null && entity.isValid()) p = Point.of(entity.getLocation());
            result.add(new RegisteredValidator(ids.get(e.getKey()), e.getKey(), e.getValue(),
                p == null ? "unknown" : p.world(), p == null ? 0 : p.x(), p == null ? 0 : p.y(), p == null ? 0 : p.z()));
        }
        result.sort(Comparator.comparingInt(RegisteredValidator::id));
        return result;
    }
    private void save() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("next-validator-id", nextId);
        for (var e : assignments.entrySet()) {
            UUID uuid = e.getKey(); String path = "validators."+uuid;
            yaml.set(path+".id", ids.get(uuid)); yaml.set(path+".station", e.getValue());
            Point p = points.get(uuid);
            if (p != null) {
                yaml.set(path+".location.world", p.world()); yaml.set(path+".location.x", p.x());
                yaml.set(path+".location.y", p.y()); yaml.set(path+".location.z", p.z());
            }
        }
        yaml.save(file);
    }
    private String money(double amount) {
        var cfg = plugin.getConfig();
        int decimals = Math.max(0, Math.min(2, cfg.getInt("currency.decimals", 2)));
        DecimalFormat format = new DecimalFormat();
        format.setDecimalFormatSymbols(DecimalFormatSymbols.getInstance(Locale.UK));
        format.setGroupingUsed(cfg.getBoolean("currency.thousands-separator", true));
        format.setMinimumFractionDigits(decimals); format.setMaximumFractionDigits(decimals);
        String symbol = cfg.getString("currency.symbol", "£");
        String gap = cfg.getBoolean("currency.space", false) ? " " : "";
        return cfg.getString("currency.position", "before").equalsIgnoreCase("after") ? format.format(amount)+gap+symbol : symbol+gap+format.format(amount);
    }
    private void message(Player player, String key, String station, CardDatabase.TapResult result) {
        String content = plugin.lang().message("validators.messages."+key, "<red>Validator: "+key);
        content = content.replace("{station}", plugin.stations().stationName(station))
            .replace("{fare}", money(result == null ? 0 : result.fare()))
            .replace("{balance}", money(result == null ? 0 : result.balance()));
        player.sendMessage(mm.deserialize(plugin.lang().brand(content)));
    }
    private void sound(Player player, String kind) {
        String path = "validators.sounds."+kind;
        if (!plugin.getConfig().getBoolean(path+".enabled", true)) return;
        String key = plugin.getConfig().getString(path+".sound", "minecraft:block.note_block.bell");
        try { player.playSound(player.getLocation(), key, SoundCategory.PLAYERS,
            (float)plugin.getConfig().getDouble(path+".volume", 0.8),
            (float)plugin.getConfig().getDouble(path+".pitch", kind.equals("tap-in") ? 1.4 : 1.0)); }
        catch (IllegalArgumentException ex) { plugin.getLogger().warning("Invalid validator sound: "+key); }
    }
    public boolean tap(Player player, ItemDisplay display) {
        if (!plugin.getConfig().getBoolean("validators.enabled", true)) return false;
        String station = station(display);
        if (station == null) return false;
        if (!plugin.stations().hasStation(station)) { message(player, "error", station, null); return true; }
        ItemStack held = player.getInventory().getItemInMainHand();
        String card = held.hasItemMeta() ? held.getItemMeta().getPersistentDataContainer().get(plugin.cardKey(), PersistentDataType.STRING) : null;
        if (card == null) { message(player, "no-card", station, null); return true; }
        long now = System.currentTimeMillis();
        long cooldown = Math.max(0, plugin.getConfig().getLong("validators.tap-cooldown-ms", 1500));
        if (now-lastTap.getOrDefault(player.getUniqueId(), 0L) < cooldown) return true;
        lastTap.put(player.getUniqueId(), now);
        try {
            CardDatabase.Journey journey = plugin.db().activeJourney(card);
            boolean entering = journey == null;
            CardDatabase.TapResult result;
            if (entering) result = plugin.db().tapIn(card, station, plugin.fares().minimumEntryBalance());
            else {
                if (!plugin.stations().hasStation(journey.origin())) { message(player, "error", station, null); return true; }
                result = plugin.db().tapOut(card, station, plugin.fares().fare(journey.origin(), station), plugin.fares().maxJourneyMillis());
            }
            if (result.status() != CardDatabase.TapStatus.OK) {
                String key = switch (result.status()) {
                    case INVALID_CARD -> "invalid-card";
                    case ALREADY_IN -> "already-in";
                    case NO_JOURNEY -> "no-journey";
                    case INSUFFICIENT -> "insufficient-balance";
                    case EXPIRED -> "journey-expired";
                    default -> "error";
                };
                message(player, key, station, result); return true;
            }
            message(player, entering ? "tap-in" : "tap-out", station, result);
            sound(player, entering ? "tap-in" : "tap-out");
            plugin.debug().log("VALIDATOR", "Tap %s at %s by %s", entering ? "in" : "out", station, player.getName());
        } catch (SQLException | IllegalArgumentException ex) {
            plugin.getLogger().severe("Validator transaction failed: "+ex);
            message(player, "error", station, null);
        }
        return true;
    }
}
