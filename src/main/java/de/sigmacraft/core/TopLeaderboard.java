package de.sigmacraft.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class TopLeaderboard {
    private final SigmaCraftCore plugin;
    private final File file;
    private YamlConfiguration data;
    private BukkitTask task;
    private static final String TAG = "sigmacraft_board";
    private static final String ID_TAG = "sigmacraft_board_";
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final Map<String,String> LABELS = Map.of("kills","Kills", "deaths","Tode", "diamonds","Diamanten", "blocks","Abgebaute Blöcke", "playtime","Spielzeit");
    TopLeaderboard(SigmaCraftCore plugin) {
        this.plugin = plugin; this.file = new File(plugin.getDataFolder(), "leaderboards.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
    }
    public void start() { task = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAll, 100L, 200L); }
    public void stop() { if (task != null) task.cancel(); }
    public boolean create(Player p, String metric) {
        if (!LABELS.containsKey(metric)) return false;
        String id = UUID.randomUUID().toString().substring(0, 8);
        Location l = p.getLocation().clone().add(0, 2.2, 0);
        data.set("boards." + id + ".world", l.getWorld().getName());
        data.set("boards." + id + ".x", l.getX()); data.set("boards." + id + ".y", l.getY()); data.set("boards." + id + ".z", l.getZ());
        data.set("boards." + id + ".metric", metric); data.set("boards." + id + ".entity", ""); save();
        refresh(id); return true;
    }
    public boolean removeNearest(Player p) {
        ConfigurationSection boards = data.getConfigurationSection("boards"); if (boards == null) return false;
        String nearest = null; double best = 5 * 5;
        for (String id : boards.getKeys(false)) {
            Location l = location(id); if (l == null || !Objects.equals(l.getWorld(), p.getWorld())) continue;
            double d = l.distanceSquared(p.getLocation()); if (d < best) { best = d; nearest = id; }
        }
        if (nearest == null) return false;
        removeEntities(nearest); data.set("boards." + nearest, null); save(); return true;
    }
    public void refreshAll() {
        ConfigurationSection section = data.getConfigurationSection("boards"); if (section == null) return;
        for (String id : new ArrayList<>(section.getKeys(false))) refresh(id);
    }
    private TextDisplay spawn(String id) {
        Location l = location(id); if (l == null || l.getWorld() == null) return null;
        return l.getWorld().spawn(l, TextDisplay.class, e -> {
            e.setBillboard(Display.Billboard.CENTER); e.setAlignment(TextDisplay.TextAlignment.CENTER);
            e.setSeeThrough(false); e.setShadowed(true); e.setPersistent(true);
            e.setBackgroundColor(org.bukkit.Color.fromARGB(100, 10, 10, 10));
            e.addScoreboardTag(TAG); e.addScoreboardTag(ID_TAG + id);
        });
    }
    private String boardIdOf(TextDisplay d) {
        for (String tag : d.getScoreboardTags()) if (tag.startsWith(ID_TAG)) return tag.substring(ID_TAG.length());
        return null;
    }
    /** Erkennt Hologramme dieses Plugins: per Tag oder (für ältere Versionen ohne Tag) am Text. */
    private boolean isBoardDisplay(Entity e) {
        if (!(e instanceof TextDisplay d)) return false;
        if (d.getScoreboardTags().contains(TAG)) return true;
        String plain = PlainTextComponentSerializer.plainText().serialize(d.text());
        return plain.contains("SIGMACRAFT") && plain.contains("Top 10");
    }
    /** Alle Hologramm-Entities dieser Tafel (auch doppelte und alte ohne Tag in unmittelbarer Nähe). */
    private List<TextDisplay> findDisplays(String id) {
        List<TextDisplay> found = new ArrayList<>();
        Location l = location(id); if (l == null || l.getWorld() == null) return found;
        l.getChunk().getEntities(); // lädt Chunk samt Entities, damit nichts doppelt erzeugt wird
        for (Entity e : l.getWorld().getNearbyEntities(l, 4, 4, 4)) {
            if (!isBoardDisplay(e) || !(e instanceof TextDisplay d)) continue;
            String owner = boardIdOf(d);
            if (owner == null || owner.equals(id)) found.add(d);
        }
        return found;
    }
    private void removeEntities(String id) { for (TextDisplay d : findDisplays(id)) d.remove(); }
    /** Entfernt alle SigmaCraft-Hologramme im Umkreis und erzeugt die gespeicherten neu. */
    public int cleanNear(Player p, double radius) {
        int count = 0;
        for (Entity e : p.getWorld().getNearbyEntities(p.getLocation(), radius, 400, radius, this::isBoardDisplay)) { e.remove(); count++; }
        Bukkit.getScheduler().runTask(plugin, this::refreshAll);
        return count;
    }
    private Location location(String id) {
        String world = data.getString("boards." + id + ".world", "world"); World w = Bukkit.getWorld(world); if (w == null) return null;
        return new Location(w, data.getDouble("boards." + id + ".x"), data.getDouble("boards." + id + ".y"), data.getDouble("boards." + id + ".z"));
    }
    private void refresh(String id) {
        List<TextDisplay> found = findDisplays(id);
        TextDisplay display;
        if (found.isEmpty()) display = spawn(id);
        else {
            display = found.get(0);
            for (int i = 1; i < found.size(); i++) found.get(i).remove(); // doppelte entfernen
            display.addScoreboardTag(TAG); display.addScoreboardTag(ID_TAG + id);
        }
        if (display == null) return;
        data.set("boards." + id + ".entity", display.getUniqueId().toString());
        String metric = data.getString("boards." + id + ".metric", "kills");
        List<SigmaCraftCore.RankRow> rows = plugin.top(metric, 10);
        StringBuilder out = new StringBuilder("&b&lSIGMACRAFT &8• &fTop 10\n&7").append(LABELS.getOrDefault(metric, metric)).append("\n");
        int n = 1; for (SigmaCraftCore.RankRow row : rows) {
            String value = metric.equals("playtime") ? plugin.formatTime(row.value()) : Long.toString(row.value());
            out.append(n++).append(". &f").append(row.name()).append(" &8- &b").append(value).append("\n");
        }
        display.text(LEGACY.deserialize(out.toString().trim()));
    }
    private void save() { try { data.save(file); } catch (IOException e) { plugin.getLogger().warning("Leaderboards konnten nicht gespeichert werden: " + e.getMessage()); } }
}
