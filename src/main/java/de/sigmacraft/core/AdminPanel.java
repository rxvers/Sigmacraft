package de.sigmacraft.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
    private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();

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
        inv.setItem(49, item(Material.PAPER, "&7Offline-Spieler", "&8Nutze &f/admin <Name>"));
        if (current < pages - 1) inv.setItem(53, item(Material.ARROW, "&eNächste Seite"));
        admin.openInventory(inv);
    }

    private void openPlayer(Player admin, UUID id) {
        String name = plugin.nameOf(id);
        PanelHolder holder = new PanelHolder("player", id, name, false, 0);
        Inventory inv = create(holder, 27, "&8Spieler: &f" + name);
        PunishmentManager.Active ban = punishments.getBan(id);
        PunishmentManager.Active mute = punishments.getMute(id);
        Player online = Bukkit.getPlayer(id);

        inv.setItem(4, item(Material.PLAYER_HEAD, "&f" + name, online != null ? "&aOnline" : "&7Offline"));
        inv.setItem(10, item(Material.NAME_TAG, "&bIP-Adresse", "&7Klicken, um die IP im Chat zu sehen"));
        inv.setItem(12, online != null
                ? item(Material.ENDER_CHEST, "&5Enderchest ansehen", "&7Nur Ansicht, nichts veränderbar")
                : item(Material.ENDER_CHEST, "&5Enderchest ansehen", "&cNur für Online-Spieler"));
        inv.setItem(14, ban == null
                ? item(Material.BARRIER, "&cBannen", "&7Dauer und Grund wählen")
                : item(Material.EMERALD, "&aEntbannen", "&7Grund: &f" + ban.reason(), "&7Verbleibend: &f" + PunishmentManager.remaining(ban)));
        inv.setItem(16, mute == null
                ? item(Material.NOTE_BLOCK, "&6Stumm schalten", "&7Dauer und Grund wählen")
                : item(Material.LIME_DYE, "&aEntmuten", "&7Grund: &f" + mute.reason(), "&7Verbleibend: &f" + PunishmentManager.remaining(mute)));
        inv.setItem(22, item(Material.BOOK, "&eStrafen-Log", "&7Erhaltene Strafen: &f" + punishments.history(id).size()));
        inv.setItem(18, item(Material.ARROW, "&eZurück"));
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
        Inventory inv = create(holder, 27, "&5Enderchest: &f" + target.getName());
        ItemStack[] contents = target.getEnderChest().getContents();
        for (int i = 0; i < 27 && i < contents.length; i++) {
            if (contents[i] != null) inv.setItem(i, contents[i].clone());
        }
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
            case 22 -> later(() -> openLog(admin, id));
            case 18 -> later(() -> openList(admin, 0));
            default -> { }
        }
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
        PunishmentManager.Active ban = punishments.getBan(e.getUniqueId());
        if (ban != null) e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, banScreen(ban));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        String ip = p.getAddress() == null ? null : p.getAddress().getAddress().getHostAddress();
        punishments.recordJoin(p.getUniqueId(), p.getName(), ip);
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
