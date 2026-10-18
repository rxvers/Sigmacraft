package de.sigmacraft.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;

public final class SigmaCraftCore extends JavaPlugin implements Listener {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private File statsFile;
    private YamlConfiguration stats;
    private BukkitTask playtimeTask;
    private TeamManager teams;
    private TopLeaderboard leaderboards;
    private ElytraBoost elytraBoost;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadStats();
        teams = new TeamManager(this);
        leaderboards = new TopLeaderboard(this);
        elytraBoost = new ElytraBoost(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(elytraBoost, this);
        getCommand("spawn").setExecutor(this);
        getCommand("scstats").setExecutor(this);
        getCommand("sigmacraft").setExecutor(this);
        getCommand("team").setExecutor(this);
        getCommand("sctop").setExecutor(this);
        teams.loadOnlinePlayers();
        leaderboards.start();
        for (Player player : Bukkit.getOnlinePlayers()) {
            updateTab(player);
        }
        playtimeTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                add(player.getUniqueId(), "playtime_seconds", 60);
                updateTab(player);
            }
            saveStats();
        }, 1200L, 1200L);
        getLogger().info("SigmaCraftCore 0.3.0 gestartet; Statistiken werden in plugins/SigmaCraftCore/stats.yml gespeichert.");
    }

    @Override
    public void onDisable() {
        if (playtimeTask != null) playtimeTask.cancel();
        if (leaderboards != null) leaderboards.stop();
        if (elytraBoost != null) elytraBoost.restoreAll();
        saveStats();
    }

    private void loadStats() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Plugin-Datenordner konnte nicht erstellt werden.");
        }
        statsFile = new File(getDataFolder(), "stats.yml");
        stats = YamlConfiguration.loadConfiguration(statsFile);
    }

    private void saveStats() {
        if (stats == null || statsFile == null) return;
        try {
            stats.save(statsFile);
        } catch (IOException e) {
            getLogger().severe("Statistiken konnten nicht gespeichert werden: " + e.getMessage());
        }
    }

    private String base(UUID id) { return "players." + id; }
    private long get(UUID id, String key) { return stats.getLong(base(id) + "." + key, 0L); }
    private void add(UUID id, String key, long amount) { stats.set(base(id) + "." + key, get(id, key) + amount); }
    private void setName(UUID id, String name) { stats.set(base(id) + ".name", name); }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        setName(event.getPlayer().getUniqueId(), event.getPlayer().getName());
        updateTab(event.getPlayer());
        if (teams != null) teams.apply(event.getPlayer());
        saveStats();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        saveStats();
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        add(victim.getUniqueId(), "deaths", 1);
        setName(victim.getUniqueId(), victim.getName());
        Player killer = victim.getKiller();
        if (killer != null && !killer.getUniqueId().equals(victim.getUniqueId())) {
            add(killer.getUniqueId(), "kills", 1);
            setName(killer.getUniqueId(), killer.getName());
            updateTab(killer);
        }
        updateTab(victim);
        saveStats();
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (insideProtection(event.getBlock().getLocation()) && !player.hasPermission("sigmacraft.admin")) {
            player.sendMessage(color(getConfig().getString("messages.protected", "&cHey, hier können keine Blöcke zerstört werden!")));
            event.setCancelled(true);
            return;
        }
        add(player.getUniqueId(), "blocks_mined", 1);
        if (isDiamondOre(event.getBlock())) add(player.getUniqueId(), "diamonds_mined", 1);
        setName(player.getUniqueId(), player.getName());
        updateTab(player);
        // Persist in batches rather than writing the YAML file for every block.
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String icon = chatIcon(player.getUniqueId());
        // Keep the vanilla chat format and add only small symbols, no rank titles.
        String prefix = teams == null ? "" : teams.prefix(player.getUniqueId());
        event.setFormat(prefix + "%s " + icon + ChatColor.RESET + ": %s");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("team")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Nur ingame verfügbar."); return true; }
            if (args.length == 0) { p.sendMessage("§b/team create <Tag> <Farbe> §7| /team invite <Spieler> §7| /team accept §7| /team leave §7| /team info"); return true; }
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "create" -> {
                    if (args.length != 3) { p.sendMessage("§c/team create <Tag> <Farbe>"); return true; }
                    if (teams.create(p, args[1], args[2])) p.sendMessage("§aTeam [" + args[1] + "] erstellt.");
                    else p.sendMessage("§cTeam konnte nicht erstellt werden. Tag 2-12 Zeichen, Farbe red/blue/green/yellow/aqua/purple/white/gold/gray/black; du darfst noch kein Team haben.");
                }
                case "invite" -> {
                    if (args.length != 2) { p.sendMessage("§c/team invite <Spieler>"); return true; }
                    Player target = Bukkit.getPlayerExact(args[1]);
                    if (target == null || !teams.invite(p, target)) p.sendMessage("§cEinladung nicht möglich. Bist du Teamleiter und hat der Spieler noch kein Team?");
                }
                case "accept" -> { if (teams.accept(p)) p.sendMessage("§aDu bist dem Team beigetreten."); else p.sendMessage("§cKeine gültige Einladung vorhanden oder du hast bereits ein Team."); }
                case "leave" -> { if (teams.leave(p)) p.sendMessage("§eDu hast das Team verlassen."); else p.sendMessage("§cDu bist in keinem Team."); }
                case "info" -> { String team = teams.teamOf(p.getUniqueId()); if (team.isEmpty()) p.sendMessage("§7Du bist in keinem Team."); else p.sendMessage(teams.color(team) + "Team [" + teams.tag(team) + "] §7- online: " + teams.onlineCount(team)); }
                default -> p.sendMessage("§b/team create <Tag> <Farbe> §7| /team invite <Spieler> §7| /team accept §7| /team leave §7| /team info");
            }
            return true;
        }
        if (command.getName().equalsIgnoreCase("sctop")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Nur ingame verfügbar."); return true; }
            if (!p.hasPermission("sigmacraft.admin")) { p.sendMessage("§cKeine Berechtigung."); return true; }
            if (args.length == 2 && args[0].equalsIgnoreCase("create")) {
                if (leaderboards.create(p, args[1].toLowerCase(Locale.ROOT))) p.sendMessage("§aTop-10-Hologramm erstellt.");
                else p.sendMessage("§cKategorie: kills, deaths, diamonds, blocks, playtime");
            } else if (args.length == 1 && args[0].equalsIgnoreCase("remove")) {
                p.sendMessage(leaderboards.removeNearest(p) ? "§aNächstes SigmaCraft-Hologramm entfernt." : "§cKein Hologramm in deiner Nähe.");
            } else p.sendMessage("§b/sctop create <kills|deaths|diamonds|blocks|playtime> §7| /sctop remove");
            return true;
        }
        if (command.getName().equalsIgnoreCase("spawn")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Dieser Befehl ist nur ingame verfügbar.");
                return true;
            }
            Location spawn = configuredSpawn();
            if (spawn == null) {
                player.sendMessage(ChatColor.RED + "Spawn-Welt nicht gefunden. Bitte config.yml prüfen.");
                return true;
            }
            double radius = getConfig().getDouble("spawn.command-radius", 300.0);
            if (!player.hasPermission("sigmacraft.admin") && !sameWorld(player.getLocation(), spawn)) {
                player.sendMessage(color(getConfig().getString("messages.too-far")));
                return true;
            }
            if (!player.hasPermission("sigmacraft.admin") && player.getLocation().distanceSquared(spawn) > radius * radius) {
                player.sendMessage(color(getConfig().getString("messages.too-far")));
                return true;
            }
            player.teleport(spawn);
            player.sendMessage(ChatColor.GREEN + "Du wurdest zum Spawn teleportiert.");
            return true;
        }
        if (command.getName().equalsIgnoreCase("sigmacraft")) {
            if (!sender.hasPermission("sigmacraft.stats") && !sender.isOp()) {
                sender.sendMessage("Keine Berechtigung.");
                return true;
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("stat")) {
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
                UUID id = target.getUniqueId();
                String name = stats.getString(base(id) + ".name", target.getName() == null ? args[2] : target.getName());
                String metric = args[1].toLowerCase(Locale.ROOT);
                long value;
                switch (metric) {
                    case "kills" -> value = get(id, "kills");
                    case "deaths" -> value = get(id, "deaths");
                    case "blocks" -> value = get(id, "blocks_mined");
                    case "diamonds" -> value = get(id, "diamonds_mined");
                    case "playtime" -> value = get(id, "playtime_seconds");
                    case "stats" -> {
                        sender.sendMessage("SigmaCraft-Statistiken für " + name + ": Kills " + get(id, "kills")
                                + " | Tode " + get(id, "deaths") + " | Blöcke " + get(id, "blocks_mined")
                                + " | Diamanterze " + get(id, "diamonds_mined") + " | Spielzeit " + formatTime(get(id, "playtime_seconds")));
                        return true;
                    }
                    default -> { sender.sendMessage("Unbekannte Statistik."); return true; }
                }
                String label = switch (metric) {
                    case "kills" -> "Kills";
                    case "deaths" -> "Tode";
                    case "blocks" -> "abgebaute Blöcke";
                    case "diamonds" -> "abgebaute Diamanterze";
                    default -> "Spielzeit";
                };
                sender.sendMessage(name + " – " + label + ": " + (metric.equals("playtime") ? formatTime(value) : value));
                return true;
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("leaderboard")) {
                String metric = args[1].toLowerCase(Locale.ROOT);
                String key = switch (metric) {
                    case "kills" -> "kills";
                    case "deaths" -> "deaths";
                    case "blocks" -> "blocks_mined";
                    case "diamonds" -> "diamonds_mined";
                    case "playtime" -> "playtime_seconds";
                    default -> null;
                };
                if (key == null) { sender.sendMessage("Unbekannte Rangliste."); return true; }
                java.util.List<java.util.Map.Entry<String, Long>> ranking = new java.util.ArrayList<>();
                for (String rawId : stats.getConfigurationSection("players") == null ? java.util.Set.<String>of() : stats.getConfigurationSection("players").getKeys(false)) {
                    String path = "players." + rawId;
                    ranking.add(new java.util.AbstractMap.SimpleEntry<>(stats.getString(path + ".name", rawId), stats.getLong(path + "." + key, 0L)));
                }
                ranking.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
                StringBuilder out = new StringBuilder("Top 10 – ").append(metric).append(":\n");
                for (int i = 0; i < Math.min(10, ranking.size()); i++) {
                    var row = ranking.get(i);
                    out.append(i + 1).append(". ").append(row.getKey()).append(" — ")
                            .append(metric.equals("playtime") ? formatTime(row.getValue()) : row.getValue()).append('\n');
                }
                sender.sendMessage(out.toString().trim());
                return true;
            }
            sender.sendMessage("Usage: /sigmacraft stat <kills|deaths|blocks|diamonds|playtime|stats> <Spieler> oder /sigmacraft leaderboard <kategorie>");
            return true;
        }
        if (command.getName().equalsIgnoreCase("scstats")) {
            if (!sender.hasPermission("sigmacraft.stats") && !sender.isOp()) {
                sender.sendMessage("Keine Berechtigung.");
                return true;
            }
            if (args.length != 1) {
                sender.sendMessage("Usage: scstats <Spielername>");
                return true;
            }
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
            UUID id = target.getUniqueId();
            String name = stats.getString(base(id) + ".name", target.getName() == null ? args[0] : target.getName());
            long kills = get(id, "kills");
            long deaths = get(id, "deaths");
            long blocks = get(id, "blocks_mined");
            long diamonds = get(id, "diamonds_mined");
            long seconds = get(id, "playtime_seconds");
            // Machine-readable line intended for the existing bot's RCON reader.
            sender.sendMessage("SCSTATS|" + safe(name) + "|" + kills + "|" + deaths + "|" + blocks + "|" + diamonds + "|" + seconds);
            return true;
        }
        return false;
    }

    private Location configuredSpawn() {
        String worldName = getConfig().getString("spawn.world", "world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;
        return new Location(world,
                getConfig().getDouble("spawn.x", 0.5),
                getConfig().getDouble("spawn.y", world.getSpawnLocation().getY()),
                getConfig().getDouble("spawn.z", 0.5),
                (float) getConfig().getDouble("spawn.yaw", 0.0),
                (float) getConfig().getDouble("spawn.pitch", 0.0));
    }

    public boolean isNearSpawn(Location location, double radius) {
        Location spawn = configuredSpawn();
        return spawn != null && sameWorld(location, spawn) && location.distanceSquared(spawn) <= radius * radius;
    }

    private boolean insideProtection(Location location) {
        Location spawn = configuredSpawn();
        if (spawn == null || !sameWorld(location, spawn)) return false;
        double radius = getConfig().getDouble("spawn.protection-radius", 30.0);
        double dx = location.getX() - spawn.getX();
        double dz = location.getZ() - spawn.getZ();
        return dx * dx + dz * dz <= radius * radius;
    }

    private boolean sameWorld(Location a, Location b) { return a.getWorld() != null && a.getWorld().equals(b.getWorld()); }

    private boolean isDiamondOre(Block block) {
        String type = block.getType().name();
        return type.equals("DIAMOND_ORE") || type.equals("DEEPSLATE_DIAMOND_ORE");
    }

    private String chatIcon(UUID id) {
        // Use broadly supported Unicode symbols instead of platform-dependent emoji.
        if (get(id, "diamonds_mined") >= 100) return ChatColor.AQUA + "◆";
        if (get(id, "kills") >= 25) return ChatColor.RED + "⚔";
        if (get(id, "playtime_seconds") >= 36000) return ChatColor.GOLD + "◷";
        if (get(id, "blocks_mined") >= 1000) return ChatColor.GRAY + "⛏";
        return ChatColor.DARK_GRAY + "·";
    }

    public void refreshPlayerTab(Player player) { updateTab(player); }

    private void updateTab(Player player) {
        String icon = chatIcon(player.getUniqueId());
        String teamPrefix = teams == null ? "" : teams.prefix(player.getUniqueId());
        String teamOnline = teams == null ? "" : teams.onlineTeamSummary(player.getUniqueId());
        String ownStats = ChatColor.DARK_GRAY + " ☠" + get(player.getUniqueId(), "deaths")
                + " ⛏" + get(player.getUniqueId(), "blocks_mined");
        Component name = LEGACY.deserialize(icon + " " + teamPrefix + ChatColor.RESET + player.getName() + ownStats + teamOnline);
        player.playerListName(name);
        player.sendPlayerListHeaderAndFooter(Component.empty(), LEGACY.deserialize("&7sigmacraft.minecraftserver.de"));
    }

    public record RankRow(String name, long value) {}
    public java.util.List<RankRow> top(String metric, int limit) {
        String key = switch (metric) { case "kills" -> "kills"; case "deaths" -> "deaths"; case "diamonds" -> "diamonds_mined"; case "blocks" -> "blocks_mined"; case "playtime" -> "playtime_seconds"; default -> null; };
        java.util.List<RankRow> rows = new java.util.ArrayList<>();
        if (key == null) return rows;
        var section = stats.getConfigurationSection("players");
        if (section != null) for (String id : section.getKeys(false)) rows.add(new RankRow(stats.getString("players." + id + ".name", id), stats.getLong("players." + id + "." + key)));
        rows.sort((a,b) -> Long.compare(b.value(), a.value()));
        return rows.subList(0, Math.min(limit, rows.size()));
    }
    public String formatTime(long seconds) {
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + minutes + "m";
        return minutes + "m";
    }

    private String color(String text) { return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text); }
    private String safe(String value) { return value.replace("|", "_").replace("\n", "_").replace("\r", "_"); }
}
