package org.sylvarion.sylvpay;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

public final class MachineGui implements Listener {

    private enum Type { MAIN, TOPUP }

    /** Marks an inventory as one of ours so we can recognise it in click events. */
    private static final class MachineHolder implements InventoryHolder {
        final Type type;
        Inventory inventory;
        MachineHolder(Type type) { this.type = type; }
        @Override public Inventory getInventory() { return inventory; }
    }

    private record CardRef(int slot, String id) {}

    private static final int[] OPTION_SLOTS = {10, 11, 12, 13};
    private static final int SLOT_BALANCE = 15;
    private static final int SLOT_TOPUP = 16;
    private static final int SLOT_REFUND = 17;
    private static final int SLOT_BACK = 22;

    private final SylvPayMain plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public MachineGui(SylvPayMain plugin) {
        this.plugin = plugin;
    }

    private FileConfiguration cfg() { return plugin.getConfig(); }

    // ------------------------------------------------------------------ helpers

    private String fill(String s, String... pairs) {
        for (int i = 0; i + 1 < pairs.length; i += 2) s = s.replace(pairs[i], pairs[i + 1]);
        return s;
    }

    private Component text(String s, String... pairs) {
        return mm.deserialize(fill(s, pairs)).decoration(TextDecoration.ITALIC, false);
    }

    private void msg(Player p, String key, String... pairs) {
        String prefix = cfg().getString("messages.prefix", "");
        String body = cfg().getString("messages." + key, key);
        p.sendMessage(text(prefix + body, pairs));
    }

