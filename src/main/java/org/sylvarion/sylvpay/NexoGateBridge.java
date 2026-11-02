package org.sylvarion.sylvpay;

import org.bukkit.Bukkit;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;

/** Nexo integration via its published runtime API. No Nexo classes are bundled into SylvPay. */
public final class NexoGateBridge {
    private final SylvPayMain plugin;
    private Class<?> furnitureApi;
    private Class<?> itemsApi;

    public NexoGateBridge(SylvPayMain plugin) { this.plugin = plugin; }

    @SuppressWarnings("unchecked")
    public boolean register(FareGateManager gates) {
        if (Bukkit.getPluginManager().getPlugin("Nexo") == null) return false;
        try {
            ClassLoader nexoLoader = Bukkit.getPluginManager().getPlugin("Nexo").getClass().getClassLoader();
            furnitureApi = nexoLoader.loadClass("com.nexomc.nexo.api.NexoFurniture");
            itemsApi = nexoLoader.loadClass("com.nexomc.nexo.api.NexoItems");
            Class<? extends Event> eventType = (Class<? extends Event>) nexoLoader.loadClass(
                    "com.nexomc.nexo.api.events.furniture.NexoFurnitureInteractEvent");
            Listener marker = new Listener() {};
            Bukkit.getPluginManager().registerEvent(eventType, marker, EventPriority.NORMAL,
                    (listener, event) -> {
                        try {
                            Player player = (Player) event.getClass().getMethod("getPlayer").invoke(event);
                            Object hand = event.getClass().getMethod("getHand").invoke(event);
                            if (!"HAND".equals(hand.toString())) return;
                            ItemDisplay display = (ItemDisplay) event.getClass().getMethod("getBaseEntity").invoke(event);
                            if (gates.tap(player, display)) {
                                event.getClass().getMethod("setCancelled", boolean.class).invoke(event, true);
                            }
                        } catch (ReflectiveOperationException ex) {
                            plugin.getLogger().warning("Nexo tap event failed: " + ex.getMessage());
                        }
                    }, plugin, true);
            plugin.getLogger().info("Nexo fare-gate interaction integration registered.");
            return true;
        } catch (ReflectiveOperationException | LinkageError ex) {
            plugin.getLogger().severe("Nexo API incompatible: " + ex.getMessage());
            return false;
        }
    }

    public ItemDisplay target(Player player) {
        if (furnitureApi == null) return null;
        try {
            Object value = furnitureApi.getMethod("findTargetFurniture", Player.class).invoke(null, player);
            if (!(value instanceof ItemDisplay display)) return null;
            if (display.getLocation().distanceSquared(player.getEyeLocation()) > 36) return null;
            return display;
        } catch (ReflectiveOperationException | IllegalArgumentException ex) {
            plugin.getLogger().warning("Could not find target Nexo furniture: " + ex.getMessage());
            return null;
        }
    }

    public String furnitureId(ItemDisplay display) {
        if (furnitureApi == null) return null;
        try {
            Object mechanic = furnitureApi.getMethod("furnitureMechanic", org.bukkit.entity.Entity.class).invoke(null, display);
            if (mechanic == null) return null;
            return (String) mechanic.getClass().getMethod("getItemID").invoke(mechanic);
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().warning("Could not read Nexo furniture ID: " + ex.getMessage());
            return null;
        }
    }

    /** Build a visual ItemStack from a Nexo item ID. It does NOT replace the furniture entity. */
    public ItemStack item(String id) {
        if (itemsApi == null || id == null || id.isBlank()) return null;
        try {
            Object builder = itemsApi.getMethod("itemFromId", String.class).invoke(null, id);
            if (builder == null) return null;
            Method build = builder.getClass().getMethod("build");
            return (ItemStack) build.invoke(builder);
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().warning("Could not build Nexo item '" + id + "': " + ex.getMessage());
            return null;
        }
    }
}
