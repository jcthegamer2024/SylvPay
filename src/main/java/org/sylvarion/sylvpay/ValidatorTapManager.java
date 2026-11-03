package org.sylvarion.sylvpay;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.SoundCategory;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import java.sql.SQLException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.*;

/** Stateless validator tap-in/out: uses the exact same CardDatabase journey and FareManager as fare gates. */
public final class ValidatorTapManager {
    private final SylvPayMain plugin;
    private final ValidatorManager validators;
    private final FareManager fares;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final MiniMessage mm = MiniMessage.miniMessage();
    public ValidatorTapManager(SylvPayMain plugin, ValidatorManager validators, FareManager fares) {
        this.plugin=plugin; this.validators=validators; this.fares=fares;
    }
    public boolean tap(Player player, ItemDisplay display) {
        if (!plugin.getConfig().getBoolean("validators.enabled", true)) return false;
        String station = validators.station(display);
        if (station == null) return false;
        ItemStack held = player.getInventory().getItemInMainHand();
        String card = held.hasItemMeta() ? held.getItemMeta().getPersistentDataContainer().get(plugin.cardKey(), PersistentDataType.STRING) : null;
        if (card == null) { message(player, "no-card", station, null, 0, 0); return true; }
        long now = System.currentTimeMillis();
        long cooldown = Math.max(0, plugin.getConfig().getLong("validators.tap-cooldown-ms", 1500));
        if (now - cooldowns.getOrDefault(player.getUniqueId(), 0L) < cooldown) return true;
        cooldowns.put(player.getUniqueId(), now);
        try {
            CardDatabase.Journey journey = plugin.db().activeJourney(card);
            if (journey != null && !plugin.stations().hasStation(journey.origin())) {
                message(player, "error", station, null, 0, 0); return true;
            }
            CardDatabase.TapResult result = journey == null
                    ? plugin.db().tapIn(card, station, fares.minimumEntryBalance())
                    : plugin.db().tapOut(card, station, fares.fare(journey.origin(), station), fares.maxJourneyMillis());
            if (result.status() != CardDatabase.TapStatus.OK) {
                String key = switch (result.status()) {
                    case INVALID_CARD -> "invalid-card";
                    case ALREADY_IN -> "already-in";
                    case NO_JOURNEY -> "no-journey";
                    case INSUFFICIENT -> "insufficient-balance";
                    case EXPIRED -> "journey-expired";
                    default -> "error";
                };
                message(player, key, station, result.origin(), result.fare(), result.balance());
                return true;
            }
            boolean entering = journey == null;
            message(player, entering ? "tap-in" : "tap-out", station, result.origin(), result.fare(), result.balance());
            sound(player, entering);
            plugin.debug().log("VALIDATOR", "Tap %s at %s by %s fare=%.2f", entering ? "IN" : "OUT", station, player.getName(), result.fare());
        } catch (SQLException | IllegalArgumentException ex) {
            plugin.getLogger().severe("Validator transaction failed: " + ex);
            message(player, "error", station, null, 0, 0);
        }
        return true;
    }
    private String money(double amount) {
        int digits = Math.max(0, Math.min(8, plugin.getConfig().getInt("currency.decimals", 2)));
        DecimalFormat df = new DecimalFormat();
        df.setDecimalFormatSymbols(DecimalFormatSymbols.getInstance(Locale.UK));
        df.setGroupingUsed(plugin.getConfig().getBoolean("currency.thousands-separator", true));
        df.setMinimumFractionDigits(digits); df.setMaximumFractionDigits(digits);
        String symbol=plugin.getConfig().getString("currency.symbol", "£");
        String space=plugin.getConfig().getBoolean("currency.space", false) ? " " : "";
        return plugin.getConfig().getString("currency.position", "before").equalsIgnoreCase("after")
                ? df.format(amount)+space+symbol : symbol+space+df.format(amount);
    }
    private String zone(String station) {
        Integer zone = station == null ? null : plugin.stations().stationZone(station);
        return zone == null ? "Unknown" : zone.toString();
    }
    private void message(Player player, String key, String station, String origin, double fare, double balance) {
        String template=plugin.getConfig().getString("validators.messages."+key, "<red>Validator error.");
        player.sendMessage(mm.deserialize(plugin.lang().brand(template
                .replace("{station}", plugin.stations().stationName(station))
                .replace("{origin}", origin == null ? "" : plugin.stations().stationName(origin))
                .replace("{zone}", zone(station)).replace("{origin_zone}", zone(origin))
                .replace("{fare}", money(fare)).replace("{balance}", money(balance)))));
    }
    private void sound(Player player, boolean entering) {
        String path = "validators.sounds." + (entering ? "tap-in" : "tap-out");
        if (!plugin.getConfig().getBoolean(path + ".enabled", true)) return;
        String sound = plugin.getConfig().getString(path + ".sound", "minecraft:block.note_block.bell");
        if (sound == null || sound.isBlank()) return;
        float volume = (float)Math.max(0, plugin.getConfig().getDouble(path + ".volume", 0.8));
        float pitch = (float)Math.max(0.5, Math.min(2, plugin.getConfig().getDouble(path + ".pitch", entering ? 1.4 : 1.0)));
        try { player.playSound(player.getLocation(), sound, SoundCategory.MASTER, volume, pitch); }
        catch (IllegalArgumentException ex) { plugin.getLogger().warning("Invalid validator sound: " + sound); }
    }
}