    private String money(double amount) {
        String symbol = cfg().getString("currency.symbol", "£");
        String position = cfg().getString("currency.position", "before");
        int decimals = Math.max(0, Math.min(2, cfg().getInt("currency.decimals", 2)));
        boolean grouping = cfg().getBoolean("currency.thousands-separator", true);
        String space = cfg().getBoolean("currency.space", false) ? " " : "";
        DecimalFormat format = new DecimalFormat();
        format.setDecimalFormatSymbols(DecimalFormatSymbols.getInstance(Locale.UK));
        format.setGroupingUsed(grouping);
        format.setMinimumFractionDigits(decimals);
        format.setMaximumFractionDigits(decimals);
        String number = format.format(amount);
        return "after".equalsIgnoreCase(position) ? number + space + symbol : symbol + space + number;
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private void applyModel(ItemMeta meta, String model) {
        if (model == null || model.isBlank()) return;
        NamespacedKey key = NamespacedKey.fromString(model);
        if (key != null) meta.setItemModel(key);
    }

    private ItemStack button(String key, String... pairs) {
        ConfigurationSection s = cfg().getConfigurationSection("buttons." + key);
        if (s == null) return new ItemStack(Material.BARRIER);
        Material mat = Material.matchMaterial(s.getString("material", "STONE"));
        if (mat == null) mat = Material.STONE;
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(text(s.getString("name", key), pairs));
        List<Component> lore = new ArrayList<>();
        for (String line : s.getStringList("lore")) lore.add(text(line, pairs));
        meta.lore(lore);
        applyModel(meta, s.getString("item-model"));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createCardItem(String id) {
        ConfigurationSection c = cfg().getConfigurationSection("card");
        Material mat = Material.matchMaterial(c.getString("material", "PAPER"));
        if (mat == null) mat = Material.PAPER;
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(text(c.getString("name", "SylvPay Card"), "{id}", id));
        List<Component> lore = new ArrayList<>();
        for (String line : c.getStringList("lore")) lore.add(text(line, "{id}", id));
        meta.lore(lore);
        applyModel(meta, c.getString("item-model"));
        meta.setMaxStackSize(1);
        meta.getPersistentDataContainer().set(plugin.cardKey(), PersistentDataType.STRING, id);
        item.setItemMeta(meta);
        return item;
    }

    private String getCardId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(plugin.cardKey(), PersistentDataType.STRING);
    }

    /** Finds a card in the player's inventory (the held one first). */
    private CardRef findCard(Player p) {
        PlayerInventory inv = p.getInventory();
        int held = inv.getHeldItemSlot();
        String id = getCardId(inv.getItem(held));
        if (id != null) return new CardRef(held, id);
        for (int i = 0; i < 36; i++) {
            id = getCardId(inv.getItem(i));
            if (id != null) return new CardRef(i, id);
        }
        return null;
    }

    // Optional Nexo GUI glyph titles. Nexo's packet glyph processing is responsible
    // for converting <glyph:id> and <shift:n> tags to its font characters.
    // All standard MiniMessage tags are handled by Adventure MiniMessage.
    // When Nexo is missing, use the ordinary GUI title instead.
    private Component guiTitle(boolean topup) {
        String normalKey = topup ? "gui.topup-title" : "gui.title";
        String fallback = topup ? "<dark_gray>Top Up Your Card" : "<dark_gray>SylvPay Ticket Machine";
        String normal = cfg().getString(normalKey, fallback);

        if (!cfg().getBoolean("gui.custom.enabled", false)
                || !plugin.getServer().getPluginManager().isPluginEnabled("Nexo")) {
            return text(normal);
        }

        String key = topup ? "gui.custom.topup-glyph" : "gui.custom.glyph";
        String glyph = cfg().getString(key, "").trim();
        if (glyph.isBlank()) return text(normal);

        // Accept either a glyph ID or a complete <glyph:id> tag.
        if (!glyph.startsWith("<glyph:")) glyph = "<glyph:" + glyph + ">";

        String prefix = cfg().getString("gui.custom.prefix", "<!italic><!bold><white>");
        String shift = cfg().getString("gui.custom.shift", "");
        String suffix = cfg().getString("gui.custom.suffix", "");
        return text(prefix + shift + glyph + suffix);
    }

    // ------------------------------------------------------------------ menus

    public void openMain(Player p) {
        MachineHolder holder = new MachineHolder(Type.MAIN);
        Inventory inv = Bukkit.createInventory(holder, 27, guiTitle(false));
        holder.inventory = inv;

        List<Double> options = cfg().getDoubleList("card.buy-options");
        double price = cfg().getDouble("card.price");
        for (int i = 0; i < Math.min(4, options.size()); i++) {
            double preload = options.get(i);
            inv.setItem(OPTION_SLOTS[i], button("buy",
                    "{amount}", money(preload),
                    "{price}", money(price),
                    "{total}", money(price + preload)));
        }
        inv.setItem(SLOT_BALANCE, button("balance"));
        inv.setItem(SLOT_TOPUP, button("topup"));
        inv.setItem(SLOT_REFUND, button("refund"));
        p.openInventory(inv);
    }

    private void openTopup(Player p) {
        MachineHolder holder = new MachineHolder(Type.TOPUP);
        Inventory inv = Bukkit.createInventory(holder, 27, guiTitle(true));
        holder.inventory = inv;

        List<Double> options = cfg().getDoubleList("topup-options");
        for (int i = 0; i < Math.min(4, options.size()); i++) {
            inv.setItem(OPTION_SLOTS[i], button("topup-amount", "{amount}", money(options.get(i))));
        }
        inv.setItem(SLOT_BACK, button("back"));
        p.openInventory(inv);
    }

    // ------------------------------------------------------------------ events

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder(false) instanceof MachineHolder holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getInventory()) return;

