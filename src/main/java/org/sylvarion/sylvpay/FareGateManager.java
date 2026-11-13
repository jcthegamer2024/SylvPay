package org.sylvarion.sylvpay;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.sql.SQLException;
import java.util.*;

/** Fare gates using Nexo furniture states on a persistent base entity. */
public final class FareGateManager implements Listener {
    private static final class OpenGate {
        ItemDisplay display;
        final UUID rider;
        final double approachX, approachZ;
        final double axisX, axisZ;
        boolean entered;
        OpenGate(ItemDisplay display, Player rider) {
            this.display = display;
            this.rider = rider.getUniqueId();
            Location centre = display.getLocation();
            Location start = rider.getLocation();
            this.approachX = start.getX() - centre.getX();
            this.approachZ = start.getZ() - centre.getZ();
            double length = Math.hypot(approachX, approachZ);
            // Direction from gate towards the tapping passenger.
            // This makes detection independent of the model's yaw convention.
            if (length < 0.25) {
                double radians = Math.toRadians(centre.getYaw());
                this.axisX = -Math.sin(radians);
                this.axisZ = Math.cos(radians);
            } else {
                this.axisX = approachX / length;
                this.axisZ = approachZ / length;
            }
        }
    }

    private final SylvPayMain plugin;
    private final StationManager stations;
    private final FareManager fares;
    private final NexoGateBridge nexo;
    private final Map<UUID, OpenGate> open = new HashMap<>();
    private final Map<UUID, Long> lastTap = new HashMap<>();
    private final MiniMessage mm = MiniMessage.miniMessage();

    public FareGateManager(SylvPayMain plugin, StationManager stations, FareManager fares, NexoGateBridge nexo) {
        this.plugin = plugin; this.stations = stations; this.fares = fares; this.nexo = nexo;
    }

    private String closedId() { return plugin.getConfig().getString("faregates.models.closed", "sylv_faregate_closed"); }
    private String openState() { return plugin.getConfig().getString("faregates.states.open", "open"); }
    private String closedState() { return plugin.getConfig().getString("faregates.states.closed", ""); }

    public boolean isManaged(ItemDisplay display) {
        return display != null && stations.gate(display) != null;
    }

