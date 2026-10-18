package de.sigmacraft.core;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleGlideEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class ElytraBoost implements Listener {
    private final SigmaCraftCore plugin;
    private final Map<UUID, ItemStack> chestplates = new HashMap<>();
    private final Map<UUID, Long> eligible = new HashMap<>();
    private final Map<UUID, Long> active = new HashMap<>();
    ElytraBoost(SigmaCraftCore plugin) { this.plugin = plugin; }

    @EventHandler public void onMove(PlayerMoveEvent e) {
        if (e.getTo() == null || e.getFrom().getY() <= e.getTo().getY()) return;
        Player p = e.getPlayer();
        if (!plugin.isNearSpawn(p.getLocation(), plugin.getConfig().getDouble("elytra.eligible-radius", 80))) return;
        if (p.getLocation().getY() <= plugin.getConfig().getDouble("elytra.min-y", 55) || p.getFallDistance() > 1.5f) eligible.put(p.getUniqueId(), System.currentTimeMillis() + 8000);
    }
    @EventHandler public void onSwap(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer(); long now = System.currentTimeMillis();
        if (!plugin.getConfig().getBoolean("elytra.enabled", true) || eligible.getOrDefault(p.getUniqueId(), 0L) < now || active.containsKey(p.getUniqueId())) return;
        if (p.isOnGround() || p.isInsideVehicle() || p.isGliding()) return;
        ItemStack chest = p.getInventory().getChestplate();
        if (chest != null && chest.getType() != Material.AIR && chest.getType() != Material.ELYTRA) {
            p.sendMessage("§cDu brauchst einen freien Brustplatten-Slot für den Spawn-Boost."); return;
        }
        e.setCancelled(true);
        chestplates.put(p.getUniqueId(), chest == null ? null : chest.clone());
        p.getInventory().setChestplate(new ItemStack(Material.ELYTRA));
        p.setGliding(true);
        Vector direction = p.getLocation().getDirection().normalize();
        double boost = plugin.getConfig().getDouble("elytra.boost-strength", 1.8);
        p.setVelocity(direction.multiply(boost).setY(Math.max(0.65, direction.getY() * boost + 0.55)));
        long expiresAt = now + plugin.getConfig().getLong("elytra.duration-seconds", 12) * 1000L;
        active.put(p.getUniqueId(), expiresAt);
        eligible.remove(p.getUniqueId());
        p.sendMessage("§bSpawn-Boost aktiviert!");
        // Safety timeout: restore the original chestplate even if the player never lands.
        long delayTicks = Math.max(1L, plugin.getConfig().getLong("elytra.duration-seconds", 12) * 20L);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (active.getOrDefault(p.getUniqueId(), 0L) == expiresAt) restore(p);
        }, delayTicks);
    }
    @EventHandler public void onMoveLand(PlayerMoveEvent e) {
        Player p = e.getPlayer(); if (active.containsKey(p.getUniqueId()) && p.isOnGround()) restore(p);
    }
    @EventHandler public void onGlide(PlayerToggleGlideEvent e) {
        if (e.isGliding()) return;
        if (active.containsKey(e.getPlayer().getUniqueId())) restore(e.getPlayer());
    }
    @EventHandler public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && active.containsKey(p.getUniqueId()) && p.getHealth() - e.getFinalDamage() <= 0) restore(p);
    }
    @EventHandler public void onDeath(PlayerDeathEvent e) {
        // Der Boost-Elytra darf nie als Drop landen; Original-Brustplatte zurückgeben.
        Player p = e.getEntity();
        if (!active.containsKey(p.getUniqueId())) return;
        e.getDrops().removeIf(i -> i != null && i.getType() == Material.ELYTRA);
        ItemStack original = chestplates.get(p.getUniqueId());
        if (original != null && !e.getKeepInventory()) e.getDrops().add(original);
        chestplates.remove(p.getUniqueId());
        active.remove(p.getUniqueId());
    }
    @EventHandler public void onQuit(PlayerQuitEvent e) { restore(e.getPlayer()); eligible.remove(e.getPlayer().getUniqueId()); }
    public void restoreAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) restore(player);
        eligible.clear();
    }

    private void restore(Player p) {
        if (!active.containsKey(p.getUniqueId())) return;
        active.remove(p.getUniqueId());
        ItemStack original = chestplates.remove(p.getUniqueId());
        if (p.getInventory().getChestplate() != null && p.getInventory().getChestplate().getType() == Material.ELYTRA) {
            p.getInventory().setChestplate(original);
        }
        if (p.isGliding()) p.setGliding(false);
    }
}