        int slot = e.getSlot();
        if (holder.type == Type.MAIN) {
            switch (slot) {
                case 10, 11, 12, 13 -> buyCard(p, slot - 10);
                case SLOT_BALANCE -> checkBalance(p);
                case SLOT_TOPUP -> {
                    if (findCard(p) == null) { msg(p, "no-card"); return; }
                    Bukkit.getScheduler().runTask(plugin, () -> openTopup(p));
                }
                case SLOT_REFUND -> refund(p);
                default -> { }
            }
        } else {
            switch (slot) {
                case 10, 11, 12, 13 -> topUp(p, slot - 10);
                case SLOT_BACK -> Bukkit.getScheduler().runTask(plugin, () -> openMain(p));
                default -> { }
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder(false) instanceof MachineHolder) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ actions

    private void buyCard(Player p, int index) {
        List<Double> options = cfg().getDoubleList("card.buy-options");
        if (index >= options.size()) return;

        double preload = round(options.get(index));
        double total = round(cfg().getDouble("card.price") + preload);

        if (!plugin.economy().has(p, total)) {
            msg(p, "not-enough-money", "{amount}", money(total));
            return;
        }
        EconomyResponse r = plugin.economy().withdrawPlayer(p, total);
        if (!r.transactionSuccess()) {
            msg(p, "not-enough-money", "{amount}", money(total));
            return;
        }

        String id;
        try {
            id = plugin.db().createCard(p.getUniqueId(), preload);
        } catch (SQLException ex) {
            plugin.getLogger().severe("Database error while buying card: " + ex.getMessage());
            plugin.economy().depositPlayer(p, total); // give the money back
            msg(p, "error");
            return;
        }

        ItemStack card = createCardItem(id);
        p.getInventory().addItem(card).values()
                .forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
        msg(p, "bought", "{id}", id, "{balance}", money(preload));
    }

    private void checkBalance(Player p) {
        CardRef card = findCard(p);
        if (card == null) { msg(p, "no-card"); return; }
        try {
            Double bal = plugin.db().getBalance(card.id());
            if (bal == null) { msg(p, "invalid-card"); return; }
            msg(p, "balance", "{id}", card.id(), "{balance}", money(bal));
        } catch (SQLException ex) {
            plugin.getLogger().severe("Database error: " + ex.getMessage());
            msg(p, "error");
        }
    }

    private void topUp(Player p, int index) {
        List<Double> options = cfg().getDoubleList("topup-options");
        if (index >= options.size()) return;
        double amount = round(options.get(index));

        CardRef card = findCard(p);
        if (card == null) { msg(p, "no-card"); return; }

        try {
            Double current = plugin.db().getBalance(card.id());
            if (current == null) { msg(p, "invalid-card"); return; }

            double max = cfg().getDouble("max-balance", 0);
            if (max > 0 && current + amount > max) {
                msg(p, "max-balance", "{max}", money(max));
                return;
            }
            if (!plugin.economy().has(p, amount)) {
                msg(p, "not-enough-money", "{amount}", money(amount));
                return;
            }
            EconomyResponse r = plugin.economy().withdrawPlayer(p, amount);
            if (!r.transactionSuccess()) {
                msg(p, "not-enough-money", "{amount}", money(amount));
                return;
            }
            if (!plugin.db().addBalance(card.id(), amount)) {
                plugin.economy().depositPlayer(p, amount); // card vanished, give money back
                msg(p, "invalid-card");
                return;
            }
            msg(p, "topped-up", "{amount}", money(amount), "{balance}", money(current + amount));
        } catch (SQLException ex) {
            plugin.getLogger().severe("Database error: " + ex.getMessage());
            msg(p, "error");
        }
    }

    private void refund(Player p) {
        CardRef card = findCard(p);
        if (card == null) { msg(p, "no-card"); return; }
        try {
            Double balance = plugin.db().closeCard(card.id());
            if (balance == null) {
                msg(p, "invalid-card");
                p.getInventory().setItem(card.slot(), null); // remove the dead card
                return;
            }
            p.getInventory().setItem(card.slot(), null);   // take the card away
            if (balance > 0) plugin.economy().depositPlayer(p, balance);
            msg(p, "refunded", "{id}", card.id(), "{amount}", money(balance));
        } catch (SQLException ex) {
            plugin.getLogger().severe("Database error: " + ex.getMessage());
            msg(p, "error");
        }
    }
}
