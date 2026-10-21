package de.sigmacraft.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.server.ServerListPingEvent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Vorab-Zugang: Solange er aktiv ist, dürfen nur Spieler auf der Zugangsliste (und Operatoren) joinen,
 * auch wenn weitere Spieler bereits auf der normalen Whitelist stehen. Mit /access off wird der Server freigegeben.
 */
final class AccessControl implements Listener {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final String PRE = "&8[&bZugang&8] ";
    private static final String DEFAULT_KICK = "&c&lSigmaCraft ist noch nicht eröffnet\n\n&7Der Server ist derzeit nur für ausgewählte Spieler offen.\n&7Schau bald wieder vorbei!";

    private final SigmaCraftCore plugin;
    private final File file;
    private final Set<String> allowed = ConcurrentHashMap.newKeySet();
    private volatile boolean restricted;

    AccessControl(SigmaCraftCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "access.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        restricted = y.getBoolean("restricted", false);
        for (String name : y.getStringList("allowed")) allowed.add(name.toLowerCase(Locale.ROOT));
    }

    // ---------- Zustand ----------

    boolean isRestricted() { return restricted; }

    boolean isAllowed(String name) { return allowed.contains(name.toLowerCase(Locale.ROOT)); }

    int count() { return allowed.size(); }

    List<String> names() { return new ArrayList<>(new TreeSet<>(allowed)); }

    synchronized boolean add(String name) {
        boolean added = allowed.add(name.toLowerCase(Locale.ROOT));
        if (added) save();
        return added;
    }

    synchronized boolean remove(String name) {
        boolean removed = allowed.remove(name.toLowerCase(Locale.ROOT));
        if (removed) save();
        return removed;
    }

    /** Schaltet den Vorab-Zugang ein/aus. Beim Einschalten werden nicht zugelassene Spieler gekickt. */
    void setRestricted(boolean on) {
        restricted = on;
        save();
        if (on) {
            for (Player p : new ArrayList<>(Bukkit.getOnlinePlayers())) {
                if (!p.isOp() && !p.hasPermission("sigmacraft.admin") && !isAllowed(p.getName())) p.kick(kickMessage());
            }
        }
    }

    private synchronized void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("restricted", restricted);
        y.set("allowed", new ArrayList<>(new TreeSet<>(allowed)));
        try {
            file.getParentFile().mkdirs();
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("access.yml konnte nicht gespeichert werden: " + e.getMessage());
        }
    }

    private Component kickMessage() {
        return LEGACY.deserialize(plugin.getConfig().getString("access.kick-message", DEFAULT_KICK));
    }

    // ---------- Durchsetzung ----------

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        if (!restricted || e.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        if (isAllowed(e.getName())) return;
        if (Bukkit.getOfflinePlayer(e.getUniqueId()).isOp()) return;
        e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, kickMessage());
    }

    @EventHandler
    public void onPing(ServerListPingEvent e) {
        String motd = plugin.getConfig().getString("access.motd", "");
        if (restricted && motd != null && !motd.isEmpty()) e.motd(LEGACY.deserialize(motd));
    }

    // ---------- Befehl ----------

    private void msg(CommandSender to, String text) { to.sendMessage(LEGACY.deserialize(text)); }

    boolean command(CommandSender sender, String[] args) {
        if (!sender.hasPermission("sigmacraft.admin")) { msg(sender, "&cDazu hast du keine Berechtigung."); return true; }
        String sub = args.length >= 1 ? args[0].toLowerCase(Locale.ROOT) : "status";
        switch (sub) {
            case "on" -> {
                setRestricted(true);
                msg(sender, PRE + "&cVorab-Zugang AKTIV. &7Nur &f" + count() + " &7zugelassene Spieler (und Operatoren) dürfen joinen.");
            }
            case "off" -> {
                setRestricted(false);
                msg(sender, PRE + "&aServer freigegeben. &7Jetzt gilt nur noch die normale Whitelist.");
            }
            case "add" -> {
                if (args.length != 2) { msg(sender, PRE + "&c/access add <Name>"); return true; }
                msg(sender, add(args[1]) ? PRE + "&f" + args[1] + " &7wurde zugelassen." : PRE + "&f" + args[1] + " &7war bereits zugelassen.");
            }
            case "remove" -> {
                if (args.length != 2) { msg(sender, PRE + "&c/access remove <Name>"); return true; }
                if (remove(args[1])) {
                    msg(sender, PRE + "&f" + args[1] + " &7wurde entfernt.");
                    Player online = Bukkit.getPlayerExact(args[1]);
                    if (restricted && online != null && !online.isOp() && !online.hasPermission("sigmacraft.admin")) online.kick(kickMessage());
                } else {
                    msg(sender, PRE + "&f" + args[1] + " &7stand nicht auf der Liste.");
                }
            }
            case "list" -> {
                msg(sender, PRE + "&7Zugelassen (&f" + count() + "&7): &f" + (allowed.isEmpty() ? "-" : String.join("&7, &f", names())));
            }
            case "status" -> {
                msg(sender, PRE + "&7Vorab-Zugang: " + (restricted ? "&cAKTIV" : "&aAUS (Server offen)") + " &8| &7Zugelassen: &f" + count());
                msg(sender, "&7/access &fon&7|&foff&7|&fadd <Name>&7|&fremove <Name>&7|&flist");
            }
            default -> msg(sender, "&7/access &fon&7|&foff&7|&fstatus&7|&fadd <Name>&7|&fremove <Name>&7|&flist");
        }
        return true;
    }
}
