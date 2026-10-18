package de.sigmacraft.core;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
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
        target.sendMessage(color(team) + "Du wurdest in Team [" + tag(team) + "] eingeladen. Nutze /team accept.");
        owner.sendMessage(ChatColor.GREEN + "Einladung gesendet."); return true;
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
