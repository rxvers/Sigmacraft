package de.sigmacraft.core;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.data.Openable;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Anti-Klau-Log für Admins: protokolliert, wer Behälter (Kisten, Fässer, Öfen ...) öffnet und was er
 * herausnimmt oder hineinlegt, wer Türen benutzt und wer Behälter zerstört. Gespeichert in activity.log.
 */
final class ActivityLog implements Listener {
    record Entry(long time, UUID player, String name, String kind, String block, String world, int x, int y, int z, String detail) { }

    private record Snapshot(Map<Material, Integer> items) { }

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM. HH:mm").withZone(ZoneId.of("Europe/Berlin"));
    private static final String PRE = "&8[&bLog&8] ";
    private static final Set<InventoryType> LOGGED_TYPES = EnumSet.of(InventoryType.CHEST, InventoryType.BARREL,
            InventoryType.SHULKER_BOX, InventoryType.HOPPER, InventoryType.DISPENSER, InventoryType.DROPPER,
            InventoryType.FURNACE, InventoryType.BLAST_FURNACE, InventoryType.SMOKER, InventoryType.BREWING);

    private final SigmaCraftCore plugin;
    private final Path path;
    private final List<Entry> entries = new ArrayList<>();
    private final Map<UUID, Snapshot> open = new HashMap<>();
    private final Set<UUID> inspecting = new HashSet<>();
    private final Map<String, Long> recentDoor = new HashMap<>();

    ActivityLog(SigmaCraftCore plugin) {
        this.plugin = plugin;
        this.path = new java.io.File(plugin.getDataFolder(), "activity.log").toPath();
        load();
    }

    // ---------- Einstellungen ----------

    private boolean enabled() { return plugin.getConfig().getBoolean("accesslog.enabled", true); }

    private boolean logDoors() { return plugin.getConfig().getBoolean("accesslog.log-doors", true); }

    private int maxEntries() { return Math.max(1000, plugin.getConfig().getInt("accesslog.max-entries", 50_000)); }

    // ---------- Speichern / Laden ----------

    private static String serialize(Entry e) {
        return e.time() + "\t" + e.player() + "\t" + e.name() + "\t" + e.kind() + "\t" + e.block() + "\t" + e.world()
                + "\t" + e.x() + "\t" + e.y() + "\t" + e.z() + "\t" + e.detail().replace('\t', ' ').replace('\n', ' ');
    }

    private static Entry parse(String line) {
        String[] f = line.split("\t", -1);
        if (f.length < 10) return null;
        try {
            return new Entry(Long.parseLong(f[0]), UUID.fromString(f[1]), f[2], f[3], f[4], f[5],
                    Integer.parseInt(f[6]), Integer.parseInt(f[7]), Integer.parseInt(f[8]), f[9]);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void load() {
        if (!Files.exists(path)) return;
        long cutoff = System.currentTimeMillis() - Math.max(1, plugin.getConfig().getInt("accesslog.retention-days", 14)) * 86_400_000L;
        boolean trimmed = false;
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                Entry e = parse(line);
                if (e == null || e.time() < cutoff) { trimmed = true; continue; }
                entries.add(e);
            }
            while (entries.size() > maxEntries()) { entries.remove(0); trimmed = true; }
            if (trimmed) {
                List<String> lines = new ArrayList<>();
                for (Entry e : entries) lines.add(serialize(e));
                Files.write(path, lines, StandardCharsets.UTF_8);
            }
        } catch (IOException ex) {
            plugin.getLogger().warning("activity.log konnte nicht gelesen werden: " + ex.getMessage());
        }
    }

