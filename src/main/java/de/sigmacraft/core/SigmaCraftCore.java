package de.sigmacraft.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.data.Openable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ChestBoat;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class SigmaCraftCore extends JavaPlugin implements Listener, TabCompleter {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final LegacyComponentSerializer SECTION = LegacyComponentSerializer.legacySection();
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final List<String> METRICS = List.of("kills", "deaths", "blocks", "diamonds", "playtime");
    private final Map<UUID, BukkitTask> pendingTeleports = new HashMap<>();
    private final Map<UUID, Long> lastTabUpdate = new HashMap<>();
    private Map<String, UUID> topHolders = new HashMap<>();
    private File statsFile;
    private YamlConfiguration stats;
    private BukkitTask playtimeTask;
    private TeamManager teams;
    private TopLeaderboard leaderboards;
    private ElytraBoost elytraBoost;
    private PunishmentManager punishments;
    private AdminPanel adminPanel;
    private AccessControl accessControl;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadStats();
        recomputeTop();
        teams = new TeamManager(this);
        leaderboards = new TopLeaderboard(this);
        elytraBoost = new ElytraBoost(this);
        punishments = new PunishmentManager(new File(getDataFolder(), "punishments.yml"));
        adminPanel = new AdminPanel(this, punishments);
        accessControl = new AccessControl(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(elytraBoost, this);
        Bukkit.getPluginManager().registerEvents(adminPanel, this);
        Bukkit.getPluginManager().registerEvents(accessControl, this);
        for (String name : List.of("spawn", "scstats", "sigmacraft", "team", "sctop", "stat", "leaderboard", "admin", "access")) {
            PluginCommand cmd = getCommand(name);
            if (cmd == null) continue;
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        teams.loadOnlinePlayers();
        leaderboards.start();
        for (Player player : Bukkit.getOnlinePlayers()) {
            updateTab(player);
        }
        // Top-1-Symbole alle 10 Sekunden neu berechnen; Tablist nur bei Änderung aktualisieren.
        Bukkit.getScheduler().runTaskTimer(this, () -> { if (recomputeTop()) refreshAllTabs(); }, 200L, 200L);
        playtimeTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                add(player.getUniqueId(), "playtime_seconds", 60);
                updateTab(player);
            }
            saveStats();
        }, 1200L, 1200L);
        getLogger().info("SigmaCraftCore gestartet; Statistiken werden in plugins/SigmaCraftCore/stats.yml gespeichert.");
    }

    @Override
    public void onDisable() {
        if (playtimeTask != null) playtimeTask.cancel();
        if (leaderboards != null) leaderboards.stop();
        if (elytraBoost != null) elytraBoost.restoreAll();
        if (punishments != null) punishments.save();
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
        if (teams != null) teams.apply(event.getPlayer());
        event.joinMessage(LEGACY.deserialize("&8[&a+&8] &7" + event.getPlayer().getName()));
        refreshAllTabs();
        saveStats();
        Bukkit.getScheduler().runTask(this, () -> enforceDimension(event.getPlayer()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        event.quitMessage(LEGACY.deserialize("&8[&c-&8] &7" + event.getPlayer().getName()));
        UUID id = event.getPlayer().getUniqueId();
        cancelTeleport(id, null);
        lastTabUpdate.remove(id);
        saveStats();
        Bukkit.getScheduler().runTask(this, this::refreshAllTabs);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (!pendingTeleports.containsKey(id) || event.getTo() == null) return;
        Location from = event.getFrom(), to = event.getTo();
        if (from.getBlockX() != to.getBlockX() || from.getBlockY() != to.getBlockY() || from.getBlockZ() != to.getBlockZ()) {
            cancelTeleport(id, "&cTeleport abgebrochen: Du hast dich bewegt.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onAnyDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player p && event.getFinalDamage() > 0 && pendingTeleports.containsKey(p.getUniqueId())) {
            cancelTeleport(p.getUniqueId(), "&cTeleport abgebrochen: Du hast Schaden genommen.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!getConfig().getBoolean("spawn.block-mobs", true) || !(event.getEntity() instanceof Mob)) return;
        CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();
        if (reason == CreatureSpawnEvent.SpawnReason.COMMAND || reason == CreatureSpawnEvent.SpawnReason.CUSTOM
                || reason == CreatureSpawnEvent.SpawnReason.SPAWNER_EGG) return;
        if (insideProtection(event.getLocation())) event.setCancelled(true);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Location deathLoc = victim.getLocation();
        victim.sendMessage(color("&8[&cTod&8] &7Du bist gestorben bei &f" + deathLoc.getBlockX() + ", " + deathLoc.getBlockY() + ", " + deathLoc.getBlockZ()
                + " &7(" + deathLoc.getWorld().getName() + ")"));
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
        updateTabThrottled(player);
        // Persist in batches rather than writing the YAML file for every block.
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteractBlock(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        Player player = event.getPlayer();
        if (block == null || !insideProtection(block.getLocation()) || player.hasPermission("sigmacraft.admin")) return;
        // Türen, Falltüren, Zauntore und alle Behälter (Kisten, Fässer, Öfen, Trichter ...) sperren.
        if (block.getBlockData() instanceof Openable || block.getState(false) instanceof Container) {
            event.setCancelled(true);
            if (event.getHand() == org.bukkit.inventory.EquipmentSlot.HAND) {
                player.sendMessage(color(getConfig().getString("messages.protected-use", "&cHey, das kannst du am Spawn nicht benutzen!")));
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Entity entity = event.getRightClicked();
        Player player = event.getPlayer();
        if (!(entity instanceof StorageMinecart || entity instanceof HopperMinecart || entity instanceof ChestBoat)) return;
        if (!insideProtection(entity.getLocation()) || player.hasPermission("sigmacraft.admin")) return;
        event.setCancelled(true);
        player.sendMessage(color(getConfig().getString("messages.protected-use", "&cHey, das kannst du am Spawn nicht benutzen!")));
    }

    // ---------- Kein PvP / kein Schaden am Spawn ----------

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpawnDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim) || !insideProtection(victim.getLocation())) return;
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause == EntityDamageEvent.DamageCause.VOID || cause == EntityDamageEvent.DamageCause.KILL
                || cause == EntityDamageEvent.DamageCause.SUICIDE) return;
        boolean pvp = false;
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Entity damager = byEntity.getDamager();
            if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) damager = shooter;
            pvp = damager instanceof Player;
        }
        if (getConfig().getBoolean("spawn.no-damage", true) || (pvp && getConfig().getBoolean("spawn.no-pvp", true))) {
            event.setCancelled(true);
        }
    }

    // ---------- Nether / End deaktivieren ----------

    public boolean isDimensionDisabled(String which) {
        return getConfig().getBoolean("worlds.disable-" + which, true);
    }

    /** Schaltet Nether ("nether") oder End ("end") um und gibt den neuen Zustand (true = deaktiviert) zurück. */
    public boolean toggleDimension(String which) {
        boolean now = !isDimensionDisabled(which);
        getConfig().set("worlds.disable-" + which, now);
        saveConfig();
        if (now) for (Player p : Bukkit.getOnlinePlayers()) enforceDimension(p);
        return now;
    }

    private boolean dimensionDisabled(World.Environment env) {
        return (env == World.Environment.NETHER && isDimensionDisabled("nether"))
                || (env == World.Environment.THE_END && isDimensionDisabled("end"));
    }

    /** Wer in einer deaktivierten Dimension steht, wird (außer Admins) zum Spawn gebracht. */
    private void enforceDimension(Player p) {
        if (p.hasPermission("sigmacraft.admin") || !dimensionDisabled(p.getWorld().getEnvironment())) return;
        Location spawn = configuredSpawn();
        if (spawn == null) return;
        p.teleport(spawn);
        p.sendMessage(color("&cDiese Dimension ist deaktiviert. Du wurdest zum Spawn gebracht."));
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Bukkit.getScheduler().runTask(this, () -> enforceDimension(event.getPlayer()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        Player p = event.getPlayer();
        Location to = event.getTo();
        if (p.hasPermission("sigmacraft.admin") || to == null || to.getWorld() == null) return;
        if (dimensionDisabled(to.getWorld().getEnvironment())) {
            event.setCancelled(true);
            p.sendMessage(color("&cDiese Dimension ist derzeit deaktiviert."));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        Location to = event.getTo();
        if (to != null && to.getWorld() != null && dimensionDisabled(to.getWorld().getEnvironment())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPortalCreate(PortalCreateEvent event) {
        if (event.getEntity() instanceof Player p && p.hasPermission("sigmacraft.admin")) return;
        if (isDimensionDisabled("nether") && (event.getReason() == PortalCreateEvent.CreateReason.FIRE
                || event.getReason() == PortalCreateEvent.CreateReason.NETHER_PAIR)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEnderEye(PlayerInteractEvent event) {
        if (!isDimensionDisabled("end") || event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.END_PORTAL_FRAME) return;
        if (event.getItem() == null || event.getItem().getType() != Material.ENDER_EYE) return;
        if (event.getPlayer().hasPermission("sigmacraft.admin")) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage(color("&cDas End ist derzeit deaktiviert."));
    }

    // ---------- Daten löschen (vom Admin-Panel genutzt) ----------

    /** Setzt die SigmaCraft-Statistiken zurück (für einen Spieler oder, bei null, für alle). */
    void clearOurStats(UUID only) {
        ConfigurationSection section = stats.getConfigurationSection("players");
        if (section != null) {
            for (String raw : section.getKeys(false)) {
                if (only != null && !raw.equals(only.toString())) continue;
                for (String key : List.of("kills", "deaths", "blocks_mined", "diamonds_mined", "playtime_seconds")) {
                    stats.set("players." + raw + "." + key, null);
                }
            }
        }
        saveStats();
        recomputeTop();
        refreshAllTabs();
        leaderboards.refreshAll();
    }

    // ---------- Creeper-Schutz ----------

    @EventHandler(ignoreCancelled = true)
    public void onCreeperExplode(EntityExplodeEvent event) {
        if (event.getEntity() instanceof Creeper && getConfig().getBoolean("creeper.disable-explosions", true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCreeperDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Creeper && getConfig().getBoolean("creeper.disable-explosions", true)) {
            event.setCancelled(true);
        }
    }

    private boolean denyInSpawn(Player player, Location location) {
        if (!insideProtection(location) || player.hasPermission("sigmacraft.admin")) return false;
        player.sendMessage(color(getConfig().getString("messages.protected-place", "&cHey, hier können keine Blöcke platziert werden!")));
        return true;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (denyInSpawn(event.getPlayer(), event.getBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (denyInSpawn(event.getPlayer(), event.getBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (denyInSpawn(event.getPlayer(), event.getBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String badges = topBadges(player.getUniqueId());
        String icon = badges.isEmpty() ? chatIcon(player.getUniqueId()) : badges;
        // Keep the vanilla chat format and add only small symbols, no rank titles.
        String prefix = teams == null ? "" : teams.prefix(player.getUniqueId());
        event.setFormat(prefix + "%s " + icon + ChatColor.RESET + ": %s");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("team")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Nur ingame verfügbar."); return true; }
            final String pre = "§8[§bTeam§8] ";
            if (args.length == 0) { teamHelp(p); return true; }
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "create" -> {
                    if (args.length != 3) { p.sendMessage(pre + "§c/team create <Tag> <Farbe>"); return true; }
                    if (teams.create(p, args[1], args[2])) p.sendMessage(pre + "§aTeam §f[" + args[1] + "] §aerstellt.");
                    else p.sendMessage(pre + "§cTeam konnte nicht erstellt werden. §7Tag: 2-12 Buchstaben/Zahlen, Farbe: " + String.join(", ", teams.colorNames()) + ". Du darfst noch kein Team haben.");
                }
                case "invite" -> {
                    if (args.length != 2) { p.sendMessage(pre + "§c/team invite <Spieler>"); return true; }
                    Player target = Bukkit.getPlayerExact(args[1]);
                    if (target == null || !teams.invite(p, target)) p.sendMessage(pre + "§cEinladung nicht möglich. §7Bist du Teamleiter und hat der Spieler noch kein Team?");
                }
                case "accept" -> p.sendMessage(pre + (teams.accept(p) ? "§aDu bist dem Team beigetreten." : "§cKeine gültige Einladung vorhanden oder du hast bereits ein Team."));
                case "leave" -> p.sendMessage(pre + (teams.leave(p) ? "§eDu hast das Team verlassen." : "§cDu bist in keinem Team."));
                case "info" -> teams.sendInfo(p);
                case "coordssave" -> {
                    if (args.length != 2) { p.sendMessage(pre + "§c/team coordssave <Name>"); return true; }
                    saveTeamCoords(p, pre, args[1]);
                }
                case "coords" -> {
                    String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "list";
                    if (sub.equals("list")) teams.sendCoords(p);
                    else if (sub.equals("save") && args.length == 3) saveTeamCoords(p, pre, args[2]);
                    else if (sub.equals("delete") && args.length == 3) {
                        switch (teams.deleteCoords(p, args[2])) {
                            case 0 -> p.sendMessage(pre + "§aKoordinate §f" + args[2] + " §agelöscht.");
                            case 1 -> p.sendMessage(pre + "§cDu bist in keinem Team.");
                            case 2 -> p.sendMessage(pre + "§cKoordinate nicht gefunden.");
                            default -> p.sendMessage(pre + "§cNur der Ersteller oder der Teamleiter kann sie löschen.");
                        }
                    } else p.sendMessage(pre + "§c/team coords list §7| §c/team coords delete <Name>");
                }
                default -> teamHelp(p);
            }
            return true;
        }
        if (command.getName().equalsIgnoreCase("sctop")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Nur ingame verfügbar."); return true; }
            if (!p.hasPermission("sigmacraft.admin")) { p.sendMessage("§cKeine Berechtigung."); return true; }
            if (args.length == 2 && args[0].equalsIgnoreCase("create")) {
                if (leaderboards.create(p, args[1].toLowerCase(Locale.ROOT))) p.sendMessage("§aTop-10-Hologramm erstellt.");
                else p.sendMessage("§cKategorie: kills, deaths, diamonds, blocks, playtime");
            } else if (args.length >= 1 && args[0].equalsIgnoreCase("clean")) {
                double radius = 150;
                if (args.length >= 2) { try { radius = Math.min(1000, Math.max(5, Double.parseDouble(args[1]))); } catch (NumberFormatException ignored) { } }
                int removed = leaderboards.cleanNear(p, radius);
                p.sendMessage("§a" + removed + " Hologramm(e) im Umkreis von " + (int) radius + " Blöcken entfernt. Gespeicherte Tafeln werden neu erzeugt.");
            } else if (args.length == 1 && args[0].equalsIgnoreCase("remove")) {
                p.sendMessage(leaderboards.removeNearest(p) ? "§aNächstes SigmaCraft-Hologramm entfernt." : "§cKein Hologramm in deiner Nähe.");
            } else p.sendMessage("§b/sctop create <kills|deaths|diamonds|blocks|playtime> §7| /sctop remove | /sctop clean [Radius]");
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
            if (player.hasPermission("sigmacraft.admin")) {
                player.teleport(spawn);
                player.sendMessage(ChatColor.GREEN + "Du wurdest zum Spawn teleportiert.");
            } else {
                startTeleport(player);
            }
            return true;
        }
        if (command.getName().equalsIgnoreCase("admin")) return adminPanel.command(sender, args);
        if (command.getName().equalsIgnoreCase("access")) return accessControl.command(sender, args);
        if (command.getName().equalsIgnoreCase("stat")) return handleStat(sender, args);
        if (command.getName().equalsIgnoreCase("leaderboard")) return handleLeaderboard(sender, args);
        if (command.getName().equalsIgnoreCase("sigmacraft")) {
            if (args.length >= 1 && args[0].equalsIgnoreCase("stat")) return handleStat(sender, Arrays.copyOfRange(args, 1, args.length));
            if (args.length >= 1 && args[0].equalsIgnoreCase("leaderboard")) return handleLeaderboard(sender, Arrays.copyOfRange(args, 1, args.length));
            sender.sendMessage(sender instanceof Player ? "§7Nutze §b/stat §7und §b/leaderboard" : "Usage: /sigmacraft stat <kills|deaths|blocks|diamonds|playtime|stats> <Spieler> oder /sigmacraft leaderboard <kategorie>");
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

    private void refreshAllTabs() {
        for (Player online : Bukkit.getOnlinePlayers()) updateTab(online);
    }

    private void updateTabThrottled(Player player) {
        long now = System.currentTimeMillis();
        if (now - lastTabUpdate.getOrDefault(player.getUniqueId(), 0L) < 2000L) return;
        updateTab(player);
    }

    /** Tablist: Name als "[Team] Spieler", Header mit Servername, Footer mit eigenen Werten. */
    private void updateTab(Player player) {
        UUID id = player.getUniqueId();
        lastTabUpdate.put(id, System.currentTimeMillis());
        String prefix = teams == null ? "" : teams.prefix(id);
        String badges = topBadges(id);
        player.playerListName(SECTION.deserialize(prefix + ChatColor.WHITE + player.getName() + (badges.isEmpty() ? "" : " " + badges)));

        String team = teams == null ? "" : teams.teamOf(id);
        String teamText = team.isEmpty() ? "-" : Integer.toString(teams.onlineCount(team));
        Component header = MM.deserialize("<newline><gradient:#55ffff:#5555ff><bold>SIGMACRAFT</bold></gradient><newline>"
                + "<gray>sigmacraft.minecraftserver.de</gray><newline>"
                + "<dark_gray>Online</dark_gray> <white>" + Bukkit.getOnlinePlayers().size() + "</white><newline>");
        Component footer = MM.deserialize("<newline>"
                + "<red>Tode</red> <white>" + get(id, "deaths") + "</white> <dark_gray>|</dark_gray> "
                + "<green>Kills</green> <white>" + get(id, "kills") + "</white> <dark_gray>|</dark_gray> "
                + "<aqua>Team-Online</aqua> <white>" + teamText + "</white> <dark_gray>|</dark_gray> "
                + "<gold>Abgebaut</gold> <white>" + get(id, "blocks_mined") + "</white><newline>");
        player.sendPlayerListHeaderAndFooter(header, footer);
    }

    /** Ermittelt pro Rangliste den Spieler auf Platz 1 (nur bei Wert > 0). Gibt true zurück, wenn sich etwas geändert hat. */
    private boolean recomputeTop() {
        Map<String, UUID> next = new HashMap<>();
        ConfigurationSection section = stats.getConfigurationSection("players");
        if (section != null) {
            for (String metric : METRICS) {
                String key = statKey(metric);
                long best = 0L;
                UUID holder = null;
                for (String raw : section.getKeys(false)) {
                    long value = stats.getLong("players." + raw + "." + key, 0L);
                    if (value <= best) continue;
                    try { holder = UUID.fromString(raw); best = value; } catch (IllegalArgumentException ignored) { }
                }
                if (holder != null) next.put(metric, holder);
            }
        }
        boolean changed = !next.equals(topHolders);
        topHolders = next;
        return changed;
    }

    /** Kleine Symbole für Spieler, die in einer Rangliste auf Platz 1 stehen. */
    private String topBadges(UUID id) {
        StringBuilder sb = new StringBuilder();
        if (id.equals(topHolders.get("kills"))) sb.append(ChatColor.RED).append(ChatColor.BOLD).append("⚔");
        if (id.equals(topHolders.get("deaths"))) sb.append(ChatColor.DARK_RED).append(ChatColor.BOLD).append("☠");
        if (id.equals(topHolders.get("diamonds"))) sb.append(ChatColor.AQUA).append(ChatColor.BOLD).append("◆");
        if (id.equals(topHolders.get("blocks"))) sb.append(ChatColor.GRAY).append(ChatColor.BOLD).append("⛏");
        if (id.equals(topHolders.get("playtime"))) sb.append(ChatColor.GOLD).append(ChatColor.BOLD).append("◷");
        return sb.length() == 0 ? "" : sb.append(ChatColor.RESET).toString();
    }

    public String nameOf(UUID id) {
        String name = stats.getString(base(id) + ".name");
        if (name != null) return name;
        OfflinePlayer off = Bukkit.getOfflinePlayer(id);
        return off.getName() == null ? id.toString().substring(0, 8) : off.getName();
    }

    private void saveTeamCoords(Player p, String pre, String name) {
        switch (teams.saveCoords(p, name)) {
            case 0 -> p.sendMessage(pre + "§aKoordinate §f" + name + " §agespeichert. §7Das ganze Team sieht sie mit §b/team coords list§7.");
            case 1 -> p.sendMessage(pre + "§cDu bist in keinem Team.");
            case 2 -> p.sendMessage(pre + "§cUngültiger Name. §7Erlaubt: Buchstaben, Zahlen, _ und - (max. 20 Zeichen).");
            default -> p.sendMessage(pre + "§cLimit erreicht (max. 20). §7Lösche eine mit §b/team coords delete <Name>§7.");
        }
    }

    private void teamHelp(Player p) {
        p.sendMessage("§8▬▬▬▬ §b§lTEAM §8▬▬▬▬");
        p.sendMessage("§b/team create §7<Tag> <Farbe>");
        p.sendMessage("§b/team invite §7<Spieler>");
        p.sendMessage("§b/team accept §8| §b/team leave");
        p.sendMessage("§b/team info §7- Leiter und Mitglieder");
        p.sendMessage("§b/team coordssave §7<Name> §8| §b/team coords list");
        p.sendMessage("§b/team coords delete §7<Name>");
    }

    // ---------- /spawn mit Wartezeit ----------

    private void startTeleport(Player player) {
        UUID id = player.getUniqueId();
        if (pendingTeleports.containsKey(id)) {
            player.sendMessage(color("&eDein Teleport läuft bereits."));
            return;
        }
        int delay = Math.max(1, getConfig().getInt("spawn.teleport-delay-seconds", 5));
        player.sendMessage(color("&8[&bSpawn&8] &7Teleport in &f" + delay + " &7Sekunden. &eNicht bewegen und keinen Schaden nehmen!"));
        int[] left = {delay};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!player.isOnline()) { cancelTeleport(id, null); return; }
            if (left[0] <= 0) {
                cancelTeleport(id, null);
                Location spawn = configuredSpawn();
                if (spawn != null) {
                    player.teleport(spawn);
                    player.sendMessage(color("&8[&bSpawn&8] &aDu wurdest zum Spawn teleportiert."));
                }
                return;
            }
            player.sendActionBar(LEGACY.deserialize("&bTeleport in &f" + left[0] + "&bs"));
            left[0]--;
        }, 0L, 20L);
        pendingTeleports.put(id, task);
    }

    private void cancelTeleport(UUID id, String reason) {
        BukkitTask task = pendingTeleports.remove(id);
        if (task == null) return;
        task.cancel();
        Player p = Bukkit.getPlayer(id);
        if (reason != null && p != null) {
            p.sendMessage(color(reason));
            p.sendActionBar(LEGACY.deserialize(reason));
        }
    }

    // ---------- Stats-Befehle (/stat, /leaderboard, /sigmacraft ...) ----------

    private String statKey(String metric) {
        return switch (metric) {
            case "kills" -> "kills";
            case "deaths" -> "deaths";
            case "blocks" -> "blocks_mined";
            case "diamonds" -> "diamonds_mined";
            default -> "playtime_seconds";
        };
    }

    private String prettyLabel(String metric) {
        return switch (metric) {
            case "kills" -> "&a⚔ Kills";
            case "deaths" -> "&c☠ Tode";
            case "blocks" -> "&7⛏ Abgebaute Blöcke";
            case "diamonds" -> "&b◆ Diamanterze";
            default -> "&6◷ Spielzeit";
        };
    }

    private String consoleLabel(String metric) {
        return switch (metric) {
            case "kills" -> "Kills";
            case "deaths" -> "Tode";
            case "blocks" -> "abgebaute Blöcke";
            case "diamonds" -> "abgebaute Diamanterze";
            default -> "Spielzeit";
        };
    }

    private String valueText(String metric, long value) {
        return metric.equals("playtime") ? formatTime(value) : Long.toString(value);
    }

    AccessControl access() { return accessControl; }

    UUID resolveId(String name, boolean allowOfflineLookup) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        ConfigurationSection section = stats.getConfigurationSection("players");
        if (section != null) {
            for (String raw : section.getKeys(false)) {
                if (!name.equalsIgnoreCase(stats.getString("players." + raw + ".name", ""))) continue;
                try { return UUID.fromString(raw); } catch (IllegalArgumentException ignored) { }
            }
        }
        return allowOfflineLookup ? Bukkit.getOfflinePlayer(name).getUniqueId() : null;
    }

    private void send(CommandSender sender, String ampersandText) { sender.sendMessage(LEGACY.deserialize(ampersandText)); }

    private boolean handleStat(CommandSender sender, String[] args) {
        boolean pretty = sender instanceof Player;
        String metric = args.length >= 1 ? args[0].toLowerCase(Locale.ROOT) : "all";
        if (metric.equals("stats")) metric = "all";
        if (!metric.equals("all") && !METRICS.contains(metric)) {
            if (pretty) send(sender, "&cUnbekannte Statistik. &7Nutze: &fkills&7, &fdeaths&7, &fblocks&7, &fdiamonds&7, &fplaytime&7, &fall");
            else sender.sendMessage("Unbekannte Statistik.");
            return true;
        }
        UUID id;
        String name;
        if (args.length >= 2) {
            id = resolveId(args[1], !pretty);
            if (id == null) {
                if (pretty) send(sender, getConfig().getString("messages.no-player", "&cSpieler nicht gefunden."));
                else sender.sendMessage("Spieler nicht gefunden.");
                return true;
            }
            name = stats.getString(base(id) + ".name", args[1]);
        } else if (sender instanceof Player p) {
            id = p.getUniqueId();
            name = p.getName();
        } else {
            sender.sendMessage("Usage: stat <kills|deaths|blocks|diamonds|playtime|all> <Spieler>");
            return true;
        }

        if (!pretty) {
            if (metric.equals("all")) {
                sender.sendMessage("SigmaCraft-Statistiken für " + name + ": Kills " + get(id, "kills")
                        + " | Tode " + get(id, "deaths") + " | Blöcke " + get(id, "blocks_mined")
                        + " | Diamanterze " + get(id, "diamonds_mined") + " | Spielzeit " + formatTime(get(id, "playtime_seconds")));
            } else {
                sender.sendMessage(name + " – " + consoleLabel(metric) + ": " + valueText(metric, get(id, statKey(metric))));
            }
            return true;
        }

        send(sender, "&8▬▬▬ &b&lSIGMACRAFT &8▬▬▬");
        if (metric.equals("all")) {
            send(sender, "&7Statistiken von &f" + name);
            send(sender, "&c☠ Tode &f" + get(id, "deaths") + "  &8|  &a⚔ Kills &f" + get(id, "kills"));
            send(sender, "&7⛏ Blöcke &f" + get(id, "blocks_mined") + "  &8|  &b◆ Diamanten &f" + get(id, "diamonds_mined"));
            send(sender, "&6◷ Spielzeit &f" + formatTime(get(id, "playtime_seconds")));
        } else {
            send(sender, "&7" + name + "  &8» " + prettyLabel(metric) + " &f" + valueText(metric, get(id, statKey(metric))));
        }
        return true;
    }

    private boolean handleLeaderboard(CommandSender sender, String[] args) {
        boolean pretty = sender instanceof Player;
        String metric = args.length >= 1 ? args[0].toLowerCase(Locale.ROOT) : "";
        if (!METRICS.contains(metric)) {
            if (pretty) send(sender, "&7Nutze: &b/leaderboard &f<kills|deaths|blocks|diamonds|playtime>");
            else sender.sendMessage("Unbekannte Rangliste.");
            return true;
        }
        List<RankRow> rows = top(metric, 10);
        if (!pretty) {
            StringBuilder out = new StringBuilder("Top 10 – ").append(metric).append(":\n");
            for (int i = 0; i < rows.size(); i++) {
                out.append(i + 1).append(". ").append(rows.get(i).name()).append(" — ").append(valueText(metric, rows.get(i).value())).append('\n');
            }
            sender.sendMessage(out.toString().trim());
            return true;
        }
        send(sender, "&8▬▬▬ &b&lTOP 10 &8» " + prettyLabel(metric) + " &8▬▬▬");
        if (rows.isEmpty()) send(sender, "&7Noch keine Einträge.");
        for (int i = 0; i < rows.size(); i++) {
            String rank = i == 0 ? "&6&l1." : i == 1 ? "&f&l2." : i == 2 ? "&c&l3." : "&7" + (i + 1) + ".";
            send(sender, rank + " &f" + rows.get(i).name() + " &8» &b" + valueText(metric, rows.get(i).value()));
        }
        return true;
    }

    // ---------- Tab-Completion ----------

    private List<String> onlineNames() {
        List<String> names = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
        return names;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        String first = args.length >= 1 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (cmd) {
            case "team" -> {
                if (args.length == 1) options.addAll(List.of("create", "invite", "accept", "leave", "info", "coordssave", "coords"));
                else if (args.length == 2 && first.equals("invite")) options.addAll(onlineNames());
                else if (args.length == 2 && first.equals("create")) options.add("<Tag>");
                else if (args.length == 2 && first.equals("coordssave")) options.add("<Name>");
                else if (args.length == 2 && first.equals("coords")) options.addAll(List.of("list", "delete", "save"));
                else if (args.length == 3 && first.equals("coords") && args[1].equalsIgnoreCase("delete") && sender instanceof Player tp) options.addAll(teams.coordNames(tp.getUniqueId()));
                else if (args.length == 3 && first.equals("create")) options.addAll(teams.colorNames());
            }
            case "stat" -> {
                if (args.length == 1) { options.addAll(METRICS); options.add("all"); }
                else if (args.length == 2) options.addAll(onlineNames());
            }
            case "leaderboard" -> { if (args.length == 1) options.addAll(METRICS); }
            case "sigmacraft" -> {
                if (args.length == 1) options.addAll(List.of("stat", "leaderboard"));
                else if (args.length == 2 && first.equals("stat")) { options.addAll(METRICS); options.add("all"); }
                else if (args.length == 2 && first.equals("leaderboard")) options.addAll(METRICS);
                else if (args.length == 3 && first.equals("stat")) options.addAll(onlineNames());
            }
            case "sctop" -> {
                if (args.length == 1) options.addAll(List.of("create", "remove", "clean"));
                else if (args.length == 2 && first.equals("create")) options.addAll(METRICS);
            }
            case "access" -> {
                if (args.length == 1) options.addAll(List.of("on", "off", "status", "add", "remove", "list"));
                else if (args.length == 2 && first.equals("add")) options.addAll(onlineNames());
                else if (args.length == 2 && first.equals("remove")) options.addAll(accessControl.names());
            }
            case "scstats", "admin" -> { if (args.length == 1) options.addAll(onlineNames()); }
            default -> { }
        }
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(typed));
        return options;
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
