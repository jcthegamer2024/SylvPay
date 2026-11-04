package org.sylvarion.sylvpay;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import com.nexomc.nexo.glyphs.GlyphTag;
import com.nexomc.nexo.glyphs.ShiftTag;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
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

    private enum Type { MAIN, INSERT, CREATE, TOPUP, HISTORY, REFUND_CONFIRM }

    private static final class MachineHolder implements InventoryHolder {
        final Type type;
        Type afterInsert;
        Inventory inventory;
        ItemStack card;
        int page;
        String creationAmount = "0"; // Reused as keypad input for creation and top-ups
        boolean transferring;
        MachineHolder(Type type, ItemStack card, int page) {
            this.type = type; this.card = card; this.page = page;
        }
        @Override public Inventory getInventory() { return inventory; }
    }

    private static final int[] OPTION_SLOTS = {10, 11, 12, 13};
    private static final int SLOT_CARD = 4;
    private static final int SLOT_INSERT = 13;
    private static final int SLOT_BALANCE = 15;
    private static final int SLOT_TOPUP = 16;
    private static final int SLOT_REFUND = 17;
    private static final int SLOT_HISTORY = 22;
    private static final int SLOT_BACK = 22;
    private static final int SLOT_CREATE = 11;
    private static final int[] KEYPAD_DIGITS = {19, 20, 21, 28, 29, 30, 37, 38, 39};
    private static final int SLOT_DECIMAL = 46;
    private static final int SLOT_ZERO = 47;
    private static final int SLOT_CLEAR = 48;
    private static final int SLOT_CONFIRM_CREATE = 33;
    private static final int SLOT_CREATE_BACK = 53;

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
        return mm.deserialize(plugin.lang().brand(fill(s, pairs))).decoration(TextDecoration.ITALIC, false);
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
                "<gray>Click to open the card reader", "<gray>Then drag a card into its slot")
                : holder.card.clone());
    }

    private void navigateToInsert(Player p, MachineHolder old, Type target) {
        old.afterInsert = target;
        navigate(p, old, Type.INSERT, 0);
    }

    private void navigate(Player p, MachineHolder old, Type type, int page) {
        plugin.debug().log("GUI", "Navigation: player=%s %s -> %s card=%s",p.getName(),old.type,type,selectedId(old));
        old.transferring = true;
        ItemStack card = old.card;
        old.card = null;
        Type afterInsert = type == Type.INSERT ? old.afterInsert : null;
        Bukkit.getScheduler().runTask(plugin, () -> open(p, type, card, page, afterInsert));
    }

    // Paper/Bukkit: update the cursor through HumanEntity rather than the
    // deprecated InventoryClickEvent#setCursor. Schedule after the inventory
    // event so the server finishes processing the cancelled click/drag first.
    private void updateCursorNextTick(Player player, ItemStack item) {
        ItemStack copy = item == null ? null : item.clone();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) player.setItemOnCursor(copy);
        });
    }

    private void returnCard(Player p, MachineHolder holder) {
        ItemStack card = holder.card;
        holder.card = null;
        if (card == null) return;
        plugin.debug().log("CARD", "Returned card to player=%s card=%s",p.getName(),getCardId(card));
        p.getInventory().addItem(card).values().forEach(left ->
                p.getWorld().dropItemNaturally(p.getLocation(), left));
    }

    // Nexo glyph tags are not standard MiniMessage tags. Parse them with Nexo's
    // resolvers, and NEVER pass glyph IDs through the branding replacement.
    private Component guiTitle(boolean topup) {
        return guiTitle(topup ? "topup" : "main");
    }

    private Component guiTitle(String screen) {
        String normalKey = switch (screen) {
            case "insert" -> "gui.insert-title";
            case "create" -> "gui.create-title";
            case "topup" -> "gui.topup-title";
            default -> "gui.title";
        };
        String normal = cfg().getString(normalKey, "<dark_gray>SylvPay Ticket Machine");
        if (!cfg().getBoolean("gui.custom.enabled", false)
                || !plugin.getServer().getPluginManager().isPluginEnabled("Nexo")) return text(normal);
        String base = switch (screen) {
            case "insert" -> "gui.custom.insert";
            case "create" -> cfg().isConfigurationSection("gui.custom.create") ? "gui.custom.create" : "gui.custom.numpad";
            case "topup" -> "gui.custom.numpad";
            default -> "gui.custom";
        };
        if (!base.equals("gui.custom") && !cfg().getBoolean(base + ".enabled", false)) return text(normal);
        String configured = cfg().getString(base + ".glyph",
                screen.equals("topup") ? cfg().getString("gui.custom.topup-glyph", "") :
                        screen.equals("main") ? cfg().getString("gui.custom.glyph", "") : "").trim();
        if (configured.isBlank()) return text(normal);

        // Accept either "tap2gomainscreen", "<glyph:tap2gomainscreen>",
        // or "<shift:-9>tap2gomainscreen" without corrupting the glyph ID.
        // An inline shift takes precedence over gui.custom.shift, so it is never doubled.
        boolean hasInlineShift = configured.matches("(?s).*<shift:[^>]+>.*");
        StringBuilder inlineTags = new StringBuilder();
        java.util.regex.Matcher shiftMatcher = java.util.regex.Pattern
                .compile("<shift:[^>]+>").matcher(configured);
        while (shiftMatcher.find()) inlineTags.append(shiftMatcher.group());
        String glyphId = configured.replaceAll("<shift:[^>]+>", "").trim();
        String glyphTag = glyphId.startsWith("<glyph:") && glyphId.endsWith(">")
                ? glyphId : "<glyph:" + glyphId + ">";
        // A numeric title-shift is easier to configure than a raw Nexo tag.
        // Existing inline shifts still work, but take precedence to prevent doubling.
        String shiftTag;
        if (hasInlineShift) {
            shiftTag = inlineTags.toString();
        } else if (cfg().isSet(base + ".title-shift")) {
            int shift = cfg().getInt(base + ".title-shift", 0);
            shiftTag = shift == 0 ? "" : "<shift:" + shift + ">";
        } else {
            shiftTag = cfg().getString(base + ".shift", cfg().getString("gui.custom.shift", "")); // Legacy config
        }
        String raw = cfg().getString(base + ".prefix", cfg().getString("gui.custom.prefix", "<!italic><!bold><white>"))
                + shiftTag + glyphTag + cfg().getString(base + ".suffix", cfg().getString("gui.custom.suffix", ""));
        try {
            return MiniMessage.builder().tags(net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.resolver(
                            net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.standard(),
                            GlyphTag.INSTANCE.getRESOLVER(), ShiftTag.INSTANCE.getRESOLVER()))
                    .build().deserialize(raw).decoration(TextDecoration.ITALIC, false);
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Cannot render Nexo GUI glyph: " + ex.getMessage());
            return text(normal);
        }
    }

    private static final Map<String, List<Integer>> DEFAULT_MAIN_SLOTS = Map.of(
            "create-card", List.of(19,20,28,29),
            "balance", List.of(23,24,32,33),
            "topup", List.of(37,38,46,47),
            "refund", List.of(41,42,50,51),
            "history", List.of(35),
            "card-reader", List.of());

    // Read the server's personal config.yml explicitly for GUI slot overrides.
    // Bukkit's bundled resource defaults must never mask a personal slot value.
    private List<Integer> configuredMainSlots(String action) {
        String path = "gui.main.slots." + action;
        java.io.File personalFile = new java.io.File(plugin.getDataFolder(), "config.yml");
        org.bukkit.configuration.file.YamlConfiguration personal =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(personalFile);

        if (personal.isSet(path)) {
            if (!personal.isList(path)) {
                plugin.getLogger().warning("Personal config has invalid GUI slot list at " + path);
                return List.of();
            }
            return personal.getIntegerList(path);
        }

        // If the path is absent from the personal file, fall back to the
        // in-memory config and finally to the built-in Java defaults.
        if (cfg().contains(path)) return cfg().getIntegerList(path);
        return DEFAULT_MAIN_SLOTS.getOrDefault(action, List.of());
    }

    private Map<Integer, String> mainActions() {
        Map<Integer, String> actions = new HashMap<>();
        // Map.of() iteration order is unspecified. Apply ordinary actions first,
        // then Journey History last so its dedicated 1x1 slot takes precedence.
        for (String action : List.of("create-card", "balance", "topup", "refund", "card-reader", "history")) {
            List<Integer> slots = configuredMainSlots(action);
            for (int index : slots) {
                if (index < 0 || index >= 54) {
                    plugin.getLogger().warning("Invalid main GUI slot " + index + " for " + action);
                    continue;
                }
                if (action.equals("history")) {
                    String previous = actions.put(index, action);
                    if (previous != null && !previous.equals(action))
                        plugin.getLogger().warning("Journey History overrides " + previous + " at main GUI slot " + index);
                } else {
                    String previous = actions.putIfAbsent(index, action);
                    if (previous != null && !previous.equals(action))
                        plugin.getLogger().warning("Main GUI slot " + index + " overlaps " + previous + " and " + action);
                }
            }
        }
        return actions;
    }

    private void renderMain(MachineHolder holder) {
        boolean show = cfg().getBoolean("gui.main.show-items", false);
        if (!show) return; // Empty slots remain clickable; the resource-pack glyph draws the buttons.
        for (var entry : mainActions().entrySet()) {
            String action = entry.getValue();
            ItemStack item = switch (action) {
                case "create-card" -> buttonOrDefault("create-card", Material.PAPER,
                        "<gold>Create New Tap2Go Card", "<gray>Choose an initial balance");
                case "balance" -> button("balance");
                case "topup" -> button("topup");
                case "refund" -> button("refund");
                case "history" -> simple(Material.BOOK, "<aqua>Transaction History");
                case "card-reader" -> holder.card == null
                        ? simple(Material.GRAY_STAINED_GLASS_PANE, "<yellow>Insert Tap2Go card")
                        : holder.card.clone();
                default -> null;
            };
            if (item != null && !(action.equals("create-card") && holder.card != null))
                holder.inventory.setItem(entry.getKey(), item);
        }
    }

    // ------------------------------------------------------------------ menus

    public void openMain(Player p) { open(p, Type.MAIN, null, 0); }

    private void open(Player p, Type type, ItemStack card, int page) {
        open(p, type, card, page, null);
    }

    private void open(Player p, Type type, ItemStack card, int page, Type afterInsert) {
        MachineHolder holder = new MachineHolder(type, card, page);
        holder.afterInsert = afterInsert;
        // Keep every SylvPay menu the same size as the main ticket machine.
        // Nexo title glyphs and click slots do not change with this setting.
        int size = 54;
        Component title = switch (type) {
            case MAIN -> guiTitle(false);
            case INSERT -> guiTitle("insert");
            case CREATE -> guiTitle("create");
            case TOPUP -> guiTitle("topup");
            case HISTORY -> text("<dark_gray>Tap2Go Transaction History");
            case REFUND_CONFIRM -> text("<dark_red>Confirm Card Refund");
        };
        Inventory inv = Bukkit.createInventory(holder, size, title);
        holder.inventory = inv;
        if (type != Type.CREATE && type != Type.INSERT && type != Type.MAIN) slot(holder);
        if (type == Type.MAIN) {
            renderMain(holder);
        } else if (type == Type.INSERT) {
            renderInsert(holder);
        } else if (type == Type.CREATE) {
            renderCreate(holder);
        } else if (type == Type.TOPUP) {
            renderTopup(holder);
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
        plugin.debug().log("GUI", "Opened %s: player=%s card=%s page=%d",type,p.getName(),selectedId(holder),page);
        p.openInventory(inv);
    }

    private void renderInsert(MachineHolder holder) {
        Inventory inv = holder.inventory;
        if (cfg().getBoolean("gui.insert.show-items", true) || holder.card != null) inv.setItem(insertCardSlot(), holder.card == null
                ? simple(Material.HOPPER, "<yellow>Drop your Tap2Go card here",
                "<gray>Pick up a card from your inventory",
                "<gray>and place or drag it into this slot")
                : holder.card.clone());
        if (cfg().getBoolean("gui.insert.show-items", true)) inv.setItem(11, simple(Material.BOOK, "<aqua>Card reader",
                holder.card == null ? "<gray>No card inserted" : "<green>Card inserted",
                "<gray>Only the card in the reader can be used"));
        if (cfg().getBoolean("gui.insert.show-items", true)) inv.setItem(15, simple(Material.LIME_CONCRETE, "<green>Continue",
                "<gray>Return to the ticket machine"));
        if (cfg().getBoolean("gui.insert.show-items", true)) inv.setItem(insertBackSlot(), button("back"));
    }

    private int inventorySize(String path, int fallback) {
        int value = cfg().getInt(path, fallback);
        return value >= 9 && value <= 54 && value % 9 == 0 ? value : fallback;
    }

    private int insertCardSlot() {
        int slot = cfg().getInt("gui.insert.card-slot", SLOT_INSERT);
        return slot >= 0 && slot < 54 ? slot : SLOT_INSERT;
    }

    private int insertBackSlot() {
        int slot = cfg().getInt("gui.insert.back-slot", 22);
        return slot >= 0 && slot < 54 ? slot : 22;
    }

    private void inserted(Player p, MachineHolder holder) {
        if (holder.afterInsert == Type.MAIN) {
            checkBalance(p, holder);
            navigate(p, holder, Type.MAIN, 0);
        } else if (holder.afterInsert != null) {
            navigate(p, holder, holder.afterInsert, 0);
        } else {
            renderInsert(holder);
        }
    }

    private boolean validCard(Player p, ItemStack item) {
        String id = getCardId(item);
        if (id == null || item.getAmount() != 1) {plugin.debug().log("CARD", "Invalid item offered by %s",p.getName());return false;}
        try {
            if (plugin.db().getBalance(id) == null) { msg(p, "invalid-card"); return false; }
            return true;
        } catch (SQLException ex) {
            plugin.getLogger().severe("Card validation failed: " + ex);
            msg(p, "error");
            return false;
        }
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

    // Configurable local UI feedback. Does not broadcast to nearby players.
    private void playButtonSound(Player player, MachineHolder holder, int slot) {
        if (!cfg().getBoolean("gui.sounds.enabled", true)) return;
        ItemStack item = holder.inventory.getItem(slot);
        if ((item == null || item.getType().isAir()) && holder.type != Type.MAIN
                && holder.type != Type.CREATE && holder.type != Type.TOPUP && holder.type != Type.INSERT) return;

        boolean keypad = (holder.type == Type.CREATE || holder.type == Type.TOPUP)
                && translateKeypadSlot(slot) >= 0;
        String path = keypad ? "gui.sounds.keypad" : "gui.sounds.button";
        String sound = cfg().getString(path + ".sound", "minecraft:ui.button.click");
        if (sound == null || sound.isBlank()) return;
        float volume = (float) Math.max(0, Math.min(2, cfg().getDouble(path + ".volume", 0.6)));
        float pitch = (float) Math.max(0.5, Math.min(2, cfg().getDouble(path + ".pitch", keypad ? 1.3 : 1.0)));
        try {
            player.playSound(player.getLocation(), sound, SoundCategory.MASTER, volume, pitch);
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Invalid SylvPay GUI sound '" + sound + "': " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ events

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder(false) instanceof MachineHolder holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClick().isKeyboardClick() || e.getClick().isShiftClick()) return;
        if (e.getClickedInventory() == e.getView().getTopInventory()) {
            playButtonSound(p, holder, e.getSlot());
        }
        if (holder.type == Type.CREATE || holder.type == Type.TOPUP) {
            if (e.getClickedInventory() == e.getView().getTopInventory()) {
                int logical = translateKeypadSlot(e.getSlot());
                if (logical < 0) return;
                if (holder.type == Type.CREATE) handleCreateClick(p, holder, logical);
                else handleTopupClick(p, holder, logical);
            }
            return;
        }
        if (holder.type == Type.INSERT) {
            // The bottom inventory behaves normally: pick up a card, then place
            // it in the dedicated reader slot. Never permit shift/hotbar moves.
            if (e.getClickedInventory() == p.getInventory()) {
                e.setCancelled(false);
                return;
            }
            if (e.getClickedInventory() != e.getView().getTopInventory()) return;
            int index = e.getSlot();
            if (index == insertCardSlot()) {
                ItemStack cursor = e.getCursor();
                if (holder.card != null && (cursor == null || cursor.getType().isAir())) {
                    plugin.debug().log("CARD", "Card removed from reader: player=%s card=%s",p.getName(),selectedId(holder));
                    updateCursorNextTick(p, holder.card);
                    holder.card = null;
                    renderInsert(holder);
                } else if (holder.card == null && validCard(p, cursor)) {
                    holder.card = cursor.clone();
                    plugin.debug().log("CARD", "Card inserted: player=%s card=%s",p.getName(),selectedId(holder));
                    updateCursorNextTick(p, null);
                    inserted(p, holder);
                } else if (holder.card != null && getCardId(cursor) != null) {
                    p.sendMessage(text("<yellow>Remove the inserted card first."));
                }
            } else if (index == insertBackSlot() || (index == 15 && cfg().getBoolean("gui.insert.show-items", true))) {
                navigate(p, holder, Type.MAIN, 0);
            }
            return;
        }
        // Card insertion/removal is only possible inside the reader GUI.
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        int index = e.getSlot();
        if (holder.type == Type.MAIN) {
            String action = mainActions().get(index);
            if (action == null) return;
            switch (action) {
                case "card-reader" -> navigate(p, holder, Type.INSERT, 0);
                case "create-card" -> { if (holder.card == null) navigate(p, holder, Type.CREATE, 0); }
                case "balance" -> navigateToInsert(p, holder, Type.MAIN);
                case "topup" -> navigateToInsert(p, holder, Type.TOPUP);
                case "refund" -> navigateToInsert(p, holder, Type.REFUND_CONFIRM);
                case "history" -> navigateToInsert(p, holder, Type.HISTORY);
            }
            return;
        }
        if (index == SLOT_CARD) {
            navigate(p, holder, Type.INSERT, 0);
            return;
        }
        switch (holder.type) {
            case INSERT, MAIN -> { /* Handled above. */ }
            case CREATE -> { /* Handled above. */ }
            case TOPUP -> { /* Handled by keypad above. */ }
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
        if (!(e.getView().getTopInventory().getHolder(false) instanceof MachineHolder holder)) return;
        // Dragging one card directly onto the dedicated reader slot is allowed.
        if (holder.type == Type.INSERT && holder.card == null
                && e.getRawSlots().size() == 1 && e.getRawSlots().contains(insertCardSlot())
                && e.getWhoClicked() instanceof Player p && validCard(p, e.getOldCursor())) {
            e.setCancelled(true);
            holder.card = e.getOldCursor().clone();
            plugin.debug().log("CARD", "Card drag-inserted: player=%s card=%s",p.getName(),selectedId(holder));
            updateCursorNextTick(p, null);
            inserted(p, holder);
            return;
        }
        e.setCancelled(true);
    }

    @EventHandler
    public void onClose(org.bukkit.event.inventory.InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder(false) instanceof MachineHolder holder)) return;
        if (!holder.transferring && e.getPlayer() instanceof Player p) {plugin.debug().log("GUI", "Closed %s: player=%s",holder.type,p.getName());returnCard(p, holder);}
    }

    // ------------------------------------------------------------------ actions

    // Creation uses a decimal keypad; amounts are stored as text to avoid floating-point input errors.
    private double cardDeposit() { return Math.max(0, round(cfg().getDouble("card.price", 2.50))); }
    private double maxCreationBalance() {
        double configured = cfg().getDouble("card.max-creation-balance", 500.0);
        return Double.isFinite(configured) ? Math.max(0, Math.min(500.0, round(configured))) : 500.0;
    }

    private ItemStack buttonOrDefault(String key, Material material, String name, String... lore) {
        return cfg().isConfigurationSection("buttons." + key) ? button(key) : simple(material, name, lore);
    }

    private double enteredAmount(MachineHolder holder) {
        try { return round(Double.parseDouble(holder.creationAmount)); }
        catch (NumberFormatException ex) { return 0; }
    }

    private int keypadSlot(String action, int fallback) {
        int slot = cfg().getInt("gui.numpad.slots." + action, fallback);
        return slot >= 0 && slot < 54 ? slot : fallback;
    }

    private int translateKeypadSlot(int physical) {
        for (int digit = 1; digit <= 9; digit++)
            if (physical == keypadSlot(Integer.toString(digit), KEYPAD_DIGITS[digit - 1])) return KEYPAD_DIGITS[digit - 1];
        if (physical == keypadSlot("0", SLOT_ZERO)) return SLOT_ZERO;
        if (physical == keypadSlot("decimal", SLOT_DECIMAL)) return SLOT_DECIMAL;
        if (physical == keypadSlot("clear", SLOT_CLEAR)) return SLOT_CLEAR;
        if (physical == keypadSlot("confirm", SLOT_CONFIRM_CREATE)) return SLOT_CONFIRM_CREATE;
        if (physical == keypadSlot("back", SLOT_CREATE_BACK)) return SLOT_CREATE_BACK;
        return -1;
    }

    // Optional custom item models for digits 0-9 and decimal on the 54-slot keypad.
    // Configure gui.numpad.items.<button>.item-model and optionally .material.
    // Clear can optionally use a custom item model; Confirm and Back remain empty clickable slots.
    // Custom buttons have no visible name, lore or tooltip; the artwork supplies labels.
    private ItemStack keypadButton(String key, ItemStack fallback) {
        String path = "gui.numpad.items." + key;
        String model = cfg().getString(path + ".item-model", "");
        if (model == null || model.isBlank()) return fallback;

        NamespacedKey modelKey = NamespacedKey.fromString(model.trim());
        if (modelKey == null) {
            plugin.getLogger().warning("Invalid keypad item model for '" + key + "': " + model);
            return fallback;
        }
        Material material = Material.matchMaterial(cfg().getString(path + ".material", "PAPER"));
        if (material == null || material.isAir()) material = Material.PAPER;
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return fallback;
        meta.setItemModel(modelKey);
        meta.displayName(Component.empty());
        meta.lore(List.of());
        meta.setHideTooltip(true);
        item.setItemMeta(meta);
        return item;
    }

    private void styleKeypad(MachineHolder holder) {
        Inventory inv = holder.inventory;
        Map<Integer, ItemStack> items = new HashMap<>();
        for (int digit = 1; digit <= 9; digit++)
            items.put(keypadSlot(Integer.toString(digit), KEYPAD_DIGITS[digit - 1]),
                    keypadButton(Integer.toString(digit), inv.getItem(KEYPAD_DIGITS[digit - 1])));
        items.put(keypadSlot("0", SLOT_ZERO), keypadButton("0", inv.getItem(SLOT_ZERO)));
        items.put(keypadSlot("decimal", SLOT_DECIMAL), keypadButton("decimal", inv.getItem(SLOT_DECIMAL)));
        // Confirm and Back remain invisible hitboxes. Clear is optional and defaults to invisible.
        String clearModel = cfg().getString("gui.numpad.items.clear.item-model", "");
        if (clearModel != null && !clearModel.isBlank())
            items.put(keypadSlot("clear", SLOT_CLEAR), keypadButton("clear", inv.getItem(SLOT_CLEAR)));
        // Their slot mappings and click actions remain active.
        for (int i : KEYPAD_DIGITS) inv.setItem(i, null);
        for (int i : new int[]{SLOT_ZERO, SLOT_DECIMAL, SLOT_CLEAR, SLOT_CONFIRM_CREATE, SLOT_CREATE_BACK}) inv.setItem(i, null);
        if (cfg().getBoolean("gui.numpad.show-items", true))
            items.forEach((index, item) -> { if (item != null) inv.setItem(index, item); });
    }

    // Preserve the live tooltip while using a transparent resource-pack item model.
    private ItemStack invisibleInfoItem(ItemStack original) {
        String path = "gui.numpad.info";
        String model = cfg().getString(path + ".item-model", "");
        if (model == null || model.isBlank()) return original;
        NamespacedKey key = NamespacedKey.fromString(model.trim());
        if (key == null) {
            plugin.getLogger().warning("Invalid numpad info item model: " + model);
            return original;
        }
        Material material = Material.matchMaterial(cfg().getString(path + ".material", "PAPER"));
        if (material == null || material.isAir()) material = Material.PAPER;
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        ItemMeta oldMeta = original.getItemMeta();
        if (meta == null || oldMeta == null) return original;
        meta.displayName(oldMeta.displayName());
        meta.lore(oldMeta.lore());
        meta.setItemModel(key);
        item.setItemMeta(meta);
        return item;
    }

    private void renderCreate(MachineHolder holder) {
        Inventory inv = holder.inventory;
        double preload = enteredAmount(holder);
        double total = round(preload + cardDeposit());
        inv.setItem(13, invisibleInfoItem(simple(Material.CLOCK, "<aqua>Initial balance: <white>" + money(preload),
                "<gray>Card deposit: <yellow>" + money(cardDeposit()),
                "<gray>Total to pay: <green>" + money(total),
                "<gray>Maximum balance: <white>" + money(maxCreationBalance()))));
        for (int digit = 1; digit <= 9; digit++) {
            inv.setItem(KEYPAD_DIGITS[digit - 1], simple(Material.LIGHT_GRAY_CONCRETE,
                    "<white><bold>" + digit));
        }
        inv.setItem(SLOT_ZERO, simple(Material.LIGHT_GRAY_CONCRETE, "<white><bold>0"));
        inv.setItem(SLOT_DECIMAL, simple(Material.YELLOW_CONCRETE, "<yellow>Decimal point (.)"));
        inv.setItem(SLOT_CLEAR, simple(Material.REDSTONE, "<red>Clear amount"));
        inv.setItem(SLOT_CONFIRM_CREATE, simple(Material.LIME_CONCRETE,
                "<green>Buy Tap2Go Card — " + money(total),
                "<gray>Initial balance: <white>" + money(preload),
                "<gray>Deposit: <yellow>" + money(cardDeposit()),
                "<yellow>Click to confirm purchase"));
        inv.setItem(SLOT_CREATE_BACK, button("back"));
        styleKeypad(holder);
    }

    private void handleCreateClick(Player p, MachineHolder holder, int slot) {
        if (slot == SLOT_CREATE_BACK) { navigate(p, holder, Type.MAIN, 0); return; }
        if (slot == SLOT_CONFIRM_CREATE) { buyCard(p, holder); return; }
        if (slot == SLOT_CLEAR) { holder.creationAmount = "0"; renderCreate(holder); return; }
        if (slot == SLOT_DECIMAL) {
            if (!holder.creationAmount.contains(".")) holder.creationAmount += ".";
            renderCreate(holder); return;
        }
        int digit = slot == SLOT_ZERO ? 0 : -1;
        if (digit == -1) {
            for (int i = 0; i < KEYPAD_DIGITS.length; i++)
                if (KEYPAD_DIGITS[i] == slot) { digit = i + 1; break; }
        }
        if (digit < 0) return;
        String input = holder.creationAmount;
        int dot = input.indexOf('.');
        if (dot >= 0 && input.length() - dot > 2) return; // at most two decimal places
        String candidate = input.equals("0") ? Integer.toString(digit) : input + digit;
        if (candidate.length() > 8) return;
        double amount;
        try { amount = Double.parseDouble(candidate); }
        catch (NumberFormatException ex) { return; }
        if (amount > maxCreationBalance()) {
            p.sendMessage(text("<red>Maximum initial card balance is " + money(maxCreationBalance()) + "."));
            return;
        }
        holder.creationAmount = candidate;
        renderCreate(holder);
    }

    private void buyCard(Player p, MachineHolder holder) {
        double preload = enteredAmount(holder);
        if (!Double.isFinite(preload) || preload < 0 || preload > maxCreationBalance()) {
            p.sendMessage(text("<red>Invalid initial card balance.")); return;
        }
        double total = round(cardDeposit() + preload);
        if (!plugin.economy().has(p, total)) {
            msg(p, "not-enough-money", "{amount}", money(total)); return;
        }
        plugin.debug().log("ECONOMY", "Card purchase requested: player=%s preload=%.2f deposit=%.2f",p.getName(),preload,cardDeposit());
        EconomyResponse r = plugin.economy().withdrawPlayer(p, total);
        if (!r.transactionSuccess()) {
            msg(p, "not-enough-money", "{amount}", money(total)); return;
        }
        String id;
        try {
            id = plugin.db().createCard(p.getUniqueId(), preload);
        } catch (SQLException ex) {
            plugin.getLogger().severe("Database error while buying card: " + ex.getMessage());
            EconomyResponse reversal = plugin.economy().depositPlayer(p, total);
            if (!reversal.transactionSuccess()) plugin.getLogger().severe("MANUAL REPAYMENT REQUIRED: " + p.getUniqueId() + " amount " + total);
            msg(p, "error"); return;
        }
        plugin.debug().log("ECONOMY", "Card purchase completed: player=%s card=%s charged=%.2f",p.getName(),id,total);
        ItemStack card = createCardItem(id);
        p.getInventory().addItem(card).values()
                .forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
        msg(p, "bought", "{id}", id, "{balance}", money(preload));
        navigate(p, holder, Type.MAIN, 0);
    }

    private void checkBalance(Player p, MachineHolder holder) {
        String id = selectedId(holder);
        try {
            Double balance = plugin.db().getBalance(id);
            if (balance == null) { msg(p, "invalid-card"); return; }
            plugin.debug().log("CARD", "Balance checked: player=%s card=%s balance=%.2f",p.getName(),id,balance);
            msg(p, "balance", "{id}", id, "{balance}", money(balance));
        } catch (SQLException ex) { msg(p, "error"); plugin.getLogger().severe(ex.getMessage()); }
    }

    private double maxTopupAmount(MachineHolder holder) {
        double max = cfg().getDouble("max-balance", 1000.0);
        if (!Double.isFinite(max) || max <= 0) return 999999.99;
        try {
            Double balance = plugin.db().getBalance(selectedId(holder));
            return balance == null ? 0 : Math.max(0, round(max - balance));
        } catch (SQLException ex) {
            plugin.getLogger().severe("Could not read card balance for keypad: " + ex);
            return 0;
        }
    }

    private void renderTopup(MachineHolder holder) {
        Inventory inv = holder.inventory;
        double amount = enteredAmount(holder);
        double current = 0;
        try {
            Double b = plugin.db().getBalance(selectedId(holder));
            if (b != null) current = b;
        } catch (SQLException ex) {
            plugin.getLogger().severe("Could not read balance: " + ex);
        }
        inv.setItem(13, invisibleInfoItem(simple(Material.CLOCK, "<aqua>Top-up amount: <white>" + money(amount),
                "<gray>Current balance: <white>" + money(current),
                "<gray>Balance after top-up: <green>" + money(round(current + amount)),
                "<gray>Maximum card balance: <yellow>" + money(cfg().getDouble("max-balance", 1000)))));
        for (int digit = 1; digit <= 9; digit++)
            inv.setItem(KEYPAD_DIGITS[digit - 1], simple(Material.LIGHT_GRAY_CONCRETE,
                    "<white><bold>" + digit));
        inv.setItem(SLOT_ZERO, simple(Material.LIGHT_GRAY_CONCRETE, "<white><bold>0"));
        inv.setItem(SLOT_DECIMAL, simple(Material.YELLOW_CONCRETE, "<yellow>Decimal point (.)"));
        inv.setItem(SLOT_CLEAR, simple(Material.REDSTONE, "<red>Clear amount"));
        inv.setItem(SLOT_CONFIRM_CREATE, simple(Material.LIME_CONCRETE,
                "<green>Confirm Top Up — " + money(amount),
                "<gray>Add this amount to the inserted Tap2Go card",
                "<yellow>Click to confirm payment"));
        inv.setItem(SLOT_CREATE_BACK, button("back"));
        styleKeypad(holder);
    }

    private void handleTopupClick(Player p, MachineHolder holder, int slot) {
        if (slot == SLOT_CREATE_BACK) { navigate(p, holder, Type.MAIN, 0); return; }
        if (slot == SLOT_CONFIRM_CREATE) { topUp(p, holder); return; }
        if (slot == SLOT_CLEAR) { holder.creationAmount = "0"; renderTopup(holder); return; }
        if (slot == SLOT_DECIMAL) {
            if (!holder.creationAmount.contains(".")) holder.creationAmount += ".";
            renderTopup(holder); return;
        }
        int digit = slot == SLOT_ZERO ? 0 : -1;
        if (digit == -1) {
            for (int i = 0; i < KEYPAD_DIGITS.length; i++)
                if (KEYPAD_DIGITS[i] == slot) { digit = i + 1; break; }
        }
        if (digit < 0) return;
        String input = holder.creationAmount;
        int dot = input.indexOf('.');
        if (dot >= 0 && input.length() - dot > 2) return;
        String candidate = input.equals("0") ? Integer.toString(digit) : input + digit;
        if (candidate.length() > 8) return;
        double amount;
        try { amount = Double.parseDouble(candidate); }
        catch (NumberFormatException ex) { return; }
        if (amount > maxTopupAmount(holder)) {
            p.sendMessage(text("<red>Maximum additional top-up is " + money(maxTopupAmount(holder)) + "."));
            return;
        }
        holder.creationAmount = candidate;
        renderTopup(holder);
    }

    private void topUp(Player p, MachineHolder holder) {
        double amount = enteredAmount(holder);
        if (!Double.isFinite(amount) || amount <= 0) {
            p.sendMessage(text("<red>Enter a top-up amount greater than zero."));
            return;
        }
        String id = selectedId(holder);
        if (id == null) { msg(p, "no-card"); return; }
        try {
            Double current = plugin.db().getBalance(id);
            if (current == null) { msg(p, "invalid-card"); return; }
            double max = cfg().getDouble("max-balance", 1000.0);
            if (max > 0 && round(current + amount) > max) {
                msg(p, "max-balance", "{max}", money(max)); return;
            }
            if (!plugin.economy().has(p, amount)) {
                msg(p, "not-enough-money", "{amount}", money(amount)); return;
            }
            plugin.debug().log("ECONOMY", "Top-up payment requested: player=%s card=%s amount=%.2f",p.getName(),id,amount);
            EconomyResponse response = plugin.economy().withdrawPlayer(p, amount);
            if (!response.transactionSuccess()) {
                msg(p, "not-enough-money", "{amount}", money(amount)); return;
            }
            try {
                if (!plugin.db().addBalance(id, amount)) {
                    EconomyResponse reversal = plugin.economy().depositPlayer(p, amount);
                    if (!reversal.transactionSuccess()) plugin.getLogger().severe("MANUAL REPAYMENT REQUIRED: " + p.getUniqueId() + " amount " + amount);
                    msg(p, "invalid-card"); return;
                }
            } catch (SQLException ex) {
                EconomyResponse reversal = plugin.economy().depositPlayer(p, amount);
                if (!reversal.transactionSuccess()) plugin.getLogger().severe("MANUAL REPAYMENT REQUIRED: " + p.getUniqueId() + " amount " + amount);
                throw ex;
            }
            plugin.debug().log("ECONOMY", "Top-up completed: player=%s card=%s amount=%.2f",p.getName(),id,amount);
            msg(p, "topped-up", "{amount}", money(amount), "{balance}", money(round(current + amount)));
            navigate(p, holder, Type.MAIN, 0);
        } catch (SQLException ex) {
            plugin.getLogger().severe("Top-up error: " + ex);
            msg(p, "error");
        }
    }

    private void refund(Player p, MachineHolder holder) {
        String id = selectedId(holder);
        try {
            if (plugin.db().activeJourney(id) != null) {
                p.sendMessage(text("<red>Finish your journey before refunding this card.")); return;
            }
            Double balance = plugin.db().closeCard(id);
            if (balance == null) { msg(p, "invalid-card"); return; }
            plugin.debug().log("CARD", "Refund approved: player=%s card=%s balance=%.2f",p.getName(),id,balance);
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