    public boolean tap(Player player, ItemDisplay gate) {
        if (!plugin.getConfig().getBoolean("faregates.enabled", false)) {plugin.debug().log("GATE", "Tap ignored: fare gates disabled");return false;}
        StationManager.Gate assigned = stations.gate(gate);
        if (assigned == null) return false;
        plugin.debug().log("GATE", "Tap requested: player=%s gate=%s station=%s direction=%s",player.getName(),gate.getUniqueId(),assigned.station(),assigned.direction());
        String station = assigned.station();
        if (open.containsKey(gate.getUniqueId()) || !closedId().equals(nexo.furnitureId(gate))) {
            message(player, "busy", station, null, 0, 0);
            return true;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        String card = held.hasItemMeta() ? held.getItemMeta().getPersistentDataContainer()
                .get(plugin.cardKey(), PersistentDataType.STRING) : null;
        if (card == null) { message(player, "no-card", station, null, 0, 0); return true; }
        long now = System.currentTimeMillis();
        long cooldown = plugin.getConfig().getLong("faregates.tap-cooldown-ms", 1500);
        if (now - lastTap.getOrDefault(player.getUniqueId(), 0L) < cooldown) {plugin.debug().log("GATE", "Tap ignored: cooldown player=%s",player.getName());return true;}
        lastTap.put(player.getUniqueId(), now);
        if (openState().equals(closedState()) || !nexo.exists(closedId())
                || !nexo.hasState(gate, openState()) || !nexo.hasState(gate, closedState())) {
            message(player, "model-missing", station, null, 0, 0); return true;
        }
        if (!nexo.isState(gate, closedState())) {
            message(player, "busy", station, null, 0, 0); return true;
        }
        try {
            CardDatabase.TapResult result;
            if (assigned.direction().equals("entrance")) {
                result = plugin.db().tapIn(card, station, fares.minimumEntryBalance());
            } else {
                CardDatabase.Journey journey = plugin.db().activeJourney(card);
                if (journey == null) {
                    message(player, plugin.db().getBalance(card) == null ? "invalid-card" : "no-journey",
                            station, null, 0, 0);
                    return true;
                }
                if (!stations.hasStation(journey.origin())) {
                    message(player, "error", station, null, 0, 0); return true;
                }
                result = plugin.db().tapOut(card, station, fares.fare(journey.origin(), station),
                        fares.maxJourneyMillis());
            }
            plugin.debug().log("GATE", "Transaction result: player=%s card=%s status=%s fare=%.2f balance=%.2f",player.getName(),card,result.status(),result.fare(),result.balance());
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
            if (!nexo.setState(gate, openState())) {
                // A state failure can occur after the journey transaction has committed.
                // Track the entity so a partially applied open state is retried closed.
                UUID failedUuid = gate.getUniqueId();
                open.put(failedUuid, new OpenGate(gate, player));
                Bukkit.getScheduler().runTaskLater(plugin, () -> attemptClose(failedUuid), 20L);
                plugin.getLogger().severe("Gate failed to open AFTER card transaction for " + card +
                        ". Review the journey manually.");
                message(player, "error", station, null, 0, 0);
                return true;
            }
            ItemDisplay opened = gate;
            long timeout = Math.max(20L, plugin.getConfig().getLong("faregates.opening.timeout-ticks", 100));
            UUID openUuid = opened.getUniqueId();
            OpenGate state = new OpenGate(opened, player);
            open.put(openUuid, state);
            plugin.debug().log("GATE", "Gate opened: entity=%s player=%s timeoutTicks=%d",openUuid,player.getName(),timeout);
            Bukkit.getScheduler().runTaskLater(plugin, () -> attemptClose(openUuid), timeout);
            boolean entrance = assigned.direction().equals("entrance");
            message(player, entrance ? "tap-in" : "tap-out",
                    station, result.origin(), result.fare(), result.balance());
            playTapSound(player, entrance);
            return true;
        } catch (SQLException | IllegalArgumentException ex) {
            plugin.getLogger().severe("Fare gate transaction failed: " + ex);
            message(player, "error", station, null, 0, 0);
            return true;
        }
    }

    /**
     * Detect the tapping passenger travelling from their original side to the far
     * side of the gate. The timeout remains a fallback if they never cross.
     */
    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null || (event.getFrom().getX() == to.getX()
                && event.getFrom().getY() == to.getY()
                && event.getFrom().getZ() == to.getZ())) return;
        Player player = event.getPlayer();
        double clear = Math.max(0.65, plugin.getConfig().getDouble(
                "faregates.opening.clearance-radius", 0.85));
        double lane = Math.max(0.5, plugin.getConfig().getDouble(
                "faregates.opening.lane-half-width", 1.0));
        for (var entry : new HashMap<>(open).entrySet()) {
            OpenGate state = entry.getValue();
            if (!state.rider.equals(player.getUniqueId()) || !state.display.isValid()
                    || !state.display.getWorld().equals(to.getWorld())) continue;
            Location centre = state.display.getLocation();
            double dx = to.getX() - centre.getX();
            double dz = to.getZ() - centre.getZ();
            // Positive is the side from which the rider tapped.
            double longitudinal = dx * state.axisX + dz * state.axisZ;
            double lateral = Math.abs(-dx * state.axisZ + dz * state.axisX);
            if (lateral > lane) continue;
            if (Math.abs(longitudinal) < clear) state.entered = true;
            // Crossing must be observed, and rider must clear the barrier footprint.
            if (state.entered && longitudinal < -clear) {
                plugin.debug().log("GATE", "Rider cleared gate: player=%s entity=%s",player.getName(),entry.getKey());
                attemptClose(entry.getKey());
            }
        }
    }

    /** Only the actual barrier footprint, not the whole approach area, blocks closing. */
    private boolean occupied(ItemDisplay display) {
        double radius = Math.max(0.5, plugin.getConfig().getDouble(
                "faregates.opening.clearance-radius", 0.85));
        Location centre = display.getLocation();
        for (Player player : display.getWorld().getPlayers()) {
            Location position = player.getLocation();
            if (Math.abs(position.getY() - centre.getY()) > 2.0) continue;
            double dx = position.getX() - centre.getX();
            double dz = position.getZ() - centre.getZ();
            if (dx * dx + dz * dz <= radius * radius) return true;
        }
        return false;
    }

    private void attemptClose(UUID openUuid) {
        OpenGate state = open.get(openUuid);
        if (state == null) return;
        if (!state.display.isValid()) {plugin.debug().log("GATE", "Open gate entity invalid: %s",openUuid); open.remove(openUuid); return; }
        if (occupied(state.display)) {
            plugin.debug().log("GATE", "Close delayed: gate %s occupied",openUuid);
            Bukkit.getScheduler().runTaskLater(plugin, () -> attemptClose(openUuid), 20L);
            return;
        }
        if (!nexo.setState(state.display, closedState())) {
            plugin.getLogger().severe("Could not reset fare gate state at " + state.display.getLocation());
            Bukkit.getScheduler().runTaskLater(plugin, () -> attemptClose(openUuid), 40L);
            return;
        }
        open.remove(openUuid);
        plugin.debug().log("GATE", "Gate closed: %s",openUuid);
    }

    public void shutdown() {
        // Server is shutting down: best-effort restore open gates immediately.
        for (var entry : new HashMap<>(open).entrySet()) {
            OpenGate state = entry.getValue();
            if (!state.display.isValid()) continue;
            if (!nexo.setState(state.display, closedState()))
                plugin.getLogger().severe("Could not reset gate state during shutdown: " + entry.getKey());
        }
        open.clear();
    }

    private String formatMoney(double amount) {
        String symbol = plugin.getConfig().getString("currency.symbol", "£");
        String position = plugin.getConfig().getString("currency.position", "before");
        int decimals = Math.max(0, Math.min(8, plugin.getConfig().getInt("currency.decimals", 2)));
        boolean grouping = plugin.getConfig().getBoolean("currency.thousands-separator", true);
        String spacing = plugin.getConfig().getBoolean("currency.space", false) ? " " : "";

        DecimalFormat format = new DecimalFormat();
        format.setDecimalFormatSymbols(DecimalFormatSymbols.getInstance(Locale.UK));
        format.setGroupingUsed(grouping);
        format.setMinimumFractionDigits(decimals);
        format.setMaximumFractionDigits(decimals);
        String number = format.format(amount);

        return "after".equalsIgnoreCase(position)
                ? number + spacing + symbol
                : symbol + spacing + number;
    }

    private void playTapSound(Player player, boolean entrance) {
        String path = entrance ? "faregates.sounds.tap-in" : "faregates.sounds.tap-out";
        if (!plugin.getConfig().getBoolean(path + ".enabled", true)) return;

        String sound = plugin.getConfig().getString(path + ".sound", "minecraft:block.note_block.bell");
        if (sound == null || sound.isBlank()) return;
        float volume = (float) Math.max(0.0, plugin.getConfig().getDouble(path + ".volume", 0.8));
        float pitch = (float) Math.max(0.5, Math.min(2.0,
                plugin.getConfig().getDouble(path + ".pitch", entrance ? 1.4 : 1.0)));
        try {
            player.playSound(player.getLocation(), sound, SoundCategory.MASTER, volume, pitch);
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Invalid fare gate sound '" + sound + "' at " + path + ": " + ex.getMessage());
        }
    }

    private String zoneLabel(String station) {
        if (station == null || !stations.hasStation(station)) return "Unknown";
        Integer zone = stations.stationZone(station);
        return zone == null ? "Unknown" : Integer.toString(zone);
    }

    private void message(Player player, String key, String station, String origin, double fare, double balance) {
        plugin.debug().log("GATE", "Feedback: player=%s key=%s station=%s",player.getName(),key,station);
        String template = plugin.lang().message("faregates.messages." + key, "<red>Fare gate error.");
        player.sendMessage(mm.deserialize(plugin.lang().brand(template
                .replace("{station}", stations.stationName(station))
                .replace("{zone}", zoneLabel(station))
                .replace("{origin_zone}", zoneLabel(origin))
                .replace("{origin}", origin == null ? "" : stations.stationName(origin))
                .replace("{fare}", formatMoney(fare))
                .replace("{balance}", formatMoney(balance)))));
    }
}
