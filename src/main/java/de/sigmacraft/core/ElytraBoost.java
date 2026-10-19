package de.sigmacraft.core;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Spawn-Boost: Wer am Spawn fällt, kann mit F (Hand tauschen) eine temporäre Elytra aktivieren.
 * Die Elytra wird erst entfernt, wenn der Spieler landet (Boden oder Wasser).
 * Eine neue Aktivierung ist nur wieder am Spawn möglich.
 */
public final class ElytraBoost implements Listener {
    private final SigmaCraftCore plugin;
    private final Map<UUID, ItemStack> chestplates = new HashMap<>();
    private final Map<UUID, Long> eligible = new HashMap<>();
    private final Map<UUID, Long> active = new HashMap<>();
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private static final Component BAR_TEXT = Component.text("✦ Drücke ", NamedTextColor.LIGHT_PURPLE)
            .append(Component.text("F", NamedTextColor.WHITE, TextDecoration.BOLD))
            .append(Component.text(", um loszufliegen ✦", NamedTextColor.LIGHT_PURPLE));

    ElytraBoost(SigmaCraftCore plugin) {
        this.plugin = plugin;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::checkLandings, 10L, 5L);
    }

    private double radius() { return plugin.getConfig().getDouble("elytra.eligible-radius", 80.0); }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (e.getTo() == null || e.getFrom().getY() <= e.getTo().getY()) return;
        Player p = e.getPlayer();
        if (active.containsKey(p.getUniqueId())) return;
        if (!plugin.isNearSpawn(p.getLocation(), radius())) return;
        if (p.getLocation().getY() <= plugin.getConfig().getDouble("elytra.min-y", 55.0) || p.getFallDistance() > 1.5f) {
            eligible.put(p.getUniqueId(), System.currentTimeMillis() + 8000L);
        }
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        if (!plugin.getConfig().getBoolean("elytra.enabled", true)) return;
        if (eligible.getOrDefault(id, 0L) < now || active.containsKey(id)) return;
        if (p.isOnGround() || p.isInsideVehicle() || p.isGliding()) return;
        // Nur am Spawn nutzbar.
        if (!plugin.isNearSpawn(p.getLocation(), radius())) return;

        e.setCancelled(true);
        eligible.remove(id);

        ItemStack chest = p.getInventory().getChestplate();
        boolean ownElytra = chest != null && chest.getType() == Material.ELYTRA;
        if (!ownElytra) {
            boolean empty = chest == null || chest.getType() == Material.AIR;
            chestplates.put(id, empty ? null : chest.clone());
            p.getInventory().setChestplate(new ItemStack(Material.ELYTRA));
            active.put(id, now);
        }
        p.setGliding(true);

        Vector dir = p.getLocation().getDirection().normalize();
        double boost = plugin.getConfig().getDouble("elytra.boost-strength", 1.8);
        double y = Math.max(0.65, dir.getY() * boost + 0.55);
        p.setVelocity(dir.multiply(boost).setY(y));
        p.sendMessage("§8[§bBoost§8] §aSpawn-Boost aktiviert! §7Die Elytra verschwindet beim Landen.");
    }

    /** Prüft regelmäßig, ob Spieler mit Boost-Elytra gelandet sind. */
    private void checkLandings() {
        long now = System.currentTimeMillis();
        updateBars(now);
        for (UUID id : new ArrayList<>(active.keySet())) {
            Player p = plugin.getServer().getPlayer(id);
            if (p == null) { active.remove(id); chestplates.remove(id); continue; }
            if (now - active.get(id) < 750L) continue;
            if (p.isOnGround() || p.isInWater() || p.isSwimming() || p.isInsideVehicle()) restore(p);
        }
    }

    /** Zeigt eine Boss-Bar (wie beim Enderdrachen), solange der Boost per F nutzbar ist. */
    private void updateBars(long now) {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            long until = eligible.getOrDefault(id, 0L);
            boolean show = until > now && !active.containsKey(id) && !p.isOnGround() && !p.isGliding();
            BossBar bar = bars.get(id);
            if (show) {
                float progress = Math.max(0f, Math.min(1f, (until - now) / 8000f));
                if (bar == null) {
                    bar = BossBar.bossBar(BAR_TEXT, progress, BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS);
                    bars.put(id, bar);
                    p.showBossBar(bar);
                } else {
                    bar.progress(progress);
                }
            } else if (bar != null) {
                p.hideBossBar(bar);
                bars.remove(id);
            }
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && active.containsKey(p.getUniqueId())
                && e.getSlotType() == InventoryType.SlotType.ARMOR) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && active.containsKey(p.getUniqueId())
                && p.getHealth() - e.getFinalDamage() <= 0) {
            restore(p);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (!active.containsKey(p.getUniqueId())) return;
        ItemStack original = chestplates.get(p.getUniqueId());
        e.getDrops().removeIf(i -> i != null && i.getType() == Material.ELYTRA);
        if (e.getKeepInventory()) {
            p.getInventory().setChestplate(original);
        } else if (original != null) {
            e.getDrops().add(original);
        }
        chestplates.remove(p.getUniqueId());
        active.remove(p.getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        restore(e.getPlayer());
        eligible.remove(e.getPlayer().getUniqueId());
        bars.remove(e.getPlayer().getUniqueId());
    }

    public void restoreAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) restore(player);
        for (Map.Entry<UUID, BossBar> entry : bars.entrySet()) {
            Player holder = plugin.getServer().getPlayer(entry.getKey());
            if (holder != null) holder.hideBossBar(entry.getValue());
        }
        bars.clear();
        eligible.clear();
    }

    private void restore(Player p) {
        UUID id = p.getUniqueId();
        if (active.remove(id) == null) return;
        ItemStack original = chestplates.remove(id);
        ItemStack current = p.getInventory().getChestplate();
        if (current != null && current.getType() == Material.ELYTRA) {
            p.getInventory().setChestplate(original);
        }
        if (p.isGliding()) p.setGliding(false);
    }
}
