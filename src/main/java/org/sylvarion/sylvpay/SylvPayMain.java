package org.sylvarion.sylvpay;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
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
    private void registerPermissions(){
        var pm=getServer().getPluginManager();
        if(pm.getPermission("sylvpay.use")==null)pm.addPermission(new Permission("sylvpay.use","Use SylvPay",PermissionDefault.TRUE));
        if(pm.getPermission("sylvpay.admin")==null)pm.addPermission(new Permission("sylvpay.admin","Configure SylvPay",PermissionDefault.OP));
    }
    private void feedback(Player p,String text){p.sendMessage(mm.deserialize(text));}
    private ItemDisplay target(Player p){
        ItemDisplay d=nexo.target(p);
        if(d==null){feedback(p,"<red>Look at a Nexo fare gate within 6 blocks.");return null;}
        String expected=getConfig().getString("faregates.models.closed","");
        String actual=nexo.furnitureId(d);
        if(!expected.equals(actual)){feedback(p,"<red>Furniture must have Nexo ID <white>"+expected);return null;}
        return d;
    }
    private void setGate(Player p,String station,String direction){
        if(!stations.hasStation(station)){feedback(p,"<red>Unknown station: <white>"+station);return;}
        ItemDisplay d=target(p);if(d==null)return;
        try{stations.assign(d,station,direction);feedback(p,"<green>Gate assigned: <gold>"+stations.stationName(station)+" <gray>("+direction+")");}
        catch(IOException ex){getLogger().severe("Cannot save gates.yml: "+ex.getMessage());feedback(p,"<red>Cannot save gates.yml.");}
    }
    private void removeGate(Player p){
        ItemDisplay d=target(p);if(d==null)return;
        try{stations.unassign(d);feedback(p,"<green>Gate assignment removed.");}
        catch(IOException ex){getLogger().severe("Cannot save gates.yml: "+ex.getMessage());feedback(p,"<red>Cannot save gates.yml.");}
    }
    private void registerCommands(){
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,event->{
            event.registrar().register(Commands.literal("sylvpay")
                    .executes(ctx->{ctx.getSource().getSender().sendMessage("/sylvpay machine | reload | gate set <station> <entrance|exit> | gate remove");return Command.SINGLE_SUCCESS;})
                    .then(Commands.literal("machine")
                            .requires(src->src.getSender() instanceof Player && src.getSender().hasPermission("sylvpay.use"))
                            .executes(ctx->{gui.openMain((Player)ctx.getSource().getSender());return Command.SINGLE_SUCCESS;}))
                    .then(Commands.literal("reload")
                            .requires(src->src.getSender().hasPermission("sylvpay.admin"))
                            .executes(ctx->{reloadConfig();stations.reload();fares.reload();ctx.getSource().getSender().sendMessage("SylvPay config, stations and fares reloaded.");return Command.SINGLE_SUCCESS;}))
                    .then(Commands.literal("gate")
                            .requires(src->src.getSender() instanceof Player && src.getSender().hasPermission("sylvpay.admin"))
                            .then(Commands.literal("set")
                                    .then(Commands.argument("station",StringArgumentType.word())
                                            .then(Commands.literal("entrance").executes(ctx->{setGate((Player)ctx.getSource().getSender(),StringArgumentType.getString(ctx,"station"),"entrance");return Command.SINGLE_SUCCESS;}))
                                            .then(Commands.literal("exit").executes(ctx->{setGate((Player)ctx.getSource().getSender(),StringArgumentType.getString(ctx,"station"),"exit");return Command.SINGLE_SUCCESS;}))))
                            .then(Commands.literal("remove").executes(ctx->{removeGate((Player)ctx.getSource().getSender());return Command.SINGLE_SUCCESS;})))
                    .build(),"SylvPay fare gates and ticket machines");
        });
    }
}
