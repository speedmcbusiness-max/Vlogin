package dev.vlogin;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public final class Database {

    public record Account(String name, String hash, String lastIp, long lastLogin, boolean verified,
                          boolean premium, String premiumUuid, String youtube, String tiktok, String instagram) {
        public String social(String platform) {
            return switch (platform) {
                case "youtube" -> youtube;
                case "tiktok" -> tiktok;
                case "instagram" -> instagram;
                default -> null;
            };
        }
    }

    /** One Minecraft account <-> one Discord user. An account can have several, and a Discord user can have several accounts. */
    public record Link(String name, String discordId, String discordName) {}

    private final Connection c;

    public Database(File file) throws Exception {
        file.getParentFile().mkdirs();
        Class.forName("org.sqlite.JDBC");
        c = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS accounts (" +
                    "name TEXT PRIMARY KEY, hash TEXT NOT NULL, last_ip TEXT, last_login INTEGER DEFAULT 0, " +
                    "verified INTEGER DEFAULT 0, premium INTEGER DEFAULT 0, premium_uuid TEXT, " +
                    "youtube TEXT, tiktok TEXT, instagram TEXT)");
            s.execute("CREATE TABLE IF NOT EXISTS links (" +
                    "name TEXT NOT NULL, discord_id TEXT NOT NULL, discord_name TEXT, PRIMARY KEY(name, discord_id))");
        }
        // upgrade databases created by older versions (errors = column already exists)
        for (String ddl : new String[]{
                "ALTER TABLE accounts ADD COLUMN premium INTEGER DEFAULT 0",
                "ALTER TABLE accounts ADD COLUMN premium_uuid TEXT",
                "ALTER TABLE accounts ADD COLUMN youtube TEXT",
                "ALTER TABLE accounts ADD COLUMN tiktok TEXT",
                "ALTER TABLE accounts ADD COLUMN instagram TEXT"}) {
            try (Statement s = c.createStatement()) { s.execute(ddl); } catch (SQLException ignored) {}
        }
        // move the old single discord_id column into the links table (errors = column does not exist)
        try (Statement s = c.createStatement()) {
            s.execute("INSERT OR IGNORE INTO links(name, discord_id, discord_name) " +
                    "SELECT name, discord_id, '' FROM accounts WHERE discord_id IS NOT NULL");
            s.execute("UPDATE accounts SET discord_id=NULL");
        } catch (SQLException ignored) {}
    }

    private Account map(ResultSet r) throws SQLException {
        return new Account(r.getString("name"), r.getString("hash"), r.getString("last_ip"),
                r.getLong("last_login"), r.getInt("verified") == 1, r.getInt("premium") == 1,
                r.getString("premium_uuid"), r.getString("youtube"), r.getString("tiktok"), r.getString("instagram"));
    }

    // ---------- accounts ----------

    public synchronized Account get(String name) {
        try (PreparedStatement p = c.prepareStatement("SELECT * FROM accounts WHERE name=?")) {
            p.setString(1, name);
            ResultSet r = p.executeQuery();
            return r.next() ? map(r) : null;
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public synchronized boolean create(String name, String hash, String ip) {
        try (PreparedStatement p = c.prepareStatement(
                "INSERT INTO accounts(name,hash,last_ip,last_login) VALUES(?,?,?,?)")) {
            p.setString(1, name); p.setString(2, hash); p.setString(3, ip);
            p.setLong(4, System.currentTimeMillis());
            p.executeUpdate();
            return true;
        } catch (SQLException e) { return false; } // already exists
    }

    public synchronized void setLogin(String name, String ip) {
        run("UPDATE accounts SET last_ip=?, last_login=? WHERE name=?", ip, System.currentTimeMillis(), name);
    }

    /** Sets a new hash and wipes the saved IP/session so the next join needs the new password. */
    public synchronized void setHash(String name, String hash) {
        run("UPDATE accounts SET hash=?, last_ip=NULL, last_login=0 WHERE name=?", hash, name);
    }

    /** Forgets the saved IP/session (forces a password login on the next join). */
    public synchronized void clearSession(String name) {
        run("UPDATE accounts SET last_ip=NULL, last_login=0 WHERE name=?", name);
    }

    public synchronized void setPremium(String name, boolean on) {
        run("UPDATE accounts SET premium=?, premium_uuid=NULL WHERE name=?", on ? 1 : 0, name);
    }

    public synchronized void setPremiumUuid(String name, String uuid) {
        run("UPDATE accounts SET premium_uuid=? WHERE name=?", uuid, name);
    }

    public synchronized List<String> premiumNames() {
        List<String> out = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT name FROM accounts WHERE premium=1")) {
            while (r.next()) out.add(r.getString(1));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    /** platform must be youtube, tiktok or instagram; url null clears it. */
    public synchronized void setSocial(String name, String platform, String url) {
        String col = switch (platform) {
            case "youtube" -> "youtube";
            case "tiktok" -> "tiktok";
            case "instagram" -> "instagram";
            default -> throw new IllegalArgumentException(platform);
        };
        run("UPDATE accounts SET " + col + "=? WHERE name=?", url, name);
    }

    // ---------- Discord links ----------

    public synchronized List<Link> linksOf(String name) {
        List<Link> out = new ArrayList<>();
        try (PreparedStatement p = c.prepareStatement("SELECT * FROM links WHERE name=?")) {
            p.setString(1, name);
            ResultSet r = p.executeQuery();
            while (r.next()) out.add(new Link(r.getString("name"), r.getString("discord_id"), r.getString("discord_name")));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    public synchronized List<Account> accountsOf(String discordId) {
        List<Account> out = new ArrayList<>();
        try (PreparedStatement p = c.prepareStatement(
                "SELECT a.* FROM accounts a JOIN links l ON l.name = a.name WHERE l.discord_id=? ORDER BY a.name")) {
            p.setString(1, discordId);
            ResultSet r = p.executeQuery();
            while (r.next()) out.add(map(r));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    public synchronized boolean isLinked(String name, String discordId) {
        try (PreparedStatement p = c.prepareStatement("SELECT 1 FROM links WHERE name=? AND discord_id=?")) {
            p.setString(1, name); p.setString(2, discordId);
            return p.executeQuery().next();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** Links a Discord user and marks the account verified. */
    public synchronized void addLink(String name, String discordId, String discordName) {
        run("INSERT OR REPLACE INTO links(name, discord_id, discord_name) VALUES(?,?,?)", name, discordId, discordName);
        run("UPDATE accounts SET verified=1 WHERE name=?", name);
    }

    /** Removes one link. If it was the last one the account is no longer verified and its session is wiped. */
    public synchronized void removeLink(String name, String discordId) {
        run("DELETE FROM links WHERE name=? AND discord_id=?", name, discordId);
        if (linksOf(name).isEmpty()) {
            run("UPDATE accounts SET verified=0, last_ip=NULL, last_login=0 WHERE name=?", name);
        }
    }

    private void run(String sql, Object... args) {
        try (PreparedStatement p = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                if (args[i] == null) p.setNull(i + 1, Types.NULL); else p.setObject(i + 1, args[i]);
            }
            p.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public synchronized void close() {
        try { c.close(); } catch (SQLException ignored) {}
    }
}
