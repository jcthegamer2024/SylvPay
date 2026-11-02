package org.sylvarion.sylvpay;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.IOException;
import java.util.List;
import org.bukkit.command.CommandSender;

public final class SylvPayMain extends JavaPlugin {
    private Economy economy;
    private CardDatabase database;
    private NamespacedKey cardKey;
    private MachineGui gui;
    private StationManager stations;
    private FareManager fares;
    private NexoGateBridge nexo;
    private FareGateManager fareGates;
    private final MiniMessage mm=MiniMessage.miniMessage();

    @Override public void onEnable(){
        saveDefaultConfig();
        RegisteredServiceProvider<Economy> rsp=getServer().getServicesManager().getRegistration(Economy.class);
        if(rsp==null){getLogger().severe("No Vault economy found; enable CMI/Vault economy.");getServer().getPluginManager().disablePlugin(this);return;}
        economy=rsp.getProvider();
        cardKey=new NamespacedKey(this,"card_id");
        database=new CardDatabase(this);
        try{database.init();}catch(Exception ex){getLogger().severe("Database init failed: "+ex.getMessage());getServer().getPluginManager().disablePlugin(this);return;}
        gui=new MachineGui(this);
        getServer().getPluginManager().registerEvents(gui,this);
        stations=new StationManager(this);
        fares=new FareManager(this);
        nexo=new NexoGateBridge(this);
        fareGates=new FareGateManager(this,stations,fares,nexo);
        getServer().getPluginManager().registerEvents(fareGates,this);
        getServer().getScheduler().runTask(this, () -> {
            if(!nexo.register(fareGates))getLogger().warning("Nexo integration unavailable; fare gates disabled.");
        });
        registerPermissions();registerCommands();
        getLogger().info("SylvPay enabled using "+economy.getName());
    }
    @Override public void onDisable(){if(fareGates!=null)fareGates.shutdown();}
    public Economy economy(){return economy;}
    public CardDatabase db(){return database;}
    public NamespacedKey cardKey(){return cardKey;}
    public StationManager stations(){return stations;}
    private void registerPermissions(){
        var pm=getServer().getPluginManager();
        if(pm.getPermission("sylvpay.use")==null)pm.addPermission(new Permission("sylvpay.use","Use SylvPay",PermissionDefault.TRUE));
        if(pm.getPermission("sylvpay.admin")==null)pm.addPermission(new Permission("sylvpay.admin","Configure SylvPay",PermissionDefault.OP));
    }
    private void feedback(Player p,String text){p.sendMessage(mm.deserialize(text));}
    private ItemDisplay target(Player p){
        ItemDisplay d=nexo.target(p);
        if(d==null){feedback(p,"<red>Look at a Nexo fare gate within 6 blocks.");return null;}
        String expected=getConfig().getString("faregates.models.closed","sylv_faregate_closed");
        String actual=nexo.furnitureId(d);
        if(!expected.equals(actual)){feedback(p,"<red>Furniture must have Nexo ID <white>"+expected);return null;}
        return d;
    }
    private void setGate(Player p,String station,String direction){
        if(!stations.hasStation(station)){feedback(p,"<red>Unknown station: <white>"+station);return;}
        ItemDisplay d=target(p);if(d==null)return;
        try{stations.assign(d,station,direction);feedback(p,"<green>Gate #"+stations.gateNumber(d)+" assigned: <gold>"+stations.stationName(station)+" <gray>("+direction+")");}
        catch(IOException ex){getLogger().severe("Cannot save gates.yml: "+ex.getMessage());feedback(p,"<red>Cannot save gates.yml.");}
    }
    private void removeGate(Player p){
        ItemDisplay d=target(p);if(d==null)return;
        try{stations.unassign(d);feedback(p,"<green>Gate assignment removed.");}
        catch(IOException ex){getLogger().severe("Cannot save gates.yml: "+ex.getMessage());feedback(p,"<red>Cannot save gates.yml.");}
    }
    private void listStations(CommandSender sender) {
        sender.sendMessage("§6SylvPay — Stations with registered gates:");
        int total = 0;
        for (String station : stations.stationIds()) {
            long count = stations.registeredGates().stream().filter(g -> g.gate().station().equals(station)).count();
            if (count == 0) continue;
            total++;
            sender.sendMessage("§e" + stations.stationName(station) + " §7(" + station + ") §f— " + count
                    + " gate(s) §8(/sylvpay gates " + station + ")");
        }
        if (total == 0) sender.sendMessage("§7No stations have registered gates yet.");
    }

