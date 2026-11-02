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

    private enum Type { MAIN, TOPUP, HISTORY, REFUND_CONFIRM }

    private static final class MachineHolder implements InventoryHolder {
        final Type type;
        Inventory inventory;
        ItemStack card;
        int page;
        boolean transferring;
        MachineHolder(Type type, ItemStack card, int page) {
            this.type = type; this.card = card; this.page = page;
        }
        @Override public Inventory getInventory() { return inventory; }
    }

    private static final int[] OPTION_SLOTS = {10, 11, 12, 13};
    private static final int SLOT_CARD = 4;
    private static final int SLOT_BALANCE = 15;
    private static final int SLOT_TOPUP = 16;
    private static final int SLOT_REFUND = 17;
    private static final int SLOT_HISTORY = 22;
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

    private String selectedId(MachineHolder holder) { return getCardId(holder.card); }

    private boolean requireCard(Player player, MachineHolder holder) {
        if (selectedId(holder) != null) return true;
        player.sendMessage(text("<red>Insert a Tap2Go card into the card slot first."));
        return false;
    }

    private ItemStack simple(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(text(name));
        List<Component> lines = new ArrayList<>();
        for (String line : lore) lines.add(text(line));
        meta.lore(lines);
        item.setItemMeta(meta);
        return item;
    }

    private void slot(MachineHolder holder) {
        holder.inventory.setItem(SLOT_CARD, holder.card == null
                ? simple(Material.GRAY_STAINED_GLASS_PANE, "<yellow>Insert Tap2Go card",
                "<gray>Click a card in your inventory", "<gray>to insert it here")
                : holder.card.clone());
    }

    private void navigate(Player p, MachineHolder old, Type type, int page) {
        old.transferring = true;
        ItemStack card = old.card;
        old.card = null;
        Bukkit.getScheduler().runTask(plugin, () -> open(p, type, card, page));
    }

    private void returnCard(Player p, MachineHolder holder) {
        ItemStack card = holder.card;
        holder.card = null;
        if (card == null) return;
        p.getInventory().addItem(card).values().forEach(left ->
                p.getWorld().dropItemNaturally(p.getLocation(), left));
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

    public void openMain(Player p) { open(p, Type.MAIN, null, 0); }

    private void open(Player p, Type type, ItemStack card, int page) {
        MachineHolder holder = new MachineHolder(type, card, page);
        int size = type == Type.HISTORY ? 45 : 27;
        Component title = switch (type) {
            case MAIN -> guiTitle(false);
            case TOPUP -> guiTitle(true);
            case HISTORY -> text("<dark_gray>Tap2Go Transaction History");
            case REFUND_CONFIRM -> text("<dark_red>Confirm Card Refund");
        };
        Inventory inv = Bukkit.createInventory(holder, size, title);
        holder.inventory = inv;
        slot(holder);
        if (type == Type.MAIN) {
            List<Double> options = cfg().getDoubleList("card.buy-options");
            double price = cfg().getDouble("card.price");
            for (int i = 0; i < Math.min(4, options.size()); i++) {
                double preload = options.get(i);
                inv.setItem(OPTION_SLOTS[i], button("buy", "{amount}", money(preload),
                        "{price}", money(price), "{total}", money(price + preload)));
            }
            inv.setItem(SLOT_BALANCE, button("balance"));
            inv.setItem(SLOT_TOPUP, button("topup"));
            inv.setItem(SLOT_REFUND, button("refund"));
            inv.setItem(SLOT_HISTORY, simple(Material.BOOK, "<aqua>Transaction History",
                    "<gray>View journeys and top-ups for inserted card"));
        } else if (type == Type.TOPUP) {
            List<Double> options = cfg().getDoubleList("topup-options");
            for (int i = 0; i < Math.min(4, options.size()); i++)
                inv.setItem(OPTION_SLOTS[i], button("topup-amount", "{amount}", money(options.get(i))));
            inv.setItem(SLOT_BACK, button("back"));
        } else if (type == Type.REFUND_CONFIRM) {
            inv.setItem(11, simple(Material.LIME_CONCRETE, "<green>Confirm refund",
                    "<gray>Deactivate this card and refund its balance"));
            inv.setItem(15, simple(Material.RED_CONCRETE, "<red>Cancel"));
        } else {
            try {
                List<CardDatabase.HistoryEntry> entries = plugin.db().history(selectedId(holder), page, 21);
                for (int i = 0; i < entries.size(); i++) {
                    CardDatabase.HistoryEntry h = entries.get(i);
                    String date = java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm")
                            .withZone(java.time.ZoneId.systemDefault())
                            .format(java.time.Instant.ofEpochMilli(h.endedAt()));
                    ItemStack entry;
                    if ("JOURNEY".equals(h.kind())) {
                        entry = simple(Material.MINECART, "<aqua>Journey " + money(h.amount()),
                                "<gray>From: <white>" + stationLabel(h.origin()),
                                "<gray>To: <white>" + stationLabel(h.destination()),
                                "<gray>Fare: <white>" + money(h.amount()), "<gray>" + date);
                    } else {
                        entry = simple(Material.PAPER, "<yellow>" + h.kind() + " " + money(h.amount()),
                                "<gray>" + date);
                    }
                    inv.setItem(9 + i + (i / 7) * 2, entry);
                }
                if (entries.isEmpty()) inv.setItem(22, simple(Material.BARRIER, "<gray>No transactions on this page"));
                if (page > 0) inv.setItem(36, simple(Material.ARROW, "<yellow>Previous page"));
                if (entries.size() == 21) inv.setItem(44, simple(Material.ARROW, "<yellow>Next page"));
            } catch (SQLException ex) {
                plugin.getLogger().severe("History query failed: " + ex.getMessage());
                msg(p, "error");
            }
            inv.setItem(40, button("back"));
        }
        p.openInventory(inv);
    }

    private String stationLabel(String id) {
        if (id == null) return "Unknown";
        org.bukkit.configuration.file.YamlConfiguration yaml =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                        new java.io.File(plugin.getDataFolder(), "stations.yml"));
        String base = "stations." + id;
        String name = yaml.getString(base + ".name", id);
        return yaml.contains(base + ".zone") ? name + " (Zone " + yaml.getInt(base + ".zone") + ")" : name;
    }

    // ------------------------------------------------------------------ events

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder(false) instanceof MachineHolder holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClick().isKeyboardClick() || e.getClick().isShiftClick()) return;
        if (e.getClickedInventory() == p.getInventory()) {
            if (holder.card != null) return;
            ItemStack item = e.getCurrentItem();
            if (getCardId(item) == null) return;
            if (item.getAmount() != 1) return;
            try {
                if (plugin.db().getBalance(getCardId(item)) == null) { msg(p, "invalid-card"); return; }
            } catch (SQLException ex) { msg(p, "error"); return; }
            holder.card = item.clone();
            e.setCurrentItem(null);
            slot(holder);
            return;
        }
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        int index = e.getSlot();
        if (index == SLOT_CARD) {
            if (holder.card != null && e.getCursor().getType().isAir()) {
                e.setCursor(holder.card);
                holder.card = null;
                slot(holder);
            } else if (holder.card == null && getCardId(e.getCursor()) != null && e.getCursor().getAmount() == 1) {
                try {
                    if (plugin.db().getBalance(getCardId(e.getCursor())) == null) { msg(p, "invalid-card"); return; }
                } catch (SQLException ex) { msg(p, "error"); return; }
                holder.card = e.getCursor().clone();
                e.setCursor(null);
                slot(holder);
            }
            return;
        }
        switch (holder.type) {
            case MAIN -> {
                switch (index) {
                    case 10, 11, 12, 13 -> buyCard(p, index - 10);
                    case SLOT_BALANCE -> { if (requireCard(p, holder)) checkBalance(p, holder); }
                    case SLOT_TOPUP -> { if (requireCard(p, holder)) navigate(p, holder, Type.TOPUP, 0); }
                    case SLOT_REFUND -> { if (requireCard(p, holder)) navigate(p, holder, Type.REFUND_CONFIRM, 0); }
                    case SLOT_HISTORY -> { if (requireCard(p, holder)) navigate(p, holder, Type.HISTORY, 0); }
                }
            }
            case TOPUP -> {
                if (index >= 10 && index <= 13 && requireCard(p, holder)) topUp(p, holder, index - 10);
                else if (index == SLOT_BACK) navigate(p, holder, Type.MAIN, 0);
            }
            case REFUND_CONFIRM -> {
                if (index == 11 && requireCard(p, holder)) refund(p, holder);
                else if (index == 15) navigate(p, holder, Type.MAIN, 0);
            }
            case HISTORY -> {
                if (index == 40) navigate(p, holder, Type.MAIN, 0);
                else if (index == 36 && holder.page > 0) navigate(p, holder, Type.HISTORY, holder.page - 1);
                else if (index == 44 && holder.inventory.getItem(44) != null)
                    navigate(p, holder, Type.HISTORY, holder.page + 1);
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder(false) instanceof MachineHolder) e.setCancelled(true);
    }

    @EventHandler
    public void onClose(org.bukkit.event.inventory.InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder(false) instanceof MachineHolder holder)) return;
        if (!holder.transferring && e.getPlayer() instanceof Player p) returnCard(p, holder);
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

    private void checkBalance(Player p, MachineHolder holder) {
        String id = selectedId(holder);
        try {
            Double balance = plugin.db().getBalance(id);
            if (balance == null) { msg(p, "invalid-card"); return; }
            msg(p, "balance", "{id}", id, "{balance}", money(balance));
        } catch (SQLException ex) { msg(p, "error"); plugin.getLogger().severe(ex.getMessage()); }
    }

    private void topUp(Player p, MachineHolder holder, int index) {
        List<Double> options = cfg().getDoubleList("topup-options");
        if (index >= options.size()) return;
        double amount = round(options.get(index));
        if (amount <= 0 || !Double.isFinite(amount)) return;
        String id = selectedId(holder);
        try {
            Double current = plugin.db().getBalance(id);
            if (current == null) { msg(p, "invalid-card"); return; }
            double max = cfg().getDouble("max-balance", 0);
            if (max > 0 && current + amount > max) {
                msg(p, "max-balance", "{max}", money(max)); return;
            }
            if (!plugin.economy().has(p, amount)) {
                msg(p, "not-enough-money", "{amount}", money(amount)); return;
            }
            EconomyResponse response = plugin.economy().withdrawPlayer(p, amount);
            if (!response.transactionSuccess()) { msg(p, "not-enough-money", "{amount}", money(amount)); return; }
            try {
                if (!plugin.db().addBalance(id, amount)) {
                    plugin.economy().depositPlayer(p, amount);
                    msg(p, "invalid-card"); return;
                }
            } catch (SQLException ex) {
                plugin.economy().depositPlayer(p, amount);
                throw ex;
            }
            msg(p, "topped-up", "{amount}", money(amount), "{balance}", money(round(current + amount)));
        } catch (SQLException ex) { plugin.getLogger().severe("Top-up error: " + ex); msg(p, "error"); }
    }

    private void refund(Player p, MachineHolder holder) {
        String id = selectedId(holder);
        try {
            if (plugin.db().activeJourney(id) != null) {
                p.sendMessage(text("<red>Finish your journey before refunding this card.")); return;
            }
            Double balance = plugin.db().closeCard(id);
            if (balance == null) { msg(p, "invalid-card"); return; }
            holder.card = null; // card is now permanently deactivated
            slot(holder);
            if (balance > 0) {
                EconomyResponse response = plugin.economy().depositPlayer(p, balance);
                if (!response.transactionSuccess()) {
                    plugin.getLogger().severe("MANUAL REFUND REQUIRED: card " + id + ", amount " + balance);
                    p.sendMessage(text("<red>Refund payout failed; contact an administrator with card ID " + id));
                    return;
                }
            }
            msg(p, "refunded", "{id}", id, "{amount}", money(balance));
            navigate(p, holder, Type.MAIN, 0);
        } catch (SQLException ex) { plugin.getLogger().severe("Refund error: " + ex); msg(p, "error"); }
    }
}