    private synchronized void add(Entry e) {
        entries.add(e);
        if (entries.size() > maxEntries()) entries.remove(0);
        try {
            plugin.getDataFolder().mkdirs();
            Files.writeString(path, serialize(e) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            plugin.getLogger().warning("activity.log konnte nicht geschrieben werden: " + ex.getMessage());
        }
    }

    private void log(Player p, String kind, String block, Location l, String detail) {
        if (l.getWorld() == null) return;
        add(new Entry(System.currentTimeMillis(), p.getUniqueId(), p.getName(), kind, block, l.getWorld().getName(),
                l.getBlockX(), l.getBlockY(), l.getBlockZ(), detail));
    }

    // ---------- Abfragen (neueste zuerst) ----------

    synchronized List<Entry> byId(UUID id, int limit) {
        List<Entry> result = new ArrayList<>();
        for (int i = entries.size() - 1; i >= 0 && result.size() < limit; i--) if (entries.get(i).player().equals(id)) result.add(entries.get(i));
        return result;
    }

    synchronized List<Entry> byName(String name, int limit) {
        List<Entry> result = new ArrayList<>();
        for (int i = entries.size() - 1; i >= 0 && result.size() < limit; i--) if (entries.get(i).name().equalsIgnoreCase(name)) result.add(entries.get(i));
        return result;
    }

    synchronized List<Entry> near(Location loc, double radius, int limit) {
        List<Entry> result = new ArrayList<>();
        if (loc.getWorld() == null) return result;
        double r2 = radius * radius;
        for (int i = entries.size() - 1; i >= 0 && result.size() < limit; i--) {
            Entry e = entries.get(i);
            if (!e.world().equals(loc.getWorld().getName())) continue;
            double dx = e.x() - loc.getX(), dy = e.y() - loc.getY(), dz = e.z() - loc.getZ();
            if (dx * dx + dy * dy + dz * dz <= r2) result.add(e);
        }
        return result;
    }

    /** Einträge an genau diesem Block (inkl. zweiter Hälfte einer Doppelkiste). */
    synchronized List<Entry> at(Location loc, int limit) {
        List<Entry> result = new ArrayList<>();
        if (loc.getWorld() == null) return result;
        for (int i = entries.size() - 1; i >= 0 && result.size() < limit; i--) {
            Entry e = entries.get(i);
            if (!e.world().equals(loc.getWorld().getName()) || e.y() != loc.getBlockY()) continue;
            int dx = e.x() - loc.getBlockX(), dz = e.z() - loc.getBlockZ();
            if (dx * dx + dz * dz <= 2) result.add(e);
        }
        return result;
    }

    // ---------- Darstellung ----------

    static String pretty(String material) {
        StringBuilder sb = new StringBuilder();
        for (String part : material.toLowerCase(Locale.ROOT).split("_")) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    static String action(Entry e) {
        return switch (e.kind()) {
            case "CONTAINER" -> "öffnete";
            case "DOOR" -> "benutzte";
            case "BREAK" -> "zerstörte";
            default -> "?";
        };
    }

    /** Färbt Änderungen: "-3 Diamond" rot, "+1 Stone" grün. */
    static String colorDetail(String detail) {
        if (detail.isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        for (String token : detail.split(", ")) parts.add((token.startsWith("-") ? "&c" : token.startsWith("+") ? "&a" : "&7") + token);
        return String.join("&7, ", parts);
    }

    static String time(Entry e) { return DATE.format(Instant.ofEpochMilli(e.time())); }

    private String line(Entry e) {
        String detail = e.detail().isEmpty() ? "" : " &8| " + colorDetail(e.detail());
        return "&8" + time(e) + " &f" + e.name() + " &7" + action(e) + " &e" + pretty(e.block())
                + " &8(" + e.x() + ", " + e.y() + ", " + e.z() + ")" + detail;
    }

    private void msg(CommandSender to, String text) { to.sendMessage(LEGACY.deserialize(text)); }

    private void send(CommandSender to, String title, List<Entry> list) {
        msg(to, "&8▬▬▬ &b&lZUGRIFFS-LOG &8▬▬▬ &7" + title);
        if (list.isEmpty()) msg(to, "&7Keine Einträge.");
        for (Entry e : list) msg(to, line(e));
    }

    boolean isInspecting(UUID id) { return inspecting.contains(id); }

    void toggleInspect(Player p) {
        if (inspecting.remove(p.getUniqueId())) {
            msg(p, PRE + "&7Inspektor &cAUS&7.");
        } else {
            inspecting.add(p.getUniqueId());
            msg(p, PRE + "&7Inspektor &aAN&7: Rechtsklick auf einen Block zeigt seinen Verlauf (Behälter öffnen sich dabei nicht).");
        }
    }

    // ---------- Befehl /zugriffe ----------

    boolean command(CommandSender sender, String[] args) {
        if (!sender.hasPermission("sigmacraft.admin")) { msg(sender, "&cDazu hast du keine Berechtigung."); return true; }
        String sub = args.length >= 1 ? args[0].toLowerCase(Locale.ROOT) : "help";
        switch (sub) {
            case "inspect", "inspektor" -> {
                if (sender instanceof Player p) toggleInspect(p); else sender.sendMessage("Nur ingame verfügbar.");
            }
            case "near", "hier" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Nur ingame verfügbar."); return true; }
                double radius = 10;
                if (args.length >= 2) { try { radius = Math.min(100, Math.max(1, Double.parseDouble(args[1]))); } catch (NumberFormatException ignored) { } }
                send(sender, "im Umkreis von " + (int) radius + " Blöcken", near(p.getLocation(), radius, 15));
            }
            case "player", "spieler" -> {
                if (args.length < 2) { msg(sender, PRE + "&c/zugriffe player <Name> [Anzahl]"); return true; }
                int limit = 15;
                if (args.length >= 3) { try { limit = Math.min(50, Math.max(1, Integer.parseInt(args[2]))); } catch (NumberFormatException ignored) { } }
                send(sender, args[1], byName(args[1], limit));
            }
            default -> {
                msg(sender, "&8▬▬▬ &b&lZUGRIFFS-LOG &8▬▬▬");
                msg(sender, "&b/zugriffe inspect &7- Inspektor: Rechtsklick auf Block zeigt Verlauf");
                msg(sender, "&b/zugriffe near &7[Radius] - Einträge in deiner Nähe");
                msg(sender, "&b/zugriffe player &7<Name> [Anzahl] - Einträge eines Spielers");
                msg(sender, "&7Auch im Admin-Panel bei jedem Spieler (&f/admin&7).");
            }
        }
        return true;
    }

    // ---------- Ereignisse ----------

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInspect(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!inspecting.contains(p.getUniqueId()) || e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        if (!p.hasPermission("sigmacraft.admin")) { inspecting.remove(p.getUniqueId()); return; }
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Block block = e.getClickedBlock();
        send(p, pretty(block.getType().name()) + " (" + block.getX() + ", " + block.getY() + ", " + block.getZ() + ")", at(block.getLocation(), 10));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDoor(PlayerInteractEvent e) {
        if (!enabled() || !logDoors() || e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Block block = e.getClickedBlock();
        if (block == null || !(block.getBlockData() instanceof Openable) || block.getType().name().contains("IRON")) return;
        String key = e.getPlayer().getUniqueId() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
        long now = System.currentTimeMillis();
        if (now - recentDoor.getOrDefault(key, 0L) < 3000L) return;
        recentDoor.put(key, now);
        if (recentDoor.size() > 2000) recentDoor.values().removeIf(t -> now - t > 3000L);
        log(e.getPlayer(), "DOOR", block.getType().name(), block.getLocation(), "");
    }

    @EventHandler(ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (!enabled() || !(e.getPlayer() instanceof Player p)) return;
        Inventory inv = e.getInventory();
        if (!LOGGED_TYPES.contains(inv.getType()) || inv.getLocation() == null) return;
        open.put(p.getUniqueId(), new Snapshot(count(inv)));
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        Snapshot before = open.remove(p.getUniqueId());
        Inventory inv = e.getInventory();
        if (before == null || inv.getLocation() == null) return;
        Location loc = inv.getLocation();
        Block block = loc.getBlock();
        String material = block.getType() == Material.AIR ? inv.getType().name() : block.getType().name();
        log(p, "CONTAINER", material, loc, diff(before.items(), count(inv)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!enabled()) return;
        Block block = e.getBlock();
        if (block.getState(false) instanceof Container) log(e.getPlayer(), "BREAK", block.getType().name(), block.getLocation(), "");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        open.remove(e.getPlayer().getUniqueId());
        inspecting.remove(e.getPlayer().getUniqueId());
    }

    // ---------- Inhalte vergleichen ----------

    private Map<Material, Integer> count(Inventory inv) {
        Map<Material, Integer> map = new EnumMap<>(Material.class);
        for (ItemStack item : inv.getContents()) {
            if (item != null && item.getType() != Material.AIR) map.merge(item.getType(), item.getAmount(), Integer::sum);
        }
        return map;
    }

    private String diff(Map<Material, Integer> before, Map<Material, Integer> after) {
        List<String> changes = new ArrayList<>();
        Set<Material> all = EnumSet.noneOf(Material.class);
        all.addAll(before.keySet());
        all.addAll(after.keySet());
        for (Material m : all) {
            int d = after.getOrDefault(m, 0) - before.getOrDefault(m, 0);
            if (d != 0) changes.add((d > 0 ? "+" : "") + d + " " + pretty(m.name()));
        }
        if (changes.isEmpty()) return "";
        if (changes.size() > 6) {
            int more = changes.size() - 6;
            changes = new ArrayList<>(changes.subList(0, 6));
            changes.add("… " + more + " weitere");
        }
        return String.join(", ", changes);
    }
}
