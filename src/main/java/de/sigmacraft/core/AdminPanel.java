package de.sigmacraft.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Ingame-Admin-Panel: /admin öffnet eine Spielerliste mit IP, Enderchest, Ban, Mute und Strafen-Log. */
final class AdminPanel implements Listener {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.of("Europe/Berlin"));
    private static final String PRE = "&8[&cAdmin&8] ";
    private static final String PERM = "sigmacraft.admin";
    private static final Set<String> MUTED_COMMANDS = Set.of("msg", "tell", "w", "whisper", "r", "reply", "me", "say", "teammsg", "tm", "t");

    private static final long HOUR = 3_600_000L;
    private static final long DAY = 24L * HOUR;
    private static final long[] DURATIONS = {HOUR, 3 * HOUR, 12 * HOUR, DAY, 3 * DAY, 7 * DAY, 30 * DAY, -1L};
    private static final String[] DURATION_NAMES = {"1 Stunde", "3 Stunden", "12 Stunden", "1 Tag", "3 Tage", "7 Tage", "30 Tage", "Permanent"};

    private final SigmaCraftCore plugin;
    private final PunishmentManager punishments;
    private static final Pattern UUID_FILE = Pattern.compile("^([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\\.(dat|dat_old|json)$");
    private static final Set<String> SKIP_DIRS = Set.of("region", "entities", "poi", "DIM-1", "DIM1", "datapacks", "generated", "plugins", "logs", "libraries", "cache", "versions", "crash-reports", ".paper-remap", "mods");
    private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();
    private final Map<UUID, Long> loginLocks = new ConcurrentHashMap<>();
    private final Set<UUID> frozen = new HashSet<>();
    private volatile long globalLock = 0L;

    /** Wartet auf eine Chat-Eingabe des Admins (Dauer und/oder Grund). */
    private static final class Prompt {
        final UUID target;
        final String name;
        final boolean ban;
        long duration; // -2 = noch nicht gewählt

        Prompt(UUID target, String name, boolean ban, long duration) {
            this.target = target;
            this.name = name;
            this.ban = ban;
            this.duration = duration;
        }
    }

    private static final class PanelHolder implements InventoryHolder {
        final String view;
        final UUID target;
        final String name;
        final boolean ban;
        final int page;
        final List<UUID> ids = new ArrayList<>();
        String category;
        boolean armed;
        Inventory inv;

        PanelHolder(String view, UUID target, String name, boolean ban, int page) {
            this.view = view;
            this.target = target;
            this.name = name;
            this.ban = ban;
            this.page = page;
        }

        @Override
        public Inventory getInventory() { return inv; }
    }

    AdminPanel(SigmaCraftCore plugin, PunishmentManager punishments) {
        this.plugin = plugin;
        this.punishments = punishments;
        loadFrozen();
    }

    // ---------- Einfrieren bleibt über Logout/Login und Neustarts bestehen ----------

    private File frozenFile() { return new File(plugin.getDataFolder(), "frozen.yml"); }

    private void loadFrozen() {
        for (String raw : YamlConfiguration.loadConfiguration(frozenFile()).getStringList("frozen")) {
            try { frozen.add(UUID.fromString(raw)); } catch (IllegalArgumentException ignored) { }
        }
    }

    private void saveFrozen() {
        YamlConfiguration y = new YamlConfiguration();
        List<String> list = new ArrayList<>();
        for (UUID id : frozen) list.add(id.toString());
        y.set("frozen", list);
        try {
            plugin.getDataFolder().mkdirs();
            y.save(frozenFile());
        } catch (IOException ex) {
            plugin.getLogger().warning("frozen.yml konnte nicht gespeichert werden: " + ex.getMessage());
        }
    }

    // ---------- Hilfen ----------

    private void msg(CommandSender to, String text) { to.sendMessage(LEGACY.deserialize(text)); }

    private Component text(String amp) { return LEGACY.deserialize(amp).decoration(TextDecoration.ITALIC, false); }

    private ItemStack item(Material material, String name, String... lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(text(name));
        if (lore.length > 0) {
            List<Component> lines = new ArrayList<>();
            for (String line : lore) lines.add(text(line));
            meta.lore(lines);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private Inventory create(PanelHolder holder, int size, String title) {
        Inventory inv = Bukkit.createInventory(holder, size, LEGACY.deserialize(title));
        holder.inv = inv;
        return inv;
    }

    private void later(Runnable action) { Bukkit.getScheduler().runTask(plugin, action); }

    private Component banScreen(PunishmentManager.Active ban) {
        return LEGACY.deserialize("&c&lDu wurdest gesperrt\n\n&7Grund: &f" + ban.reason()
                + "\n&7Dauer: &f" + (ban.until() == -1L ? "Permanent" : "noch " + PunishmentManager.remaining(ban))
                + "\n\n&8SigmaCraft");
    }

    private String muteText(PunishmentManager.Active mute) {
        return "&cDu bist stumm geschaltet. &7Grund: &f" + mute.reason() + " &7| Verbleibend: &f" + PunishmentManager.remaining(mute);
    }

    private String describeDuration(long ms) { return ms < 0 ? "Permanent" : PunishmentManager.formatDuration(ms); }

    // ---------- Befehl ----------

    boolean command(CommandSender sender, String[] args) {
        if (!(sender instanceof Player admin)) { sender.sendMessage("Nur ingame verfügbar."); return true; }
        if (!admin.hasPermission(PERM)) { msg(admin, "&cDazu hast du keine Berechtigung."); return true; }
        if (args.length >= 1) {
            UUID id = plugin.resolveId(args[0], false);
            if (id == null) id = punishments.findByName(args[0]);
            if (id == null) { msg(admin, PRE + "&cSpieler nicht gefunden."); return true; }
            openPlayer(admin, id);
        } else {
            openList(admin, 0);
        }
        return true;
    }

    // ---------- Menüs ----------

    private void openList(Player admin, int page) {
        List<Player> list = new ArrayList<>(Bukkit.getOnlinePlayers());
        list.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        int pages = Math.max(1, (list.size() + 44) / 45);
        int current = Math.max(0, Math.min(page, pages - 1));
        PanelHolder holder = new PanelHolder("list", null, null, false, current);
        Inventory inv = create(holder, 54, "&8Admin-Panel &7(" + list.size() + " online)");
        for (int i = 0; i < 45 && current * 45 + i < list.size(); i++) {
            Player target = list.get(current * 45 + i);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(target);
            meta.displayName(text("&f" + target.getName()));
            List<Component> lore = new ArrayList<>();
            if (punishments.getBan(target.getUniqueId()) != null) lore.add(text("&cGebannt"));
            if (punishments.getMute(target.getUniqueId()) != null) lore.add(text("&6Stumm"));
            lore.add(text("&7Klicken für Optionen"));
            meta.lore(lore);
            head.setItemMeta(meta);
            inv.setItem(i, head);
            holder.ids.add(target.getUniqueId());
        }
        if (current > 0) inv.setItem(45, item(Material.ARROW, "&eVorherige Seite"));
        inv.setItem(46, dimensionItem("nether"));
        inv.setItem(47, dimensionItem("end"));
        inv.setItem(48, item(Material.TNT, "&4Daten löschen (ALLE Spieler)", "&7Stats, Achievements, Inventar ...", "&cMit Bestätigung"));
        inv.setItem(49, item(Material.PAPER, "&7Offline-Spieler", "&8Nutze &f/admin <Name>"));
        boolean restricted = plugin.access().isRestricted();
        inv.setItem(50, item(restricted ? Material.IRON_DOOR : Material.OAK_DOOR,
                restricted ? "&cVorab-Zugang: AKTIV" : "&aVorab-Zugang: AUS (Server offen)",
                "&7Nur zugelassene Spieler dürfen joinen,", "&7auch wenn andere gewhitelisted sind.",
                "&7Zugelassen: &f" + plugin.access().count(), "&8Klicken zum Umschalten"));
        if (current < pages - 1) inv.setItem(53, item(Material.ARROW, "&eNächste Seite"));
        admin.openInventory(inv);
    }

    private ItemStack dimensionItem(String which) {
        boolean disabled = plugin.isDimensionDisabled(which);
        String label = which.equals("nether") ? "Nether" : "End";
        return item(which.equals("nether") ? Material.NETHERRACK : Material.END_STONE,
                (disabled ? "&c" : "&a") + label + ": " + (disabled ? "DEAKTIVIERT" : "aktiv"),
                "&7Klicken zum Umschalten", "&8Admins sind ausgenommen");
    }

    private void openPlayer(Player admin, UUID id) {
        String name = plugin.nameOf(id);
        PanelHolder holder = new PanelHolder("player", id, name, false, 0);
        Inventory inv = create(holder, 36, "&8Spieler: &f" + name);
        PunishmentManager.Active ban = punishments.getBan(id);
        PunishmentManager.Active mute = punishments.getMute(id);
        Player online = Bukkit.getPlayer(id);
        String onlineOnly = "&cNur für Online-Spieler";

        inv.setItem(4, item(Material.PLAYER_HEAD, "&f" + name, online != null ? "&aOnline" : "&7Offline"));
        inv.setItem(10, item(Material.NAME_TAG, "&bIP-Adresse", "&7Klicken, um die IP im Chat zu sehen"));
        inv.setItem(11, item(Material.CHEST, "&6Inventar ansehen", online != null ? "&7Nur Ansicht, nichts veränderbar" : onlineOnly));
        inv.setItem(12, item(Material.ENDER_CHEST, "&5Enderchest ansehen", online != null ? "&7Nur Ansicht, nichts veränderbar" : onlineOnly));
        inv.setItem(14, ban == null
                ? item(Material.BARRIER, "&cBannen", "&7Dauer und Grund wählen")
                : item(Material.EMERALD, "&aEntbannen", "&7Grund: &f" + ban.reason(), "&7Verbleibend: &f" + PunishmentManager.remaining(ban)));
        inv.setItem(16, mute == null
                ? item(Material.NOTE_BLOCK, "&6Stumm schalten", "&7Dauer und Grund wählen")
                : item(Material.LIME_DYE, "&aEntmuten", "&7Grund: &f" + mute.reason(), "&7Verbleibend: &f" + PunishmentManager.remaining(mute)));
        inv.setItem(19, item(Material.ENDER_PEARL, "&dZu Spieler teleportieren", online != null ? "&7Du wirst zu ihm gebracht" : onlineOnly));
        inv.setItem(20, item(Material.LEAD, "&dSpieler zu mir holen", online != null ? "&7Er wird zu dir gebracht" : onlineOnly));
        boolean isFrozen = frozen.contains(id);
        inv.setItem(21, isFrozen
                ? item(Material.CAMPFIRE, "&6Auftauen", "&7Spieler kann sich wieder bewegen")
                : item(Material.ICE, "&bEinfrieren", online != null ? "&7Spieler kann sich nicht mehr bewegen" : onlineOnly));
        boolean listed = plugin.access().isAllowed(name);
        inv.setItem(22, item(listed ? Material.WRITABLE_BOOK : Material.BOOK, listed ? "&aVorab-Zugang: erlaubt" : "&7Vorab-Zugang: nicht erlaubt",
                "&7Klicken zum " + (listed ? "Entziehen" : "Erlauben")));
        inv.setItem(23, item(Material.BOOK, "&eStrafen-Log", "&7Erhaltene Strafen: &f" + punishments.history(id).size()));
        inv.setItem(25, item(Material.TNT, "&4Daten löschen", "&7Stats, Achievements, Inventar ...", "&cMit Bestätigung"));
        inv.setItem(27, item(Material.ARROW, "&eZurück"));
        admin.openInventory(inv);
    }

    private void openInv(Player admin, UUID id) {
        Player target = Bukkit.getPlayer(id);
        if (target == null) { msg(admin, PRE + "&cDas Inventar ist nur für Online-Spieler verfügbar."); return; }
        PanelHolder holder = new PanelHolder("inv", id, target.getName(), false, 0);
        Inventory inv = create(holder, 54, "&6Inventar: &f" + target.getName());
        PlayerInventory pi = target.getInventory();
        ItemStack[] storage = pi.getStorageContents();
        for (int i = 9; i < 36 && i < storage.length; i++) if (storage[i] != null) inv.setItem(i - 9, storage[i].clone());
        for (int i = 0; i < 9 && i < storage.length; i++) if (storage[i] != null) inv.setItem(27 + i, storage[i].clone());
        if (pi.getHelmet() != null) inv.setItem(45, pi.getHelmet().clone());
        if (pi.getChestplate() != null) inv.setItem(46, pi.getChestplate().clone());
        if (pi.getLeggings() != null) inv.setItem(47, pi.getLeggings().clone());
        if (pi.getBoots() != null) inv.setItem(48, pi.getBoots().clone());
        if (pi.getItemInOffHand().getType() != Material.AIR) inv.setItem(50, pi.getItemInOffHand().clone());
        inv.setItem(53, item(Material.ARROW, "&eZurück"));
        admin.openInventory(inv);
    }

    private String categoryName(String category) {
        return switch (category) {
            case "stats" -> "Statistiken";
            case "ach" -> "Achievements";
            case "inv" -> "Inventar & Spielerdaten";
            default -> "ALLE Daten";
        };
    }

    private void openClear(Player admin, UUID target, String name) {
        PanelHolder holder = new PanelHolder("clear", target, name, false, 0);
        Inventory inv = create(holder, 27, "&4Daten löschen: &f" + (target == null ? "ALLE Spieler" : name));
        inv.setItem(10, item(Material.PAPER, "&eStatistiken", "&7Kills, Tode, Blöcke, Diamanten, Spielzeit", "&7und Vanilla-Statistiken"));
        inv.setItem(12, item(Material.NETHER_STAR, "&eAchievements", "&7Alle Fortschritte (Advancements)"));
        inv.setItem(14, item(Material.CHEST, "&eInventar & Spielerdaten", "&7Inventar, Enderchest, XP, Position"));
        inv.setItem(16, item(Material.TNT, "&4ALLES", "&7Statistiken, Achievements und Spielerdaten"));
        inv.setItem(18, item(Material.ARROW, "&eZurück"));
        admin.openInventory(inv);
    }

    private void openConfirm(Player admin, UUID target, String name, String category) {
        PanelHolder holder = new PanelHolder("confirm", target, name, false, 0);
        holder.category = category;
        Inventory inv = create(holder, 27, "&4Bestätigen: &f" + categoryName(category));
        inv.setItem(11, item(Material.LIME_CONCRETE, "&a&lBESTÄTIGEN", target == null ? "&cBetrifft ALLE Spieler!" : "&7Spieler: &f" + name));
        inv.setItem(13, item(Material.TNT, "&c" + categoryName(category),
                "&7Ziel: &f" + (target == null ? "ALLE Spieler, die je auf dem Server waren" : name),
                "&7Online-Spieler werden gekickt" + (target == null ? " (auch du)" : ""),
                "&4Nicht rückgängig zu machen!"));
        inv.setItem(15, item(Material.RED_CONCRETE, "&c&lABBRECHEN"));
        admin.openInventory(inv);
    }

    private void openDuration(Player admin, UUID id, String name, boolean ban) {
        PanelHolder holder = new PanelHolder("duration", id, name, ban, 0);
        Inventory inv = create(holder, 27, (ban ? "&cBan" : "&6Mute") + " &8– Dauer für &f" + name);
        for (int i = 0; i < DURATIONS.length; i++) {
            inv.setItem(9 + i, item(DURATIONS[i] < 0 ? Material.RED_CONCRETE : Material.CLOCK, "&f" + DURATION_NAMES[i], "&7Klicken zum Auswählen"));
        }
        inv.setItem(22, item(Material.ANVIL, "&eEigene Dauer", "&7Im Chat eingeben, z. B. &f90m&7, &f6h&7, &f2d&7, &f1w"));
        inv.setItem(18, item(Material.ARROW, "&eZurück"));
        admin.openInventory(inv);
    }

    private void openLog(Player admin, UUID id) {
        String name = plugin.nameOf(id);
        PanelHolder holder = new PanelHolder("log", id, name, false, 0);
        Inventory inv = create(holder, 54, "&8Strafen-Log: &f" + name);
        List<PunishmentManager.Entry> entries = punishments.history(id);
        if (entries.isEmpty()) inv.setItem(22, item(Material.PAPER, "&7Keine Strafen vorhanden"));
        for (int i = 0; i < 45 && i < entries.size(); i++) {
            PunishmentManager.Entry en = entries.get(i);
            boolean punish = en.type().equals("BAN") || en.type().equals("MUTE");
            Material material = switch (en.type()) {
                case "BAN" -> Material.RED_CONCRETE;
                case "MUTE" -> Material.ORANGE_CONCRETE;
                default -> Material.LIME_CONCRETE;
            };
            String label = switch (en.type()) {
                case "BAN" -> "&cBan";
                case "MUTE" -> "&6Mute";
                case "UNBAN" -> "&aEntbannt";
                default -> "&aEntmutet";
            };
            String when = DATE.format(Instant.ofEpochMilli(en.time()));
            if (punish) {
                inv.setItem(i, item(material, label + " &8– &7" + when, "&7Von: &f" + en.by(), "&7Grund: &f" + en.reason(),
                        "&7Dauer: &f" + describeDuration(en.until() == -1L ? -1L : en.until() - en.time())));
            } else {
                inv.setItem(i, item(material, label + " &8– &7" + when, "&7Von: &f" + en.by()));
            }
        }
        inv.setItem(49, item(Material.ARROW, "&eZurück"));
        admin.openInventory(inv);
    }

    private void openEnder(Player admin, UUID id) {
        Player target = Bukkit.getPlayer(id);
        if (target == null) { msg(admin, PRE + "&cDie Enderchest ist nur für Online-Spieler verfügbar."); return; }
        PanelHolder holder = new PanelHolder("ender", id, target.getName(), false, 0);
        Inventory inv = create(holder, 36, "&5Enderchest: &f" + target.getName());
        ItemStack[] contents = target.getEnderChest().getContents();
        for (int i = 0; i < 27 && i < contents.length; i++) {
            if (contents[i] != null) inv.setItem(i, contents[i].clone());
        }
        inv.setItem(35, item(Material.ARROW, "&eZurück"));
        admin.openInventory(inv);
    }

    private void showIp(Player admin, UUID id) {
        String ip = punishments.ip(id);
        Player online = Bukkit.getPlayer(id);
        if (online != null && online.getAddress() != null) ip = online.getAddress().getAddress().getHostAddress();
        String name = plugin.nameOf(id);
        if (ip == null) { msg(admin, PRE + "&7IP von &f" + name + "&7: &cunbekannt"); return; }
        msg(admin, PRE + "&7IP von &f" + name + "&7: &f" + ip);
        List<String> others = punishments.sameIp(id, ip);
        if (!others.isEmpty()) msg(admin, PRE + "&7Weitere Accounts mit dieser IP: &f" + String.join("&7, &f", others));
    }

    // ---------- Klicks ----------

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof PanelHolder holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player admin) || !admin.hasPermission(PERM)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getInventory()) return;
        int slot = e.getRawSlot();
        switch (holder.view) {
            case "list" -> clickList(admin, holder, slot);
            case "player" -> clickPlayer(admin, holder, slot);
            case "duration" -> clickDuration(admin, holder, slot);
            case "log" -> { if (slot == 49) later(() -> openPlayer(admin, holder.target)); }
            case "inv" -> { if (slot == 53) later(() -> openPlayer(admin, holder.target)); }
            case "ender" -> { if (slot == 35) later(() -> openPlayer(admin, holder.target)); }
            case "clear" -> clickClear(admin, holder, slot);
            case "confirm" -> clickConfirm(admin, holder, slot);
            default -> { }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof PanelHolder) e.setCancelled(true);
    }

    private void clickList(Player admin, PanelHolder holder, int slot) {
        if (slot >= 0 && slot < 45 && slot < holder.ids.size()) {
            UUID id = holder.ids.get(slot);
            later(() -> openPlayer(admin, id));
        } else if (slot == 46 || slot == 47) {
            String which = slot == 46 ? "nether" : "end";
            later(() -> {
                boolean disabled = plugin.toggleDimension(which);
                msg(admin, PRE + "&7" + (which.equals("nether") ? "Nether" : "End") + " ist jetzt " + (disabled ? "&cdeaktiviert" : "&aaktiv") + "&7.");
                openList(admin, holder.page);
            });
        } else if (slot == 50) {
            later(() -> {
                boolean on = !plugin.access().isRestricted();
                plugin.access().setRestricted(on);
                msg(admin, PRE + (on ? "&cVorab-Zugang aktiv." : "&aServer freigegeben."));
                openList(admin, holder.page);
            });
        } else if (slot == 48) {
            later(() -> openClear(admin, null, null));
        } else if (slot == 45 && holder.page > 0) {
            later(() -> openList(admin, holder.page - 1));
        } else if (slot == 53) {
            later(() -> openList(admin, holder.page + 1));
        }
    }

    private void clickPlayer(Player admin, PanelHolder holder, int slot) {
        UUID id = holder.target;
        switch (slot) {
            case 10 -> later(() -> { admin.closeInventory(); showIp(admin, id); });
            case 11 -> later(() -> openInv(admin, id));
            case 12 -> later(() -> openEnder(admin, id));
            case 14 -> later(() -> {
                if (punishments.getBan(id) != null) {
                    punishments.unban(id, admin.getName());
                    msg(admin, PRE + "&a" + holder.name + " &7wurde entbannt.");
                    openPlayer(admin, id);
                } else if (!canPunish(admin, id)) {
                    admin.closeInventory();
                } else {
                    openDuration(admin, id, holder.name, true);
                }
            });
            case 16 -> later(() -> {
                if (punishments.getMute(id) != null) {
                    punishments.unmute(id, admin.getName());
                    Player t = Bukkit.getPlayer(id);
                    if (t != null) msg(t, "&aDu wurdest entmutet.");
                    msg(admin, PRE + "&a" + holder.name + " &7wurde entmutet.");
                    openPlayer(admin, id);
                } else if (!canPunish(admin, id)) {
                    admin.closeInventory();
                } else {
                    openDuration(admin, id, holder.name, false);
                }
            });
            case 19 -> later(() -> {
                Player t = Bukkit.getPlayer(id);
                if (t == null) { msg(admin, PRE + "&cSpieler ist nicht online."); return; }
                admin.closeInventory();
                admin.teleport(t.getLocation());
                msg(admin, PRE + "&7Du wurdest zu &f" + t.getName() + " &7teleportiert.");
            });
            case 20 -> later(() -> {
                Player t = Bukkit.getPlayer(id);
                if (t == null) { msg(admin, PRE + "&cSpieler ist nicht online."); return; }
                admin.closeInventory();
                t.teleport(admin.getLocation());
                msg(admin, PRE + "&f" + t.getName() + " &7wurde zu dir geholt.");
            });
            case 21 -> later(() -> {
                Player t = Bukkit.getPlayer(id);
                if (frozen.contains(id)) {
                    frozen.remove(id);
                    saveFrozen();
                    if (t != null) msg(t, "&aDu wurdest aufgetaut.");
                    msg(admin, PRE + "&f" + holder.name + " &7ist nicht mehr eingefroren.");
                } else if (t == null) {
                    msg(admin, PRE + "&cSpieler ist nicht online.");
                } else if (canPunish(admin, id)) {
                    frozen.add(id);
                    saveFrozen();
                    msg(t, "&bDu wurdest von einem Admin eingefroren.");
                    msg(admin, PRE + "&f" + holder.name + " &7wurde eingefroren.");
                }
                openPlayer(admin, id);
            });
            case 22 -> later(() -> {
                if (plugin.access().isAllowed(holder.name)) {
                    plugin.access().remove(holder.name);
                    msg(admin, PRE + "&f" + holder.name + " &7hat keinen Vorab-Zugang mehr.");
                } else {
                    plugin.access().add(holder.name);
                    msg(admin, PRE + "&f" + holder.name + " &7hat jetzt Vorab-Zugang.");
                }
                openPlayer(admin, id);
            });
            case 23 -> later(() -> openLog(admin, id));
            case 25 -> later(() -> openClear(admin, id, holder.name));
            case 27 -> later(() -> openList(admin, 0));
            default -> { }
        }
    }

    private void clickClear(Player admin, PanelHolder holder, int slot) {
        String category = switch (slot) { case 10 -> "stats"; case 12 -> "ach"; case 14 -> "inv"; case 16 -> "all"; default -> null; };
        if (category != null) {
            later(() -> openConfirm(admin, holder.target, holder.name, category));
        } else if (slot == 18) {
            later(() -> { if (holder.target == null) openList(admin, 0); else openPlayer(admin, holder.target); });
        }
    }

    private void clickConfirm(Player admin, PanelHolder holder, int slot) {
        if (slot == 11) {
            later(() -> {
                // Beim Löschen für ALLE Spieler muss zweimal geklickt werden.
                if (holder.target == null && !holder.armed) {
                    holder.armed = true;
                    holder.inv.setItem(11, item(Material.REDSTONE_BLOCK, "&4&lWIRKLICH ALLE LÖSCHEN?", "&cNochmal klicken zum Ausführen"));
                    return;
                }
                executeClear(admin, holder.target, holder.name, holder.category);
            });
        } else if (slot == 15) {
            later(() -> openClear(admin, holder.target, holder.name));
        }
    }

    // ---------- Daten löschen ----------

    /** Setzt für Online-Spieler Inventar, Enderchest, XP und Advancements direkt über die API zurück. */
    private void clearOnline(Player p, boolean ach, boolean inv) {
        if (inv) {
            p.getInventory().clear();
            p.getInventory().setArmorContents(new ItemStack[4]);
            p.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
            p.getEnderChest().clear();
            p.setItemOnCursor(new ItemStack(Material.AIR));
            p.setLevel(0);
            p.setExp(0f);
        }
        if (ach) {
            Iterator<Advancement> it = Bukkit.advancementIterator();
            while (it.hasNext()) {
                AdvancementProgress progress = p.getAdvancementProgress(it.next());
                for (String criteria : new ArrayList<>(progress.getAwardedCriteria())) progress.revokeCriteria(criteria);
            }
        }
    }

    private void executeClear(Player admin, UUID target, String name, String category) {
        boolean stats = category.equals("stats") || category.equals("all");
        boolean ach = category.equals("ach") || category.equals("all");
        boolean inv = category.equals("inv") || category.equals("all");
        String scope = target == null ? "ALLE Spieler" : name;
        UUID adminId = admin.getUniqueId();
        admin.closeInventory();
        msg(admin, PRE + "&7Lösche &f" + categoryName(category) + " &7für &f" + scope + "&7 ...");
        plugin.getLogger().warning("Admin " + admin.getName() + " löscht '" + categoryName(category) + "' für " + scope);

        // 1) Online-Spieler: direkt per API zurücksetzen, dann kicken und kurz sperren.
        Component kick = LEGACY.deserialize("&c&lDeine Daten wurden zurückgesetzt\n\n&7Bitte verbinde dich in einigen Sekunden neu.");
        long lockUntil = System.currentTimeMillis() + 10_000L;
        if (target == null) {
            globalLock = lockUntil;
            for (Player p : new ArrayList<>(Bukkit.getOnlinePlayers())) {
                clearOnline(p, ach, inv);
                p.kick(kick);
            }
        } else {
            loginLocks.put(target, lockUntil);
            Player t = Bukkit.getPlayer(target);
            if (t != null) {
                clearOnline(t, ach, inv);
                t.kick(kick);
            }
        }

        // 2) Dateien von Offline-Spielern (und Vanilla-Statistiken) nach kurzer Wartezeit löschen.
        Bukkit.getScheduler().runTaskLater(plugin, () -> Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            int[] deleted = deleteFiles(target, stats, ach, inv);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (stats) plugin.clearOurStats(target);
                String result = "Stats-Dateien " + deleted[0] + ", Achievements " + deleted[1] + ", Spielerdaten " + deleted[2];
                plugin.getLogger().warning("Datenlöschung abgeschlossen für " + scope + ": " + result
                        + " (durchsucht: " + Bukkit.getWorldContainer().getAbsolutePath() + ")");
                Player a = Bukkit.getPlayer(adminId);
                if (a != null) {
                    msg(a, PRE + "&aFertig. &7Gelöscht: &f" + result);
                    if (deleted[0] + deleted[1] + deleted[2] == 0 && (ach || inv)) {
                        msg(a, PRE + "&eHinweis: &7Keine Dateien gefunden. Online-Spieler wurden direkt zurückgesetzt, Offline-Spieler ggf. nicht.");
                    }
                }
            });
        }), 40L);
    }

    /**
     * Löscht Spielerdateien (Vanilla-Stats, Advancements, Spielerdaten) für einen Spieler oder alle.
     * Durchsucht den gesamten Server-Ordner (bis Tiefe 8), da die Ordnerstruktur je nach Version
     * unterschiedlich ist (z. B. world/playerdata oder world/players/data). Läuft asynchron.
     * Rückgabe: {Stats, Achievements, Spielerdaten}.
     */
    private int[] deleteFiles(UUID only, boolean stats, boolean ach, boolean inv) {
        int[] count = {0, 0, 0};
        Path root = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        try {
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), 8, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    Path name = dir.getFileName();
                    return name != null && !dir.equals(root) && SKIP_DIRS.contains(name.toString())
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String fileName = file.getFileName().toString();
                    Matcher m = UUID_FILE.matcher(fileName);
                    if (!m.matches()) return FileVisitResult.CONTINUE;
                    UUID id;
                    try { id = UUID.fromString(m.group(1)); } catch (IllegalArgumentException e) { return FileVisitResult.CONTINUE; }
                    if (only != null && !only.equals(id)) return FileVisitResult.CONTINUE;
                    String rel = "/" + root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/').toLowerCase(Locale.ROOT);
                    int type = -1;
                    if (rel.contains("/advancements/")) type = 1;
                    else if (rel.contains("/stats/")) type = 0;
                    else if ((fileName.endsWith(".dat") || fileName.endsWith(".dat_old"))
                            && (rel.contains("/playerdata/") || rel.contains("/players/"))) type = 2;
                    if (type < 0) return FileVisitResult.CONTINUE;
                    if ((type == 0 && stats) || (type == 1 && ach) || (type == 2 && inv)) {
                        try { Files.delete(file); count[type]++; } catch (IOException ignored) { }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) { return FileVisitResult.CONTINUE; }
            });
        } catch (IOException e) {
            plugin.getLogger().warning("Fehler beim Löschen der Spielerdateien: " + e.getMessage());
        }
        return count;
    }

    private boolean canPunish(Player admin, UUID target) {
        if (target.equals(admin.getUniqueId())) { msg(admin, PRE + "&cDu kannst dich nicht selbst bestrafen."); return false; }
        Player online = Bukkit.getPlayer(target);
        if (online != null && online.hasPermission(PERM)) { msg(admin, PRE + "&cAdmins können nicht bestraft werden."); return false; }
        return true;
    }

    private void clickDuration(Player admin, PanelHolder holder, int slot) {
        if (slot >= 9 && slot < 9 + DURATIONS.length) {
            long duration = DURATIONS[slot - 9];
            later(() -> startPrompt(admin, holder, duration));
        } else if (slot == 22) {
            later(() -> startPrompt(admin, holder, -2L));
        } else if (slot == 18) {
            later(() -> openPlayer(admin, holder.target));
        }
    }

    // ---------- Chat-Eingabe für Dauer und Grund ----------

    private void startPrompt(Player admin, PanelHolder holder, long duration) {
        prompts.put(admin.getUniqueId(), new Prompt(holder.target, holder.name, holder.ban, duration));
        admin.closeInventory();
        if (duration == -2L) {
            msg(admin, PRE + "&7Gib die &fDauer &7im Chat ein, z. B. &f30m&7, &f6h&7, &f2d&7, &f1w &7oder &fperm&7. &8(&fabbrechen&8 zum Abbrechen)");
        } else {
            askReason(admin, holder.ban);
        }
    }

    private void askReason(Player admin, boolean ban) {
        msg(admin, PRE + "&7Gib jetzt den &fGrund &7für den " + (ban ? "Ban" : "Mute") + " im Chat ein. &8(&f-&8 = kein Grund, &fabbrechen&8 zum Abbrechen)");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPromptChat(AsyncPlayerChatEvent e) {
        Player admin = e.getPlayer();
        if (!prompts.containsKey(admin.getUniqueId())) return;
        e.setCancelled(true);
        String message = e.getMessage().trim();
        later(() -> handlePrompt(admin, message));
    }

    private void handlePrompt(Player admin, String message) {
        UUID adminId = admin.getUniqueId();
        Prompt prompt = prompts.get(adminId);
        if (prompt == null) return;
        if (message.equalsIgnoreCase("abbrechen")) {
            prompts.remove(adminId);
            msg(admin, PRE + "&7Abgebrochen.");
            return;
        }
        if (prompt.duration == -2L) {
            long parsed = PunishmentManager.parseDuration(message);
            if (parsed == -2L) {
                msg(admin, PRE + "&cUngültige Dauer. &7Beispiele: &f30m&7, &f6h&7, &f2d&7, &f1w&7, &fperm");
                return;
            }
            prompt.duration = parsed;
            askReason(admin, prompt.ban);
            return;
        }
        prompts.remove(adminId);
        String reason = message.equals("-") || message.isEmpty() ? "Kein Grund angegeben" : message;
        if (!canPunish(admin, prompt.target)) return;
        Player target = Bukkit.getPlayer(prompt.target);
        if (prompt.ban) {
            punishments.ban(prompt.target, prompt.name, prompt.duration, reason, admin.getName());
            PunishmentManager.Active ban = punishments.getBan(prompt.target);
            if (target != null && ban != null) target.kick(banScreen(ban));
            msg(admin, PRE + "&c" + prompt.name + " &7gebannt für &f" + describeDuration(prompt.duration) + "&7. Grund: &f" + reason);
        } else {
            punishments.mute(prompt.target, prompt.name, prompt.duration, reason, admin.getName());
            PunishmentManager.Active mute = punishments.getMute(prompt.target);
            if (target != null && mute != null) msg(target, muteText(mute));
            msg(admin, PRE + "&6" + prompt.name + " &7stumm geschaltet für &f" + describeDuration(prompt.duration) + "&7. Grund: &f" + reason);
        }
    }

    // ---------- Durchsetzung ----------

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        long now = System.currentTimeMillis();
        if (now < globalLock || now < loginLocks.getOrDefault(e.getUniqueId(), 0L)) {
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    LEGACY.deserialize("&eDaten werden gerade zurückgesetzt.\n&7Bitte versuche es in einigen Sekunden erneut."));
            return;
        }
        PunishmentManager.Active ban = punishments.getBan(e.getUniqueId());
        if (ban != null) e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, banScreen(ban));
    }

    @EventHandler(ignoreCancelled = true)
    public void onFrozenMove(PlayerMoveEvent e) {
        if (!frozen.contains(e.getPlayer().getUniqueId()) || e.getTo() == null) return;
        Location from = e.getFrom(), to = e.getTo();
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) e.setCancelled(true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        String ip = p.getAddress() == null ? null : p.getAddress().getAddress().getHostAddress();
        punishments.recordJoin(p.getUniqueId(), p.getName(), ip);
        if (frozen.contains(p.getUniqueId())) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> msg(p, "&bDu bist von einem Admin eingefroren und kannst dich nicht bewegen."), 20L);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        prompts.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMuteChat(AsyncPlayerChatEvent e) {
        PunishmentManager.Active mute = punishments.getMute(e.getPlayer().getUniqueId());
        if (mute == null) return;
        e.setCancelled(true);
        msg(e.getPlayer(), muteText(mute));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMuteCommand(PlayerCommandPreprocessEvent e) {
        String raw = e.getMessage();
        if (raw.length() < 2) return;
        String cmd = raw.substring(1).split(" ")[0].toLowerCase(Locale.ROOT);
        if (cmd.contains(":")) cmd = cmd.substring(cmd.indexOf(':') + 1);
        if (!MUTED_COMMANDS.contains(cmd)) return;
        PunishmentManager.Active mute = punishments.getMute(e.getPlayer().getUniqueId());
        if (mute == null) return;
        e.setCancelled(true);
        msg(e.getPlayer(), muteText(mute));
    }
}
