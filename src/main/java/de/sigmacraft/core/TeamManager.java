package de.sigmacraft.core;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class TeamManager {
    private final SigmaCraftCore plugin;
    private final File file;
    private YamlConfiguration data;
    private final Map<UUID, String> invitations = new HashMap<>();
    private final Map<String, ChatColor> colors = Map.ofEntries(
            Map.entry("red", ChatColor.RED), Map.entry("blue", ChatColor.BLUE), Map.entry("green", ChatColor.GREEN),
            Map.entry("yellow", ChatColor.YELLOW), Map.entry("aqua", ChatColor.AQUA), Map.entry("purple", ChatColor.LIGHT_PURPLE),
            Map.entry("white", ChatColor.WHITE), Map.entry("gold", ChatColor.GOLD), Map.entry("gray", ChatColor.GRAY),
            Map.entry("black", ChatColor.BLACK)
    );

    TeamManager(SigmaCraftCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "teams.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    public void loadOnlinePlayers() { for (Player p : Bukkit.getOnlinePlayers()) apply(p); }
    public String teamOf(UUID id) { return data.getString("members." + id, ""); }
    public String tag(String team) { return data.getString("teams." + team + ".tag", team); }
    public ChatColor color(String team) { return ChatColor.valueOf(data.getString("teams." + team + ".color", "WHITE")); }
    public String prefix(UUID id) {
        String t = teamOf(id);
        return t.isEmpty() ? "" : color(t) + "[" + tag(t) + "] " + ChatColor.RESET;
    }
    public int onlineCount(String team) {
        int count = 0;
        for (Player p : Bukkit.getOnlinePlayers()) if (team.equals(teamOf(p.getUniqueId()))) count++;
        return count;
    }
    // ---------- Team-Koordinaten ----------
    private static final int MAX_COORDS = 20;

    /** 0 = gespeichert, 1 = kein Team, 2 = ungültiger Name, 3 = Limit erreicht. */
    public int saveCoords(Player p, String name) {
        String team = teamOf(p.getUniqueId());
        if (team.isEmpty()) return 1;
        if (!name.matches("[A-Za-z0-9_-]{1,20}")) return 2;
        String key = name.toLowerCase(Locale.ROOT);
        ConfigurationSection all = data.getConfigurationSection("teams." + team + ".coords");
        if (all != null && !all.contains(key) && all.getKeys(false).size() >= MAX_COORDS) return 3;
        Location l = p.getLocation();
        String base = "teams." + team + ".coords." + key;
        data.set(base + ".name", name);
        data.set(base + ".world", l.getWorld().getName());
        data.set(base + ".x", l.getBlockX());
        data.set(base + ".y", l.getBlockY());
        data.set(base + ".z", l.getBlockZ());
        data.set(base + ".by", p.getUniqueId().toString());
        save();
        return 0;
    }

    /** 0 = gelöscht, 1 = kein Team, 2 = nicht gefunden, 3 = keine Berechtigung (nur Ersteller oder Leiter). */
    public int deleteCoords(Player p, String name) {
        String team = teamOf(p.getUniqueId());
        if (team.isEmpty()) return 1;
        String base = "teams." + team + ".coords." + name.toLowerCase(Locale.ROOT);
        if (!data.contains(base)) return 2;
        boolean isOwner = p.getUniqueId().equals(owner(team));
        boolean isAuthor = p.getUniqueId().toString().equals(data.getString(base + ".by"));
        if (!isOwner && !isAuthor) return 3;
        data.set(base, null);
        save();
        return 0;
    }

    public List<String> coordNames(UUID id) {
        List<String> names = new ArrayList<>();
        String team = teamOf(id);
        ConfigurationSection all = team.isEmpty() ? null : data.getConfigurationSection("teams." + team + ".coords");
        if (all != null) for (String key : all.getKeys(false)) names.add(data.getString("teams." + team + ".coords." + key + ".name", key));
        return names;
    }

    private String worldLabel(String worldName) {
        World w = Bukkit.getWorld(worldName);
        if (w == null) return worldName;
        return switch (w.getEnvironment()) {
            case NORMAL -> "Overworld";
            case NETHER -> "Nether";
            case THE_END -> "End";
            default -> worldName;
        };
    }

    public void sendCoords(Player p) {
        String team = teamOf(p.getUniqueId());
        if (team.isEmpty()) { p.sendMessage("§8[§bTeam§8] §7Du bist in keinem Team."); return; }
        ConfigurationSection all = data.getConfigurationSection("teams." + team + ".coords");
        p.sendMessage("§8▬▬▬ " + color(team) + "§l[" + tag(team) + "] §b§lKoordinaten §8▬▬▬");
        if (all == null || all.getKeys(false).isEmpty()) {
            p.sendMessage("§7Noch keine gespeichert. §8» §b/team coordssave <Name>");
            return;
        }
        for (String key : all.getKeys(false)) {
            String base = "teams." + team + ".coords." + key + ".";
            String worldName = data.getString(base + "world", "world");
            int x = data.getInt(base + "x"), y = data.getInt(base + "y"), z = data.getInt(base + "z");
            String author = "?";
            try { author = plugin.nameOf(UUID.fromString(data.getString(base + "by", ""))); } catch (IllegalArgumentException ignored) { }
            String distance = "";
            if (p.getWorld().getName().equals(worldName)) {
                double d = Math.sqrt(Math.pow(p.getLocation().getX() - x, 2) + Math.pow(p.getLocation().getY() - y, 2) + Math.pow(p.getLocation().getZ() - z, 2));
                distance = " §8| §7~" + Math.round(d) + " Blöcke";
            }
            p.sendMessage("§b▪ §f" + data.getString(base + "name", key) + " §8» §f" + x + ", " + y + ", " + z
                    + " §7(" + worldLabel(worldName) + ")" + distance + " §8| §7von " + author);
        }
    }

    public UUID owner(String team) {
        String raw = data.getString("teams." + team + ".owner");
        try { return raw == null ? null : UUID.fromString(raw); } catch (IllegalArgumentException e) { return null; }
    }
    public List<UUID> members(String team) {
        List<UUID> result = new ArrayList<>();
        if (data.getConfigurationSection("members") == null) return result;
        for (String key : data.getConfigurationSection("members").getKeys(false)) {
            if (!team.equals(data.getString("members." + key))) continue;
            try { result.add(UUID.fromString(key)); } catch (IllegalArgumentException ignored) { }
        }
        return result;
    }
    public List<String> colorNames() { return new ArrayList<>(new TreeSet<>(colors.keySet())); }

    /** Schöne Team-Übersicht mit Leiter sowie Mitgliedern (online und offline). */
    public void sendInfo(Player p) {
        String team = teamOf(p.getUniqueId());
        if (team.isEmpty()) {
            p.sendMessage("§8[§bTeam§8] §7Du bist in keinem Team. §8» §b/team create <Tag> <Farbe>");
            return;
        }
        ChatColor c = color(team);
        UUID owner = owner(team);
        List<UUID> all = members(team);
        all.sort((a, b) -> {
            if (a.equals(owner) != b.equals(owner)) return a.equals(owner) ? -1 : 1;
            boolean ao = Bukkit.getPlayer(a) != null, bo = Bukkit.getPlayer(b) != null;
            if (ao != bo) return ao ? -1 : 1;
            return plugin.nameOf(a).compareToIgnoreCase(plugin.nameOf(b));
        });
        int online = 0;
        for (UUID m : all) if (Bukkit.getPlayer(m) != null) online++;
        p.sendMessage("§8▬▬▬▬▬ " + c + "§l[" + tag(team) + "] §8▬▬▬▬▬");
        p.sendMessage("§7Teamleiter: §f" + (owner == null ? "?" : plugin.nameOf(owner)));
        p.sendMessage("§7Mitglieder: §f" + all.size() + " §8(§a" + online + " online§8, §7" + (all.size() - online) + " offline§8)");
        for (UUID m : all) {
            boolean isOnline = Bukkit.getPlayer(m) != null;
            String crown = m.equals(owner) ? " §6★" : "";
            p.sendMessage(isOnline ? "  §a● §f" + plugin.nameOf(m) + crown : "  §8● §7" + plugin.nameOf(m) + crown);
        }
        p.sendMessage("§8▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬");
    }

    public boolean create(Player p, String rawTag, String rawColor) {
        String team = rawTag.toLowerCase(Locale.ROOT);
        if (!team.matches("[a-z0-9]{2,12}") || !teamOf(p.getUniqueId()).isEmpty() || data.contains("teams." + team)) return false;
        ChatColor color = colors.get(rawColor.toLowerCase(Locale.ROOT));
        if (color == null) return false;
        data.set("teams." + team + ".tag", rawTag);
        data.set("teams." + team + ".color", color.name());
        data.set("teams." + team + ".owner", p.getUniqueId().toString());
        data.set("members." + p.getUniqueId(), team);
        save(); apply(p); return true;
    }
    public boolean invite(Player owner, Player target) {
        String team = teamOf(owner.getUniqueId());
        if (team.isEmpty() || !owner.getUniqueId().toString().equals(data.getString("teams." + team + ".owner")) || !teamOf(target.getUniqueId()).isEmpty()) return false;
        invitations.put(target.getUniqueId(), team);
        target.sendMessage("§8[§bTeam§8] §7Du wurdest in Team " + color(team) + "[" + tag(team) + "] §7eingeladen. §8» §b/team accept");
        owner.sendMessage("§8[§bTeam§8] §aEinladung an §f" + target.getName() + " §agesendet."); return true;
    }
    public boolean accept(Player p) {
        String team = invitations.remove(p.getUniqueId());
        if (team == null || !data.contains("teams." + team) || !teamOf(p.getUniqueId()).isEmpty()) return false;
        data.set("members." + p.getUniqueId(), team); save(); apply(p); return true;
    }
    public boolean leave(Player p) {
        String team = teamOf(p.getUniqueId());
        if (team.isEmpty()) return false;
        if (p.getUniqueId().toString().equals(data.getString("teams." + team + ".owner"))) {
            for (String member : new ArrayList<>(data.getConfigurationSection("members") == null ? Set.<String>of() : data.getConfigurationSection("members").getKeys(false))) {
                if (team.equals(data.getString("members." + member))) data.set("members." + member, null);
            }
            data.set("teams." + team, null);
            for (Player online : Bukkit.getOnlinePlayers()) apply(online);
        } else data.set("members." + p.getUniqueId(), null);
        save(); apply(p); return true;
    }
    public void apply(Player p) {
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        String oldName = null;
        for (Team t : board.getTeams()) if (t.getName().startsWith("sc_") && t.hasEntry(p.getName())) { oldName = t.getName(); break; }
        if (oldName != null) board.getTeam(oldName).removeEntry(p.getName());
        String team = teamOf(p.getUniqueId());
        if (!team.isEmpty()) {
            String key = "sc_" + team;
            Team bukkitTeam = board.getTeam(key);
            if (bukkitTeam == null) bukkitTeam = board.registerNewTeam(key);
            bukkitTeam.setColor(color(team));
            bukkitTeam.prefix(net.kyori.adventure.text.Component.text("[" + tag(team) + "] ", net.kyori.adventure.text.format.NamedTextColor.WHITE));
            bukkitTeam.addEntry(p.getName());
        }
        plugin.refreshPlayerTab(p);
        for (Player online : Bukkit.getOnlinePlayers()) plugin.refreshPlayerTab(online);
    }
    public String onlineTeamSummary(UUID id) {
        String team = teamOf(id);
        return team.isEmpty() ? "" : " " + color(team) + "👥" + onlineCount(team);
    }
    private void save() { try { data.save(file); } catch (IOException e) { plugin.getLogger().warning("Teams konnten nicht gespeichert werden: " + e.getMessage()); } }
}
