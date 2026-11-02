
package org.sylvarion.sylvpay;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.sql.SQLException;
import java.util.*;

public final class FareGateManager implements Listener {

    private record OpenGate(
            ItemDisplay entity,
            ItemStack original,
            UUID rider,
            boolean near
    ) {}

    private final SylvPayMain plugin;
    private final StationManager stations;
    private final FareManager fares;
    private final NexoGateBridge nexo;

    private final Map<UUID, OpenGate> open = new HashMap<>();
    private final Map<UUID, Long> lastTap = new HashMap<>();

    private final MiniMessage mm = MiniMessage.miniMessage();

    public FareGateManager(
            SylvPayMain plugin,
            StationManager stations,
            FareManager fares,
            NexoGateBridge nexo
    ) {
        this.plugin = plugin;
        this.stations = stations;
        this.fares = fares;
        this.nexo = nexo;
    }

    public boolean tap(Player player, ItemDisplay gate) {

        if (!plugin.getConfig().getBoolean("faregates.enabled", false)) {
            return false;
        }

        StationManager.Gate assigned = stations.gate(gate);

        if (assigned == null) {
            return false;
        }

        String station = assigned.station();
        UUID gateId = gate.getUniqueId();

        if (open.containsKey(gateId)) {
            message(player, "busy", station, null, 0, 0);
            return true;
        }

        ItemStack held = player.getInventory().getItemInMainHand();

        String card = held.hasItemMeta()
                ? held.getItemMeta().getPersistentDataContainer()
                .get(plugin.cardKey(), PersistentDataType.STRING)
                : null;

        if (card == null) {
            message(player, "no-card", station, null, 0, 0);
            return true;
        }

        long now = System.currentTimeMillis();

        long cooldown = plugin.getConfig().getLong(
                "faregates.tap-cooldown-ms",
                1500
        );

        if (now - lastTap.getOrDefault(player.getUniqueId(), 0L) < cooldown) {
            return true;
        }

        lastTap.put(player.getUniqueId(), now);

        String openId = plugin.getConfig().getString(
                "faregates.models.open",
                ""
        );

        String closedId = plugin.getConfig().getString(
                "faregates.models.closed",
                ""
        );

        ItemStack openVisual = nexo.item(openId);
        ItemStack closedVisual = nexo.item(closedId);

        if (openVisual == null || closedVisual == null) {
            message(player, "model-missing", station, null, 0, 0);
            return true;
        }

        try {

            CardDatabase.TapResult result;

            if (assigned.direction().equals("entrance")) {

                result = plugin.db().tapIn(
                        card,
                        station,
                        fares.minimumEntryBalance()
                );

            } else {

                CardDatabase.Journey journey =
                        plugin.db().activeJourney(card);

                if (journey == null) {

                    if (plugin.db().getBalance(card) == null) {
                        message(player, "invalid-card", station, null, 0, 0);
                    } else {
                        message(player, "no-journey", station, null, 0, 0);
                    }

                    return true;
                }

                if (!stations.hasStation(journey.origin())) {
                    message(player, "error", station, null, 0, 0);
                    return true;
                }

                double fare = fares.fare(journey.origin(), station);

                result = plugin.db().tapOut(
                        card,
                        station,
                        fare,
                        fares.maxJourneyMillis()
                );
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

                message(
                        player,
                        key,
                        station,
                        result.origin(),
                        result.fare(),
                        result.balance()
                );

                return true;
            }

            // Save the original display item as a fallback.
            ItemStack original = gate.getItemStack().clone();

            // Change to the open Nexo model.
            gate.setItemStack(openVisual.clone());

            open.put(
                    gateId,
                    new OpenGate(
                            gate,
                            original,
                            player.getUniqueId(),
                            false
                    )
            );

            long ticks = Math.max(
                    20L,
                    plugin.getConfig().getLong(
                            "faregates.opening.timeout-ticks",
                            100
                    )
            );

            Bukkit.getScheduler().runTaskLater(
                    plugin,
                    () -> close(gateId),
                    ticks
            );

            message(
                    player,
                    assigned.direction().equals("entrance")
                            ? "tap-in"
                            : "tap-out",
                    station,
                    result.origin(),
                    result.fare(),
                    result.balance()
            );

            return true;

        } catch (SQLException | IllegalArgumentException ex) {

            plugin.getLogger().severe(
                    "Fare gate transaction failed: " + ex.getMessage()
            );

            message(player, "error", station, null, 0, 0);
            return true;
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {

        if (event.getTo() == null) {
            return;
        }

        if (event.getFrom().getX() == event.getTo().getX()
                && event.getFrom().getY() == event.getTo().getY()
                && event.getFrom().getZ() == event.getTo().getZ()) {
            return;
        }

        Player player = event.getPlayer();

        double radius = Math.max(
                0.5,
                plugin.getConfig().getDouble(
                        "faregates.opening.crossing-radius",
                        1.5
                )
        );

        for (var entry : new HashMap<>(open).entrySet()) {

            OpenGate state = entry.getValue();

            if (!state.rider().equals(player.getUniqueId())) {
                continue;
            }

            if (!state.entity().isValid()) {
                continue;
            }

            if (!state.entity().getWorld().equals(player.getWorld())) {
                continue;
            }

            boolean near =
                    state.entity().getLocation()
                            .distanceSquared(player.getLocation())
                            <= radius * radius;

            if (near && !state.near()) {

                open.put(
                        entry.getKey(),
                        new OpenGate(
                                state.entity(),
                                state.original(),
                                state.rider(),
                                true
                        )
                );

            } else if (!near && state.near()) {

                close(entry.getKey());
            }
        }
    }

    public void close(UUID id) {

        OpenGate state = open.remove(id);

        if (state == null) {
            return;
        }

        ItemDisplay entity = state.entity();

        if (!entity.isValid()) {
            return;
        }

        // Rebuild the CLOSED model from its Nexo ID.
        String closedId = plugin.getConfig().getString(
                "faregates.models.closed",
                ""
        );

        ItemStack closedVisual = nexo.item(closedId);

        if (closedVisual != null) {

            entity.setItemStack(closedVisual.clone());

            plugin.getLogger().info(
                    "Fare gate " + id + " restored to Nexo model: " + closedId
            );

        } else {

            // Fallback to the original display item.
            entity.setItemStack(state.original().clone());

            plugin.getLogger().warning(
                    "Could not rebuild closed Nexo model '" + closedId
                            + "'. Restored original display item."
            );
        }
    }

    public void shutdown() {

        for (UUID id : new ArrayList<>(open.keySet())) {
            close(id);
        }
    }

    private void message(
            Player player,
            String key,
            String station,
            String origin,
            double fare,
            double balance
    ) {

        String template = plugin.getConfig().getString(
                "faregates.messages." + key,
                "<red>Fare gate error."
        );

        String formatted = String.format(
                Locale.UK,
                "£%.2f",
                fare
        );

        String money = String.format(
                Locale.UK,
                "£%.2f",
                balance
        );

        player.sendMessage(
                mm.deserialize(
                        template
                                .replace(
                                        "{station}",
                                        stations.stationName(station)
                                )
                                .replace(
                                        "{origin}",
                                        origin == null
                                                ? ""
                                                : stations.stationName(origin)
                                )
                                .replace("{fare}", formatted)
                                .replace("{balance}", money)
                )
        );
    }
}
