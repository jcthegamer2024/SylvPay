package org.sylvarion.sylvpay;

import org.bukkit.plugin.java.JavaPlugin;
import java.io.File;
import java.sql.*;
import java.util.UUID;

/** SQLite card balances and persistent journeys. All state-changing operations are synchronized. */
public final class CardDatabase {
    public record Journey(String origin, long startedAt) {}
    public enum TapStatus { OK, INVALID_CARD, ALREADY_IN, NO_JOURNEY, INSUFFICIENT, EXPIRED }
    public record TapResult(TapStatus status, String origin, double fare, double balance) {}
    private final String url;
    public CardDatabase(JavaPlugin plugin) {
        plugin.getDataFolder().mkdirs();
        url = "jdbc:sqlite:" + new File(plugin.getDataFolder(), "cards.db").getAbsolutePath();
    }
    private Connection connect() throws SQLException { return DriverManager.getConnection(url); }
    public void init() throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS cards (id TEXT PRIMARY KEY, owner_uuid TEXT NOT NULL, balance REAL NOT NULL, created_at INTEGER NOT NULL, active INTEGER NOT NULL DEFAULT 1)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS journeys (card_id TEXT PRIMARY KEY, origin TEXT NOT NULL, started_at INTEGER NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS journey_history (id INTEGER PRIMARY KEY AUTOINCREMENT, card_id TEXT NOT NULL, origin TEXT NOT NULL, destination TEXT NOT NULL, started_at INTEGER NOT NULL, ended_at INTEGER NOT NULL, fare REAL NOT NULL)");
        }
    }
    public synchronized String createCard(UUID owner, double balance) throws SQLException {
        for (int attempt = 0; attempt < 10; attempt++) {
            String id = "SP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
            try (Connection c = connect(); PreparedStatement ps = c.prepareStatement("INSERT OR IGNORE INTO cards (id,owner_uuid,balance,created_at) VALUES (?,?,?,?)")) {
                ps.setString(1,id); ps.setString(2,owner.toString()); ps.setDouble(3,balance); ps.setLong(4,System.currentTimeMillis());
                if (ps.executeUpdate() == 1) return id;
            }
        }
        throw new SQLException("Unable to allocate card ID");
    }
    public synchronized Double getBalance(String id) throws SQLException {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement("SELECT balance FROM cards WHERE id=? AND active=1")) {
            ps.setString(1,id);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getDouble(1) : null; }
        }
    }
    public synchronized boolean addBalance(String id, double amount) throws SQLException {
        if (!Double.isFinite(amount) || amount <= 0) return false;
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement("UPDATE cards SET balance=ROUND(balance+?,2) WHERE id=? AND active=1")) {
            ps.setDouble(1,amount); ps.setString(2,id); return ps.executeUpdate()==1;
        }
    }
    /** Refunding an active journey's card is disallowed, preventing stranded journeys. */
    public synchronized Double closeCard(String id) throws SQLException {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                if (readJourney(c,id)!=null) { c.rollback(); return null; }
                Double balance = readBalance(c,id);
                if (balance==null) { c.rollback(); return null; }
                try (PreparedStatement ps=c.prepareStatement("UPDATE cards SET active=0,balance=0 WHERE id=? AND active=1")) {
                    ps.setString(1,id); if(ps.executeUpdate()!=1) { c.rollback(); return null; }
                }
                c.commit(); return balance;
            } catch(SQLException e) { c.rollback(); throw e; }
        }
    }
    private Double readBalance(Connection c,String id) throws SQLException {
        try (PreparedStatement ps=c.prepareStatement("SELECT balance FROM cards WHERE id=? AND active=1")) {
            ps.setString(1,id); try(ResultSet rs=ps.executeQuery()){return rs.next()?rs.getDouble(1):null;}
        }
    }
    private Journey readJourney(Connection c,String id) throws SQLException {
        try (PreparedStatement ps=c.prepareStatement("SELECT origin,started_at FROM journeys WHERE card_id=?")) {
            ps.setString(1,id); try(ResultSet rs=ps.executeQuery()){return rs.next()?new Journey(rs.getString(1),rs.getLong(2)):null;}
        }
    }
    public synchronized TapResult tapIn(String id,String station,double minimum) throws SQLException {
        try(Connection c=connect()) {
            c.setAutoCommit(false);
            try {
                Double balance=readBalance(c,id);
                if(balance==null){c.rollback();return new TapResult(TapStatus.INVALID_CARD,null,0,0);}
                if(readJourney(c,id)!=null){c.rollback();return new TapResult(TapStatus.ALREADY_IN,null,0,balance);}
                if(balance+0.00001<minimum){c.rollback();return new TapResult(TapStatus.INSUFFICIENT,null,0,balance);}
                try(PreparedStatement ps=c.prepareStatement("INSERT INTO journeys(card_id,origin,started_at) VALUES(?,?,?)")){
                    ps.setString(1,id);ps.setString(2,station);ps.setLong(3,System.currentTimeMillis());ps.executeUpdate();
                }
                c.commit();return new TapResult(TapStatus.OK,station,0,balance);
            } catch(SQLException e){c.rollback();throw e;}
        }
    }
    public synchronized TapResult tapOut(String id,String destination,double fare,long maxMillis) throws SQLException {
        try(Connection c=connect()){
            c.setAutoCommit(false);
            try{
                Double balance=readBalance(c,id);
                if(balance==null){c.rollback();return new TapResult(TapStatus.INVALID_CARD,null,0,0);}
                Journey journey=readJourney(c,id);
                if(journey==null){c.rollback();return new TapResult(TapStatus.NO_JOURNEY,null,0,balance);}
                if(System.currentTimeMillis()-journey.startedAt()>maxMillis){c.rollback();return new TapResult(TapStatus.EXPIRED,journey.origin(),0,balance);}
                if(balance+0.00001<fare){c.rollback();return new TapResult(TapStatus.INSUFFICIENT,journey.origin(),fare,balance);}
                try(PreparedStatement ps=c.prepareStatement("UPDATE cards SET balance=ROUND(balance-?,2) WHERE id=? AND active=1 AND balance>=?")){
                    ps.setDouble(1,fare);ps.setString(2,id);ps.setDouble(3,fare-0.00001);
                    if(ps.executeUpdate()!=1){c.rollback();return new TapResult(TapStatus.INSUFFICIENT,journey.origin(),fare,balance);}
                }
                try(PreparedStatement ps=c.prepareStatement("INSERT INTO journey_history(card_id,origin,destination,started_at,ended_at,fare) VALUES(?,?,?,?,?,?)")){
                    ps.setString(1,id);ps.setString(2,journey.origin());ps.setString(3,destination);
                    ps.setLong(4,journey.startedAt());ps.setLong(5,System.currentTimeMillis());ps.setDouble(6,fare);ps.executeUpdate();
                }
                try(PreparedStatement ps=c.prepareStatement("DELETE FROM journeys WHERE card_id=?")){
                    ps.setString(1,id);ps.executeUpdate();
                }
                c.commit();return new TapResult(TapStatus.OK,journey.origin(),fare,Math.round((balance-fare)*100.0)/100.0);
            }catch(SQLException e){c.rollback();throw e;}
        }
    }
    public synchronized Journey activeJourney(String id) throws SQLException {
        try(Connection c=connect()){return readJourney(c,id);}
    }
}
