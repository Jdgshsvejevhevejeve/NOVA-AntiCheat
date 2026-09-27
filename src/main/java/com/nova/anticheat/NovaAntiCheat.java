package com.nova.anticheat;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class NovaAntiCheat extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private final Map<UUID, PlayerData> data = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Double>> vl = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Bukkit.getPluginManager().registerEvents(this, this);

        PluginCommand cmd = getCommand("novac");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        // Periodic violation decay.
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Map<String, Double> checks : vl.values()) {
                checks.replaceAll((k, v) -> Math.max(0, v - 1));
            }
        }, 20L * getConfig().getLong("settings.violation-decay-seconds", 30),
                20L * getConfig().getLong("settings.violation-decay-seconds", 30));

        getLogger().info("NOVA AntiCheat enabled.");
    }

    @Override
    public void onDisable() {
        data.clear();
        vl.clear();
    }

    private PlayerData d(Player p) {
        return data.computeIfAbsent(p.getUniqueId(), k -> new PlayerData());
    }

    private boolean bypass(Player p) {
        return p.hasPermission(getConfig().getString("settings.exempt-permission", "novac.bypass"));
    }

    private void flag(Player p, String check, double amount, String detail) {
        if (bypass(p)) return;

        Map<String, Double> checks = vl.computeIfAbsent(p.getUniqueId(), k -> new ConcurrentHashMap<>());
        double newVl = checks.merge(check, amount, Double::sum);

        if (getConfig().getBoolean("settings.alerts", true)) {
            String msg = "§8[§bNOVA§8] §c" + p.getName() + " §7failed §e" + check +
                    " §8(VL " + String.format(Locale.US, "%.1f", newVl) + "§8) §7" + detail;
            Bukkit.getOnlinePlayers().stream()
                    .filter(x -> x.hasPermission("novac.admin"))
                    .forEach(x -> x.sendMessage(msg));
        }

        double warn = getConfig().getDouble("actions.warn.threshold", 5);
        double setback = getConfig().getDouble("actions.setback.threshold", 8);
        double kick = getConfig().getDouble("actions.kick.threshold", 20);

        if (getConfig().getBoolean("actions.warn.enabled", true) && newVl >= warn && newVl - amount < warn)
            p.sendMessage("§8[§bNOVA§8] §cSuspicious movement/combat detected. §7Please play normally.");

        if (getConfig().getBoolean("actions.setback.enabled", true) && newVl >= setback && newVl - amount < setback) {
            Location safe = d(p).lastGood;
            if (safe != null) Bukkit.getScheduler().runTask(this, () -> p.teleport(safe));
        }

        if (getConfig().getBoolean("actions.kick.enabled", true) && newVl >= kick) {
            Bukkit.getScheduler().runTask(this, () -> p.kickPlayer("§cNOVA AntiCheat\n§7Unfair gameplay detected."));
        }
    }

    @EventHandler
    public void join(PlayerJoinEvent e) {
        PlayerData pd = d(e.getPlayer());
        pd.lastGood = e.getPlayer().getLocation().clone();
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        data.remove(e.getPlayer().getUniqueId());
        vl.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void move(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        PlayerData pd = d(p);
        Location from = e.getFrom(), to = e.getTo();
        if (to == null || from.getWorld() != to.getWorld()) {
            pd.lastGood = to == null ? from.clone() : to.clone();
            return;
        }

        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double horizontal = Math.hypot(dx, dz);

        boolean grounded = p.isOnGround() || p.getLocation().subtract(0, 0.15, 0).getBlock().getType().isSolid();

        if (grounded) {
            pd.airTicks = 0;
            pd.lastGood = from.clone();
        } else {
            pd.airTicks++;
        }

        if (getConfig().getBoolean("checks.speed.enabled", true) &&
                !p.isFlying() && !p.isGliding() && !p.isInsideVehicle()) {
            double max = getConfig().getDouble("checks.speed.max-horizontal-per-tick", 0.80);
            if (horizontal > max && ++pd.speedBuffer >= getConfig().getInt("checks.speed.buffer", 5)) {
                flag(p, "Speed", 1, String.format(Locale.US, "move=%.2f", horizontal));
                pd.speedBuffer = 0;
            } else if (horizontal <= max) pd.speedBuffer = Math.max(0, pd.speedBuffer - 1);
        }

        if (getConfig().getBoolean("checks.flight.enabled", true) &&
                !grounded && !p.isFlying() && !p.isGliding() && !p.isInsideVehicle()) {
            int maxAir = getConfig().getInt("checks.flight.max-air-ticks-without-support", 12);
            if (pd.airTicks > maxAir && ++pd.flightBuffer >= getConfig().getInt("checks.flight.buffer", 3)) {
                flag(p, "Flight", 1, "airTicks=" + pd.airTicks);
                pd.flightBuffer = 0;
            }
        } else {
            pd.flightBuffer = 0;
        }

        if (getConfig().getBoolean("checks.rotation.enabled", true)) {
            float yawDiff = angleDiff(to.getYaw(), from.getYaw());
            float pitchDiff = Math.abs(to.getPitch() - from.getPitch());
            if (yawDiff > getConfig().getDouble("checks.rotation.max-yaw-change", 160) ||
                    pitchDiff > getConfig().getDouble("checks.rotation.max-pitch-change", 90)) {
                flag(p, "Rotation", 0.75, String.format(Locale.US, "yaw=%.1f pitch=%.1f", yawDiff, pitchDiff));
            }
        }

        if (getConfig().getBoolean("checks.nofall.enabled", true) &&
                pd.lastY > to.getY() && grounded) {
            double fall = pd.lastY - to.getY();
            if (fall >= getConfig().getDouble("checks.nofall.min-fall-distance", 4.0) &&
                    p.getFallDistance() <= 0.01f) {
                flag(p, "NoFall", 1.5, String.format(Locale.US, "fall=%.1f", fall));
            }
        }

        pd.lastY = to.getY();
        pd.lastMove = System.nanoTime();
    }

    @EventHandler(ignoreCancelled = true)
    public void attack(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (!getConfig().getBoolean("checks.reach.enabled", true)) return;

        Entity target = e.getEntity();
        double distance = p.getEyeLocation().distance(target.getBoundingBox().getCenter().toLocation(target.getWorld()));
        double max = getConfig().getDouble("checks.reach.max-distance", 3.15);
        if (distance > max + getConfig().getDouble("checks.reach.buffer", 3) * 0.02) {
            flag(p, "Reach", 2, String.format(Locale.US, "distance=%.2f", distance));
            e.setCancelled(true);
        }

        PlayerData pd = d(p);
        pd.lastAttackNanos = System.nanoTime();
        pd.attackSamples++;
        long now = System.nanoTime();
        if (pd.attackWindowStart == 0) pd.attackWindowStart = now;
        if ((now - pd.attackWindowStart) >= 1_000_000_000L) {
            double cps = pd.attackSamples / ((now - pd.attackWindowStart) / 1_000_000_000.0);
            double maxCps = getConfig().getDouble("checks.autoclicker.max-cps", 18);
            if (getConfig().getBoolean("checks.autoclicker.enabled", true) &&
                    pd.attackSamples >= getConfig().getInt("checks.autoclicker.min-samples", 20) &&
                    cps > maxCps) {
                flag(p, "AutoClicker", 1, String.format(Locale.US, "cps=%.1f", cps));
            }
            pd.attackSamples = 0;
            pd.attackWindowStart = now;
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void damage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (e.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK ||
                e.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) {
            d(p).lastDamagedNanos = System.nanoTime();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent e) {
        if (!getConfig().getBoolean("checks.fastbreak.enabled", true)) return;
        Player p = e.getPlayer();
        PlayerData pd = d(p);
        long now = System.nanoTime();

        if (pd.lastBreakNanos != 0) {
            long deltaMs = (now - pd.lastBreakNanos) / 1_000_000L;
            if (deltaMs < 80) flag(p, "FastBreak", 1.5, "interval=" + deltaMs + "ms");
        }
        pd.lastBreakNanos = now;
    }

    @EventHandler(ignoreCancelled = true)
    public void place(BlockPlaceEvent e) {
        if (!getConfig().getBoolean("checks.fastplace.enabled", true)) return;
        Player p = e.getPlayer();
        PlayerData pd = d(p);
        long now = System.nanoTime();

        if (pd.placeWindowStart == 0 || now - pd.placeWindowStart >= 1_000_000_000L) {
            if (pd.placeCount > getConfig().getInt("checks.fastplace.max-placements-per-second", 18))
                flag(p, "FastPlace", 1, "placements=" + pd.placeCount);
            pd.placeCount = 0;
            pd.placeWindowStart = now;
        }
        pd.placeCount++;
    }

    private static float angleDiff(float a, float b) {
        float d = Math.abs(a - b) % 360f;
        return d > 180f ? 360f - d : d;
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] args) {
        if (!s.hasPermission("novac.admin")) {
            s.sendMessage("§cNo permission.");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("alerts")) {
            s.sendMessage("§8[§bNOVA§8] §7Alerts are " +
                    (getConfig().getBoolean("settings.alerts", true) ? "§aON" : "§cOFF"));
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            s.sendMessage("§8[§bNOVA§8] §aConfiguration reloaded.");
            return true;
        }
        if (args[0].equalsIgnoreCase("info") && args.length >= 2) {
            Player p = Bukkit.getPlayerExact(args[1]);
            if (p == null) {
                s.sendMessage("§cPlayer not found.");
                return true;
            }
            Map<String, Double> checks = vl.getOrDefault(p.getUniqueId(), Map.of());
            s.sendMessage("§8§m--------------------");
            s.sendMessage("§bNOVA §7— §f" + p.getName());
            if (checks.isEmpty()) s.sendMessage("§7No violations.");
            else checks.forEach((k, v) -> s.sendMessage("§e" + k + "§7: §f" + String.format(Locale.US, "%.1f", v)));
            s.sendMessage("§8§m--------------------");
            return true;
        }
        s.sendMessage("§7Usage: §f/novac <alerts|info <player>|reload>");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] args) {
        if (args.length == 1) return List.of("alerts", "info", "reload");
        if (args.length == 2 && args[0].equalsIgnoreCase("info"))
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        return List.of();
    }

    private static final class PlayerData {
        Location lastGood;
        Location lastMove;
        double lastY;
        int airTicks;
        int speedBuffer;
        int flightBuffer;
        long lastAttackNanos;
        long lastDamagedNanos;
        long attackWindowStart;
        int attackSamples;
        long lastBreakNanos;
        long placeWindowStart;
        int placeCount;
    }
}
