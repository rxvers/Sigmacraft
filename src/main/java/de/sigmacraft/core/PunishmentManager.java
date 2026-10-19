package de.sigmacraft.core;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Eigene Ban-/Mute-Verwaltung mit Dauer, Grund, Strafen-Log und gespeicherten IPs (punishments.yml). */
final class PunishmentManager {
    record Entry(String type, String by, String reason, long time, long until) { }

    record Active(long until, String reason, String by, long since) {
        boolean expired() { return until != -1L && System.currentTimeMillis() >= until; }
    }

    private final File file;
    private final Map<UUID, Active> bans = new HashMap<>();
    private final Map<UUID, Active> mutes = new HashMap<>();
    private final Map<UUID, List<Entry>> history = new HashMap<>();
    private final Map<UUID, String> ips = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();

    PunishmentManager(File file) {
        this.file = file;
        load();
    }

    // ---------- Laden / Speichern ----------

    private synchronized void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        loadActive(y, "bans", bans);
        loadActive(y, "mutes", mutes);
        ConfigurationSection h = y.getConfigurationSection("history");
        if (h != null) {
            for (String key : h.getKeys(false)) {
                ConfigurationSection u = h.getConfigurationSection(key);
                if (u == null) continue;
                UUID id;
                try { id = UUID.fromString(key); } catch (IllegalArgumentException e) { continue; }
                List<Entry> list = new ArrayList<>();
                for (String idx : u.getKeys(false)) {
                    String b = idx + ".";
                    list.add(new Entry(u.getString(b + "type", "?"), u.getString(b + "by", "?"),
                            u.getString(b + "reason", ""), u.getLong(b + "time"), u.getLong(b + "until", -1L)));
                }
                history.put(id, list);
            }
        }
        loadStrings(y, "ips", ips);
        loadStrings(y, "names", names);
    }

    private void loadActive(YamlConfiguration y, String path, Map<UUID, Active> target) {
        ConfigurationSection s = y.getConfigurationSection(path);
        if (s == null) return;
        for (String key : s.getKeys(false)) {
            try {
                target.put(UUID.fromString(key), new Active(s.getLong(key + ".until", -1L), s.getString(key + ".reason", ""),
                        s.getString(key + ".by", "?"), s.getLong(key + ".since")));
            } catch (IllegalArgumentException ignored) { }
        }
    }

    private void loadStrings(YamlConfiguration y, String path, Map<UUID, String> target) {
        ConfigurationSection s = y.getConfigurationSection(path);
        if (s == null) return;
        for (String key : s.getKeys(false)) {
            try { target.put(UUID.fromString(key), s.getString(key, "")); } catch (IllegalArgumentException ignored) { }
        }
    }

    synchronized void save() {
        YamlConfiguration y = new YamlConfiguration();
        saveActive(y, "bans", bans);
        saveActive(y, "mutes", mutes);
        for (Map.Entry<UUID, List<Entry>> e : history.entrySet()) {
            int i = 0;
            for (Entry en : e.getValue()) {
                String b = "history." + e.getKey() + "." + (i++) + ".";
                y.set(b + "type", en.type());
                y.set(b + "by", en.by());
                y.set(b + "reason", en.reason());
                y.set(b + "time", en.time());
                y.set(b + "until", en.until());
            }
        }
        for (Map.Entry<UUID, String> e : ips.entrySet()) y.set("ips." + e.getKey(), e.getValue());
        for (Map.Entry<UUID, String> e : names.entrySet()) y.set("names." + e.getKey(), e.getValue());
        try {
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            y.save(file);
        } catch (IOException ex) {
            System.err.println("[SigmaCraftCore] punishments.yml konnte nicht gespeichert werden: " + ex.getMessage());
        }
    }

    private void saveActive(YamlConfiguration y, String path, Map<UUID, Active> source) {
        for (Map.Entry<UUID, Active> e : source.entrySet()) {
            String b = path + "." + e.getKey() + ".";
            y.set(b + "until", e.getValue().until());
            y.set(b + "reason", e.getValue().reason());
            y.set(b + "by", e.getValue().by());
            y.set(b + "since", e.getValue().since());
        }
    }

    // ---------- Abfragen ----------

    synchronized Active getBan(UUID id) { return active(bans, id); }

    synchronized Active getMute(UUID id) { return active(mutes, id); }

    private Active active(Map<UUID, Active> map, UUID id) {
        Active a = map.get(id);
        if (a == null) return null;
        if (a.expired()) {
            map.remove(id);
            save();
            return null;
        }
        return a;
    }

    /** Strafen-Log, neueste zuerst. */
    synchronized List<Entry> history(UUID id) {
        List<Entry> copy = new ArrayList<>(history.getOrDefault(id, List.of()));
        Collections.reverse(copy);
        return copy;
    }

    synchronized String ip(UUID id) { return ips.get(id); }

    synchronized String knownName(UUID id) { return names.get(id); }

    synchronized UUID findByName(String name) {
        for (Map.Entry<UUID, String> e : names.entrySet()) {
            if (e.getValue().equalsIgnoreCase(name)) return e.getKey();
        }
        return null;
    }

    /** Namen anderer Accounts, die dieselbe IP genutzt haben. */
    synchronized List<String> sameIp(UUID self, String ip) {
        List<String> result = new ArrayList<>();
        for (Map.Entry<UUID, String> e : ips.entrySet()) {
            if (!e.getKey().equals(self) && ip.equals(e.getValue())) {
                result.add(names.getOrDefault(e.getKey(), e.getKey().toString().substring(0, 8)));
            }
        }
        return result;
    }

    // ---------- Aktionen ----------

    synchronized void recordJoin(UUID id, String name, String ip) {
        names.put(id, name);
        if (ip != null) ips.put(id, ip);
        save();
    }

    synchronized void ban(UUID id, String name, long durationMs, String reason, String by) {
        apply(bans, "BAN", id, name, durationMs, reason, by);
    }

    synchronized void mute(UUID id, String name, long durationMs, String reason, String by) {
        apply(mutes, "MUTE", id, name, durationMs, reason, by);
    }

    private void apply(Map<UUID, Active> map, String type, UUID id, String name, long durationMs, String reason, String by) {
        long now = System.currentTimeMillis();
        long until = durationMs < 0 ? -1L : now + durationMs;
        map.put(id, new Active(until, reason, by, now));
        history.computeIfAbsent(id, k -> new ArrayList<>()).add(new Entry(type, by, reason, now, until));
        if (name != null) names.put(id, name);
        save();
    }

    synchronized boolean unban(UUID id, String by) { return lift(bans, "UNBAN", id, by); }

    synchronized boolean unmute(UUID id, String by) { return lift(mutes, "UNMUTE", id, by); }

    private boolean lift(Map<UUID, Active> map, String type, UUID id, String by) {
        if (map.remove(id) == null) return false;
        history.computeIfAbsent(id, k -> new ArrayList<>()).add(new Entry(type, by, "", System.currentTimeMillis(), -1L));
        save();
        return true;
    }

    // ---------- Hilfen für Zeitangaben ----------

    /** Liefert Millisekunden, -1 für permanent, -2 bei ungültiger Eingabe. Beispiele: 30m, 6h, 2d, 1w, perm. */
    static long parseDuration(String input) {
        String s = input.trim().toLowerCase(Locale.ROOT);
        if (s.equals("perm") || s.equals("permanent") || s.equals("dauerhaft")) return -1L;
        if (s.length() < 2) return -2L;
        long n;
        try { n = Long.parseLong(s.substring(0, s.length() - 1)); } catch (NumberFormatException e) { return -2L; }
        if (n <= 0 || n > 100_000L) return -2L;
        long mult = switch (s.charAt(s.length() - 1)) {
            case 'm' -> 60_000L;
            case 'h' -> 3_600_000L;
            case 'd' -> 86_400_000L;
            case 'w' -> 604_800_000L;
            default -> 0L;
        };
        return mult == 0L ? -2L : n * mult;
    }

    static String formatDuration(long ms) {
        long totalMin = Math.max(1L, ms / 60_000L);
        long d = totalMin / 1440L, h = (totalMin % 1440L) / 60L, m = totalMin % 60L;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append(d == 1 ? " Tag " : " Tage ");
        if (h > 0) sb.append(h).append(" Std ");
        if (m > 0 && d == 0) sb.append(m).append(" Min");
        return sb.toString().trim();
    }

    static String remaining(Active a) {
        return a.until() == -1L ? "Permanent" : formatDuration(a.until() - System.currentTimeMillis());
    }
}
