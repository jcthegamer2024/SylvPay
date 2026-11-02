package org.sylvarion.sylvpay;

import com.nexomc.nexo.api.NexoFurniture;
import com.nexomc.nexo.api.events.furniture.NexoFurnitureInteractEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.EquipmentSlot;
import java.io.IOException;

/** All Nexo integration uses its published public Java API. */
public final class NexoGateBridge implements Listener {
    private final SylvPayMain plugin;
    private FareGateManager gates;

    public NexoGateBridge(SylvPayMain plugin) { this.plugin = plugin; }

    public boolean register(FareGateManager manager) {
        if (!Bukkit.getPluginManager().isPluginEnabled("Nexo")) return false;
        gates = manager;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(NexoFurnitureInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        ItemDisplay display = event.getBaseEntity();
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
        var mechanic = NexoFurniture.furnitureMechanic(display);
        return mechanic == null ? null : mechanic.getItemID();
    }

    public boolean exists(String id) {
        return id != null && !id.isBlank() && NexoFurniture.isFurniture(id);
    }

    /** Use Nexo removal and placement, so Nexo manages the collision blocks itself. */
    public ItemDisplay replace(ItemDisplay previous, String replacementId) {
        if (previous == null || !previous.isValid() || !exists(replacementId)) return null;
        Location position = previous.getLocation().clone();
        if (!NexoFurniture.remove(previous)) return null;
        try {
            ItemDisplay placed = NexoFurniture.place(replacementId, position, position.getYaw(), BlockFace.UP);
            if (placed != null) return placed;
        } catch (RuntimeException ex) {
            plugin.getLogger().severe("Nexo placement failed: " + ex);
        }
        plugin.getLogger().severe("Furniture removed, but replacement could not be placed at " + position);
        return null;
    }

    public ItemDisplay place(String id, Location location) {
        if (!exists(id)) return null;
        try {
            return NexoFurniture.place(id, location, location.getYaw(), BlockFace.UP);
        } catch (RuntimeException ex) {
            plugin.getLogger().severe("Nexo placement failed: " + ex);
            return null;
        }
    }
}
