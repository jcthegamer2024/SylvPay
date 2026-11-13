package org.sylvarion.sylvpay;

import com.nexomc.nexo.api.NexoFurniture;
import com.nexomc.nexo.api.events.furniture.NexoFurnitureInteractEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.EquipmentSlot;
import java.io.IOException;
import com.nexomc.nexo.mechanics.furniture.states.FurnitureStates;
import com.nexomc.nexo.mechanics.furniture.FurnitureMechanic;
import org.bukkit.persistence.PersistentDataType;

/** All Nexo integration uses its published public Java API. */
public final class NexoGateBridge implements Listener {
    private final SylvPayMain plugin;
    private FareGateManager gates;

    public NexoGateBridge(SylvPayMain plugin) { this.plugin = plugin; }

    public boolean register(FareGateManager manager) {
        if (!Bukkit.getPluginManager().isPluginEnabled("Nexo")) {plugin.debug().log("NEXO", "Nexo unavailable; bridge not registered");return false;}
        gates = manager;
        plugin.debug().log("NEXO", "Registered Nexo furniture listener");
        Bukkit.getPluginManager().registerEvents(this, plugin);
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(NexoFurnitureInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        ItemDisplay display = event.getBaseEntity();
        if (display == null || !display.isValid()) return;

        // Ticket machines work anywhere, with no per-instance registration.
        // Handle them before fare-gate recovery so the two systems stay independent.
        String machineId = plugin.getConfig().getString("ticket-machine.furniture-id", "").trim();
        if (!machineId.isEmpty() && machineId.equals(furnitureId(display))) {
            event.setCancelled(true);
            Player player = event.getPlayer();
            plugin.debug().log("MACHINE", "Ticket machine clicked: player=%s entity=%s furniture=%s",
                    player.getName(), display.getUniqueId(), machineId);
            // Open after the Nexo interaction event has finished.
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && display.isValid()) plugin.openTicketMachine(player);
            });
            return;
        }

        // Validators are stationary tap points: no barrier or gate direction.
        String validatorId = plugin.getConfig().getString("validators.furniture-id", "sylv_validator").trim();
        if (plugin.getConfig().getBoolean("validators.enabled", true)
                && !validatorId.isEmpty() && validatorId.equals(furnitureId(display))) {
            boolean assigned = plugin.validators().station(display) != null;
            if (!assigned) {
                try { assigned = plugin.validators().recoverAt(display); }
                catch (IOException ex) { plugin.getLogger().severe("Cannot recover validator: " + ex.getMessage()); return; }
            }
            if (!assigned) return;
            event.setCancelled(true);
            Player player = event.getPlayer();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && display.isValid()) plugin.validators().tap(player, display);
            });
            return;
        }

        if (!gates.isManaged(display)) {
            // Only consider the configured closed gate furniture, never arbitrary Nexo items.
            String closed = plugin.getConfig().getString("faregates.models.closed", "sylv_faregate_closed");
            if (!closed.equals(furnitureId(display))) return;
            try {
                if (!plugin.stations().recoverAt(display)) return;
            } catch (IOException ex) {
                plugin.getLogger().severe("Could not recover replaced fare gate: " + ex.getMessage());
                return;
            }
        }
        plugin.debug().log("NEXO", "Furniture interaction: entity=%s player=%s",display.getUniqueId(),event.getPlayer().getName());
        event.setCancelled(true);
        Player player = event.getPlayer();
        // Don't mutate Nexo furniture from inside its own interaction event.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && display.isValid()) gates.tap(player, display);
        });
    }

    public ItemDisplay target(Player player) {
        ItemDisplay display = NexoFurniture.findTargetFurniture(player);
        if (display == null || !display.isValid()) return null;
        if (!display.getWorld().equals(player.getWorld()) ||
                display.getLocation().distanceSquared(player.getEyeLocation()) > 36) return null;
        return display;
    }

    public String furnitureId(ItemDisplay display) {
        if (display == null || !display.isValid()) return null;
        // FURNITURE states can change the active mechanic. Keep resolving the original definition.
        String baseId = display.getPersistentDataContainer().get(
                FurnitureMechanic.Companion.getFURNITURE_KEY(), PersistentDataType.STRING);
        if (baseId != null) return baseId;
        var mechanic = NexoFurniture.furnitureMechanic(display);
        return mechanic == null ? null : mechanic.getItemID();
    }

    public boolean exists(String id) {
        return id != null && !id.isBlank() && NexoFurniture.isFurniture(id);
    }

    /** Both configured states must exist before any card transaction is committed. */
    public boolean hasState(ItemDisplay display, String id) {
        var mechanic = NexoFurniture.furnitureMechanic(furnitureId(display));
        return mechanic != null && mechanic.getStates() != null
                && mechanic.getStates().getStates().containsKey(id);
    }

    public boolean isState(ItemDisplay display, String id) {
        var mechanic = NexoFurniture.furnitureMechanic(furnitureId(display));
        return mechanic != null && mechanic.getStates() != null
                && id.equals(mechanic.getStates().currentState(display).getId());
    }

    /** Apply an explicit state using the public state/key API, without replacing the entity.
     * Nexo's transition order is: store target ID, unset previous state, set target state.
     */
    public boolean setState(ItemDisplay display, String id) {
        if (display == null || !display.isValid()) return false;
        try {
            var mechanic = NexoFurniture.furnitureMechanic(furnitureId(display));
            if (mechanic == null || mechanic.getStates() == null) return false;
            var states = mechanic.getStates();
            var next = states.getStates().get(id);
            if (next == null) return false;
            var previous = states.currentState(display);
            display.getPersistentDataContainer().set(FurnitureStates.Companion.getSTATE_KEY(),
                    PersistentDataType.STRING, id);
            previous.unsetState(display);
            next.setState(display);
            plugin.debug().log("NEXO", "Furniture state changed: entity=%s state=%s", display.getUniqueId(), id);
            return true;
        } catch (RuntimeException ex) {
            plugin.getLogger().severe("Nexo state change failed for " + display.getUniqueId() + ": " + ex);
            return false;
        }
    }
}