    private void listGates(CommandSender sender, String station) {
        if (!stations.hasStation(station)) {
            sender.sendMessage("§cUnknown station: " + station);
            return;
        }
        List<StationManager.RegisteredGate> gates = stations.registeredGates().stream()
                .filter(g -> g.gate().station().equals(station)).toList();
        sender.sendMessage("§6" + stations.stationName(station) + " §7— " + gates.size() + " gate(s):");
        for (StationManager.RegisteredGate gate : gates) {
            String coordinates = gate.world().equals("unknown") ? "location unknown"
                    : gate.world() + " " + (int)Math.floor(gate.x()) + ", "
                    + (int)Math.floor(gate.y()) + ", " + (int)Math.floor(gate.z());
            sender.sendMessage("§e#" + gate.id() + " §f" + gate.gate().direction()
                    + " §7— " + coordinates);
        }
        if (gates.isEmpty()) sender.sendMessage("§7No gates registered at this station.");
    }

    private void deleteGate(CommandSender sender, int id) {
        if (id <= 0) { sender.sendMessage("§cGate ID must be positive."); return; }
        try {
            if (stations.delete(id)) sender.sendMessage("§aGate #" + id + " unregistered. Furniture was left in place.");
            else sender.sendMessage("§cNo registered gate with ID #" + id + ".");
        } catch (IOException ex) {
            getLogger().severe("Cannot save gates.yml: " + ex.getMessage());
            sender.sendMessage("§cCould not save gates.yml. Gate was not deleted.");
        }
    }

    private void registerCommands(){
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,event->{
            event.registrar().register(Commands.literal("sylvpay")
                    .executes(ctx->{ctx.getSource().getSender().sendMessage("/sylvpay machine | reload | stations | gates <station> | gate set/remove/delete");return Command.SINGLE_SUCCESS;})
                    .then(Commands.literal("machine")
                            .requires(src->src.getSender() instanceof Player && src.getSender().hasPermission("sylvpay.use"))
                            .executes(ctx->{gui.openMain((Player)ctx.getSource().getSender());return Command.SINGLE_SUCCESS;}))
                    .then(Commands.literal("reload")
                            .requires(src->src.getSender().hasPermission("sylvpay.admin"))
                            .executes(ctx->{reloadConfig();stations.reload();fares.reload();ctx.getSource().getSender().sendMessage("SylvPay config, stations and fares reloaded.");return Command.SINGLE_SUCCESS;}))
                    .then(Commands.literal("stations")
                            .requires(src->src.getSender().hasPermission("sylvpay.admin"))
                            .executes(ctx->{listStations(ctx.getSource().getSender());return Command.SINGLE_SUCCESS;}))
                    .then(Commands.literal("gates")
                            .requires(src->src.getSender().hasPermission("sylvpay.admin"))
                            .then(Commands.argument("station",StringArgumentType.word())
                                    .executes(ctx->{listGates(ctx.getSource().getSender(),StringArgumentType.getString(ctx,"station"));return Command.SINGLE_SUCCESS;})))
                    .then(Commands.literal("gate")
                            .requires(src->src.getSender() instanceof Player && src.getSender().hasPermission("sylvpay.admin"))
                            .then(Commands.literal("set")
                                    .then(Commands.argument("station",StringArgumentType.word())
                                            .then(Commands.literal("entrance").executes(ctx->{setGate((Player)ctx.getSource().getSender(),StringArgumentType.getString(ctx,"station"),"entrance");return Command.SINGLE_SUCCESS;}))
                                            .then(Commands.literal("exit").executes(ctx->{setGate((Player)ctx.getSource().getSender(),StringArgumentType.getString(ctx,"station"),"exit");return Command.SINGLE_SUCCESS;}))))
                            .then(Commands.literal("remove").executes(ctx->{removeGate((Player)ctx.getSource().getSender());return Command.SINGLE_SUCCESS;}))
                            .then(Commands.literal("delete")
                                    .then(Commands.argument("id",IntegerArgumentType.integer(1))
                                            .executes(ctx->{deleteGate(ctx.getSource().getSender(),IntegerArgumentType.getInteger(ctx,"id"));return Command.SINGLE_SUCCESS;}))))
                    .build(),"SylvPay fare gates and ticket machines");
        });
    }
}
