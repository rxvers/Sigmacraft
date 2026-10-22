package de.sigmacraft.core;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PvP-Kennzeichnung (Opt-in): Wer gekennzeichnet ist, stimmt PvP, Fallen und Griefing durch andere
 * Gekennzeichnete zu. Das Plugin zeigt die Kennzeichnung an (Tablist, Chat, Liste); die Regeln werden
 * vom Team durchgesetzt. Gespeichert in pvp.yml.
 */
final class PvpManager implements Listener {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final String PRE = "&8[&cPvP&8] ";
    private static final long CONFIRM_WINDOW = 30_000L;

    private final SigmaCraftCore plugin;
    private final File file;
    private final Map<UUID, Long> marked = new ConcurrentHashMap<>();
    private final Map<UUID, Long> pending = new HashMap<>();

    PvpManager(SigmaCraftCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "pvp.yml");
        ConfigurationSection section = YamlConfiguration.loadConfiguration(file).getConfigurationSection("marked");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                try { marked.put(UUID.fromString(key), section.getLong(key)); } catch (IllegalArgumentException ignored) { }
            }
        }
    }

    // ---------- Zustand ----------

    boolean isMarked(UUID id) { return marked.containsKey(id); }

    int markedCount() { return marked.size(); }

    /** Setzt oder entfernt die Kennzeichnung (ohne Wartezeit-Prüfung, z. B. für Admins). */
    void setMarked(UUID id, boolean on) {
        if (on) marked.put(id, System.currentTimeMillis());
        else marked.remove(id);
        save();
        plugin.refreshTabs();
    }

    private synchronized void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Long> e : marked.entrySet()) y.set("marked." + e.getKey(), e.getValue());
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("pvp.yml konnte nicht gespeichert werden: " + ex.getMessage());
        }
    }

    private int cooldownMinutes() { return Math.max(0, plugin.getConfig().getInt("pvp.optout-cooldown-minutes", 60)); }

    private void msg(CommandSender to, String text) { to.sendMessage(LEGACY.deserialize(text)); }

    // ---------- Befehl /pvp ----------

    boolean command(CommandSender sender, String[] args) {
        String sub = args.length >= 1 ? args[0].toLowerCase(Locale.ROOT) : "status";
        if (sub.equals("list") || sub.equals("liste")) { sendList(sender); return true; }
        if (!(sender instanceof Player p)) { sender.sendMessage("Nur ingame verfügbar."); return true; }
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        switch (sub) {
            case "on", "an" -> {
                if (isMarked(id)) { msg(p, PRE + "&7Du bist bereits &cgekennzeichnet&7."); return true; }
                pending.put(id, now);
                msg(p, "&8▬▬▬ &c&lPVP-KENNZEICHNUNG &8▬▬▬");
                msg(p, "&7Du stimmst &fPvP&7, &fFallen &7und &fGriefing &7durch andere Gekennzeichnete zu.");
                msg(p, "&7In der Tablist bekommst du einen &c●&7. Alle anderen bleiben geschützt (&a●&7).");
                msg(p, "&7Austreten ist erst nach &f" + cooldownMinutes() + " Minuten &7möglich.");
                msg(p, "&7Zum Bestätigen: &a/pvp bestätigen &8(30 Sekunden gültig)");
            }
            case "confirm", "bestätigen", "bestaetigen" -> {
                if (isMarked(id)) { msg(p, PRE + "&7Du bist bereits &cgekennzeichnet&7."); return true; }
                Long asked = pending.remove(id);
                if (asked == null || now - asked > CONFIRM_WINDOW) {
                    msg(p, PRE + "&7Nichts zu bestätigen. Starte mit &f/pvp on&7.");
                    return true;
                }
                setMarked(id, true);
                Bukkit.getServer().sendMessage(LEGACY.deserialize("&c● &f" + p.getName() + " &7hat sich für &cPvP &7gekennzeichnet."));
            }
            case "off", "aus" -> {
                if (!isMarked(id)) { msg(p, PRE + "&7Du bist bereits &ageschützt&7."); return true; }
                long unlock = marked.get(id) + cooldownMinutes() * 60_000L;
                if (now < unlock) {
                    msg(p, PRE + "&cDu kannst erst in &f" + PunishmentManager.formatDuration(unlock - now) + " &caustreten.");
                    return true;
                }
                setMarked(id, false);
                msg(p, PRE + "&aDu bist wieder &ageschützt&a.");
            }
            default -> sendStatus(p);
        }
        return true;
    }

    private void sendStatus(Player p) {
        UUID id = p.getUniqueId();
        msg(p, "&8▬▬▬ &c&lPVP &8▬▬▬");
        if (isMarked(id)) {
            long unlock = marked.get(id) + cooldownMinutes() * 60_000L;
            long left = unlock - System.currentTimeMillis();
            msg(p, "&7Status: &c● Gekennzeichnet &8(PvP, Fallen, Griefing)");
            msg(p, left > 0 ? "&7Austreten möglich in &f" + PunishmentManager.formatDuration(left) : "&7Austreten jetzt möglich: &f/pvp off");
        } else {
            msg(p, "&7Status: &a● Geschützt");
            msg(p, "&7Kennzeichnen: &f/pvp on");
        }
        msg(p, "&7Liste aller Spieler: &f/pvp list");
    }

    /** Liste: wer will PvP, wer nicht (online) sowie gekennzeichnete Offline-Spieler. */
    void sendList(CommandSender to) {
        List<String> pvp = new ArrayList<>();
        List<String> safe = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) (isMarked(online.getUniqueId()) ? pvp : safe).add(online.getName());
        pvp.sort(String.CASE_INSENSITIVE_ORDER);
        safe.sort(String.CASE_INSENSITIVE_ORDER);
        List<String> offline = new ArrayList<>();
        for (UUID id : marked.keySet()) if (Bukkit.getPlayer(id) == null) offline.add(plugin.nameOf(id));
        offline.sort(String.CASE_INSENSITIVE_ORDER);

        msg(to, "&8▬▬▬ &b&lPVP-LISTE &8▬▬▬");
        msg(to, "&c● PvP &8(&f" + pvp.size() + "&8)&7: " + (pvp.isEmpty() ? "&8niemand" : "&f" + String.join("&7, &f", pvp)));
        msg(to, "&a● Geschützt &8(&f" + safe.size() + "&8)&7: " + (safe.isEmpty() ? "&8niemand" : "&f" + String.join("&7, &f", safe)));
        if (!offline.isEmpty()) msg(to, "&8Offline gekennzeichnet: &7" + String.join("&8, &7", offline));
    }

    // ---------- Willkommen und Regeln ----------

    void sendWelcome(Player p) {
        msg(p, "&8▬▬▬ &b&lWILLKOMMEN AUF SIGMACRAFT &8▬▬▬");
        msg(p, "&b▪ &f/regeln &7- die wichtigsten Regeln zu PvP und Griefing");
        msg(p, "&b▪ &f/pvp on &7- PvP, Fallen und Griefing zustimmen &8(Standard: &ageschützt&8)");
        msg(p, "&b▪ &f/pvp off &7- wieder austreten &8(nach " + cooldownMinutes() + " Minuten)");
        msg(p, "&b▪ &f/pvp list &7- wer will PvP, wer nicht");
        msg(p, "&7Ziel des Servers: &fgemeinsam spielen und krasse Sachen bauen&7. PvP ist nur eine Option.");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!p.hasPlayedBefore()) Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) sendWelcome(p); }, 60L);
    }

    /** Die PvP- und Griefing-Regeln im Wortlaut der Discord-Regeln; alle weiteren Regeln stehen im Discord. */
    void sendRules(CommandSender to) {
        List<String> custom = plugin.getConfig().getStringList("rules.lines");
        if (!custom.isEmpty()) {
            for (String line : custom) msg(to, line);
            return;
        }
        msg(to, "&8▬▬▬ &b&lSIGMACRAFT REGELN &8▬▬▬");
        msg(to, "&c&lGriefing (Opt-in)");
        msg(to, "&7• Wer sich freiwillig fürs Griefing kennzeichnen lässt, darf andere gekennzeichnete Spieler griefen und selbst gegrieft werden.");
        msg(to, "&7• Wer nicht gekennzeichnet ist, ist vor Griefing geschützt.");
        msg(to, "&7• Kisten und andere Lagerbehälter zu griefen oder auszurauben ist verboten, unabhängig davon, ob jemand gekennzeichnet ist.");
        msg(to, "&7• Das großflächige Zerstören von Basen ist komplett verboten, auch bei gekennzeichneten Spielern. Einzelne Blöcke dürfen gelegentlich zerstört werden, sofern dadurch keine größeren Schäden entstehen.");
        msg(to, "&7• Wer einen nicht gekennzeichneten Spieler griefed, wird gebannt. Zusätzlich verliert der Täter seinen Schutz gegenüber dem Opfer.");
        msg(to, "&7• Wirst du ohne deine Zustimmung gegrieft, darfst du dich einmalig am Täter rächen. Weitere Racheaktionen sind verboten und können zum Bann führen.");
        msg(to, "&c&lPvP & Fallen");
        msg(to, "&7• Wer sich freiwillig kennzeichnen lässt, stimmt PvP und Fallen zu und darf von anderen gekennzeichneten Spielern angegriffen werden.");
        msg(to, "&7• Wer nicht gekennzeichnet ist, ist geschützt und darf weder getötet noch in tödliche Fallen gelockt werden.");
        msg(to, "&7• Fallen sind nur gegen gekennzeichnete Spieler erlaubt und müssen sich in einem angemessenen Rahmen halten.");
        msg(to, "&7• Spawnkilling ist ausnahmslos verboten, auch bei gekennzeichneten Spielern.");
        msg(to, "&7• PvP und Griefing sind optionale Zusatzfunktionen. Das Ziel von Sigma Craft ist es, gemeinsam zu spielen, Spaß zu haben und beeindruckende Bauwerke zu erschaffen – nicht, sich gegenseitig zu bekämpfen oder den Spielspaß anderer zu zerstören.");
        msg(to, "&8Alle weiteren Regeln findest du im Regelkanal auf Discord. &7Kennzeichnen: &f/pvp on");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        pending.remove(id);
    }
}
