package de.sigmacraft.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
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
        spawn(id); refresh(id); return true;
    }
    public boolean removeNearest(Player p) {
        ConfigurationSection boards = data.getConfigurationSection("boards"); if (boards == null) return false;
        String nearest = null; double best = 5 * 5;
        for (String id : boards.getKeys(false)) {
            Location l = location(id); if (l == null || !Objects.equals(l.getWorld(), p.getWorld())) continue;
            double d = l.distanceSquared(p.getLocation()); if (d < best) { best = d; nearest = id; }
        }
        if (nearest == null) return false;
        removeEntity(nearest); data.set("boards." + nearest, null); save(); return true;
    }
    public void refreshAll() {
        ConfigurationSection section = data.getConfigurationSection("boards"); if (section == null) return;
        for (String id : new ArrayList<>(section.getKeys(false))) { if (findEntity(id) == null) spawn(id); refresh(id); }
    }
    private void spawn(String id) {
        Location l = location(id); if (l == null || l.getWorld() == null) return;
        TextDisplay display = l.getWorld().spawn(l, TextDisplay.class, e -> {
            e.setBillboard(Display.Billboard.CENTER); e.setAlignment(TextDisplay.TextAlignment.CENTER);
            e.setSeeThrough(false); e.setShadowed(true); e.setPersistent(true);
            e.setBackgroundColor(org.bukkit.Color.fromARGB(100, 10, 10, 10));
        });
        data.set("boards." + id + ".entity", display.getUniqueId().toString()); save();
    }
    private Entity findEntity(String id) {
        String raw = data.getString("boards." + id + ".entity", "");
        try {
            UUID uuid = UUID.fromString(raw);
            // Ensure the board's chunk is loaded before looking up its persistent TextDisplay.
            Location boardLocation = location(id);
            if (boardLocation != null && boardLocation.getWorld() != null) {
                boardLocation.getChunk().load();
                Entity entity = boardLocation.getWorld().getEntity(uuid);
                if (entity != null) return entity;
            }
        } catch (IllegalArgumentException ignored) { }
        return null;
    }
    private void removeEntity(String id) { Entity e = findEntity(id); if (e != null) e.remove(); }
    private Location location(String id) {
        String world = data.getString("boards." + id + ".world", "world"); World w = Bukkit.getWorld(world); if (w == null) return null;
        return new Location(w, data.getDouble("boards." + id + ".x"), data.getDouble("boards." + id + ".y"), data.getDouble("boards." + id + ".z"));
    }
    private void refresh(String id) {
        Entity entity = findEntity(id); if (!(entity instanceof TextDisplay display)) return;
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
