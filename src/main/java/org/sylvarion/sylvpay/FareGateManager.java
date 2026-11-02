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

/** One rider at a time per gate. Visual opening only; Nexo hitboxes must be passable. */
public final class FareGateManager implements Listener {
    private record OpenGate(ItemDisplay entity, ItemStack original, UUID rider, boolean near) {}
    private final SylvPayMain plugin;
    private final StationManager stations;
    private final FareManager fares;
    private final NexoGateBridge nexo;
    private final Map<UUID,OpenGate> open=new HashMap<>();
    private final Map<UUID,Long> lastTap=new HashMap<>();
    private final MiniMessage mm=MiniMessage.miniMessage();

    public FareGateManager(SylvPayMain plugin,StationManager stations,FareManager fares,NexoGateBridge nexo){
        this.plugin=plugin;this.stations=stations;this.fares=fares;this.nexo=nexo;
    }
    public boolean tap(Player player,ItemDisplay gate){
        if(!plugin.getConfig().getBoolean("faregates.enabled",false))return false;
        StationManager.Gate assigned=stations.gate(gate);
        if(assigned==null)return false;
        String station=assigned.station();
        if(open.containsKey(gate.getUniqueId())){message(player,"busy",station,null,0,0);return true;}
        ItemStack held=player.getInventory().getItemInMainHand();
        String card=held.hasItemMeta()?held.getItemMeta().getPersistentDataContainer().get(plugin.cardKey(),PersistentDataType.STRING):null;
        if(card==null){message(player,"no-card",station,null,0,0);return true;}
        long now=System.currentTimeMillis();
        if(now-lastTap.getOrDefault(player.getUniqueId(),0L)<plugin.getConfig().getLong("faregates.tap-cooldown-ms",1500))return true;
        lastTap.put(player.getUniqueId(),now);
        String openId=plugin.getConfig().getString("faregates.models.open","");
        ItemStack openVisual=nexo.item(openId);
        if(openVisual==null){message(player,"model-missing",station,null,0,0);return true;}
        try{
            CardDatabase.TapResult result;
            if(assigned.direction().equals("entrance")){
                result=plugin.db().tapIn(card,station,fares.minimumEntryBalance());
            }else{
                CardDatabase.Journey journey=plugin.db().activeJourney(card);
                if(journey==null){
                    if(plugin.db().getBalance(card)==null)message(player,"invalid-card",station,null,0,0);
                    else message(player,"no-journey",station,null,0,0);
                    return true;
                }
                if(!stations.hasStation(journey.origin())){message(player,"error",station,null,0,0);return true;}
                double fare=fares.fare(journey.origin(),station);
                result=plugin.db().tapOut(card,station,fare,fares.maxJourneyMillis());
            }
            if(result.status()!=CardDatabase.TapStatus.OK){
                String key=switch(result.status()){
                    case INVALID_CARD->"invalid-card";
                    case ALREADY_IN->"already-in";
                    case NO_JOURNEY->"no-journey";
                    case INSUFFICIENT->"insufficient-balance";
                    case EXPIRED->"journey-expired";
                    default->"error";
                };
                message(player,key,station,result.origin(),result.fare(),result.balance());return true;
            }
            ItemStack original=gate.getItemStack().clone();
            gate.setItemStack(openVisual);
            UUID id=gate.getUniqueId();
            open.put(id,new OpenGate(gate,original,player.getUniqueId(),false));
            long ticks=Math.max(20L,plugin.getConfig().getLong("faregates.opening.timeout-ticks",100));
            Bukkit.getScheduler().runTaskLater(plugin,()->close(id),ticks);
            message(player,assigned.direction().equals("entrance")?"tap-in":"tap-out",station,result.origin(),result.fare(),result.balance());
            return true;
        }catch(SQLException|IllegalArgumentException ex){
            plugin.getLogger().severe("Fare gate transaction failed: "+ex.getMessage());
            message(player,"error",station,null,0,0);return true;
        }
    }
    @EventHandler
    public void onMove(PlayerMoveEvent event){
        if(event.getTo()==null || (event.getFrom().getX()==event.getTo().getX() && event.getFrom().getY()==event.getTo().getY() && event.getFrom().getZ()==event.getTo().getZ()))return;
        Player player=event.getPlayer();
        double radius=Math.max(0.5,plugin.getConfig().getDouble("faregates.opening.crossing-radius",1.5));
        for(var e:new HashMap<>(open).entrySet()){
            OpenGate state=e.getValue();
            if(!state.rider().equals(player.getUniqueId())||!state.entity().isValid())continue;
            if(!state.entity().getWorld().equals(player.getWorld()))continue;
            boolean near=state.entity().getLocation().distanceSquared(player.getLocation())<=radius*radius;
            if(near&&!state.near())open.put(e.getKey(),new OpenGate(state.entity(),state.original(),state.rider(),true));
            else if(!near&&state.near())close(e.getKey());
        }
    }
    public void close(UUID id){OpenGate state=open.remove(id);if(state!=null&&state.entity().isValid())state.entity().setItemStack(state.original());}
    public void shutdown(){for(UUID id:new ArrayList<>(open.keySet()))close(id);}
    private void message(Player player,String key,String station,String origin,double fare,double balance){
        String template=plugin.getConfig().getString("faregates.messages."+key,"<red>Fare gate error.");
        String formatted=String.format(java.util.Locale.UK,"£%.2f",fare);
        String money=String.format(java.util.Locale.UK,"£%.2f",balance);
        player.sendMessage(mm.deserialize(template.replace("{station}",stations.stationName(station))
            .replace("{origin}",origin==null?"":stations.stationName(origin))
            .replace("{fare}",formatted).replace("{balance}",money)));
    }
}
