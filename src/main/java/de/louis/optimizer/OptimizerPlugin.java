package de.louis.optimizer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Animals;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Boss;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Steerable;
import org.bukkit.entity.Tameable;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class OptimizerPlugin extends JavaPlugin implements TabExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final long MB = 1024L * 1024L;

    private final List<BukkitTask> tasks = new ArrayList<>();
    private int secondsLeft;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        var cmd = getCommand("optimizer");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        startTasks();
        getLogger().info("ServerOptimizer aktiv.");
    }

    @Override
    public void onDisable() {
        stopTasks();
    }

    // ------------------------------------------------------------------ Tasks

    private void startTasks() {
        FileConfiguration c = getConfig();

        if (c.getBoolean("item-cleaner.enabled", true)) {
            secondsLeft = Math.max(10, c.getInt("item-cleaner.interval-seconds", 300));
            tasks.add(Bukkit.getScheduler().runTaskTimer(this, this::itemCleanerTick, 20L, 20L));
        }

        if (c.getBoolean("mob-limiter.enabled", true)) {
            long period = Math.max(5, c.getInt("mob-limiter.interval-seconds", 30)) * 20L;
            tasks.add(Bukkit.getScheduler().runTaskTimer(this, () -> limitMobs(false), period, period));
        }

        if (c.getBoolean("dynamic-simulation-distance.enabled", false)) {
            long period = Math.max(5, c.getInt("dynamic-simulation-distance.check-interval-seconds", 15)) * 20L;
            tasks.add(Bukkit.getScheduler().runTaskTimer(this, this::adjustSimulationDistance, period, period));
        }
    }

    private void stopTasks() {
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
    }

    // ------------------------------------------------------------ Item-Cleaner

    private void itemCleanerTick() {
        secondsLeft--;
        if (secondsLeft > 0) {
            if (getConfig().getIntegerList("item-cleaner.warn-at-seconds").contains(secondsLeft)) {
                Bukkit.broadcast(msg("messages.item-warning", "{seconds}", String.valueOf(secondsLeft)));
            }
            return;
        }
        int removed = clearItems();
        secondsLeft = Math.max(10, getConfig().getInt("item-cleaner.interval-seconds", 300));
        Bukkit.broadcast(msg("messages.item-cleared", "{count}", String.valueOf(removed)));
    }

    private int clearItems() {
        Set<Material> keep = EnumSet.noneOf(Material.class);
        for (String name : getConfig().getStringList("item-cleaner.keep-items")) {
            Material m = Material.matchMaterial(name);
            if (m != null) keep.add(m);
        }
        int minAgeTicks = Math.max(0, getConfig().getInt("item-cleaner.min-age-seconds", 30)) * 20;

        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Item item : world.getEntitiesByClass(Item.class)) {
                if (item.getTicksLived() < minAgeTicks) continue;
                if (keep.contains(item.getItemStack().getType())) continue;
                item.remove();
                removed++;
            }
        }
        return removed;
    }

    // ------------------------------------------------------------- Mob-Limiter

    private int limitMobs(boolean verbose) {
        int maxAnimals = Math.max(1, getConfig().getInt("mob-limiter.max-animals-per-type-per-chunk", 24));
        int maxMonsters = Math.max(1, getConfig().getInt("mob-limiter.max-monsters-per-type-per-chunk", 20));

        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                if (!chunk.isEntitiesLoaded()) continue;

                Map<EntityType, List<LivingEntity>> byType = new HashMap<>();
                for (Entity e : chunk.getEntities()) {
                    if (e instanceof LivingEntity le && isLimitable(le)) {
                        byType.computeIfAbsent(le.getType(), k -> new ArrayList<>()).add(le);
                    }
                }

                for (List<LivingEntity> list : byType.values()) {
                    int max = (list.get(0) instanceof Enemy) ? maxMonsters : maxAnimals;
                    int excess = list.size() - max;
                    if (excess <= 0) continue;

                    // Älteste zuerst entfernen
                    list.sort((a, b) -> Integer.compare(b.getTicksLived(), a.getTicksLived()));
                    for (int i = 0; i < excess; i++) {
                        list.get(i).remove();
                        removed++;
                    }
                }
            }
        }

        if (verbose || removed > 0) {
            getLogger().info(removed + " überschüssige Mobs entfernt.");
        }
        return removed;
    }

    private boolean isLimitable(LivingEntity le) {
        if (!(le instanceof Animals) && !(le instanceof Enemy)) return false;
        if (le instanceof Player || le instanceof ArmorStand || le instanceof Boss) return false;
        if (le.customName() != null) return false;
        if (le instanceof Tameable t && t.isTamed()) return false;
        if (le instanceof Mob m && m.isLeashed()) return false;
        if (le instanceof Steerable s && s.hasSaddle()) return false;
        if (le.isInsideVehicle() || !le.getPassengers().isEmpty()) return false;
        return true;
    }

    // ------------------------------------------- Dynamische Simulation Distance

    private void adjustSimulationDistance() {
        FileConfiguration c = getConfig();
        int min = Math.max(2, c.getInt("dynamic-simulation-distance.min-distance", 4));
        int max = Math.max(min, c.getInt("dynamic-simulation-distance.max-distance", 10));
        double low = c.getDouble("dynamic-simulation-distance.lower-below-tps", 17.0);
        double high = c.getDouble("dynamic-simulation-distance.raise-above-tps", 19.5);

        double tps = Math.min(20.0, Bukkit.getTPS()[0]);

        for (World world : Bukkit.getWorlds()) {
            int current = world.getSimulationDistance();
            if (tps < low && current > min) {
                world.setSimulationDistance(current - 1);
            } else if (tps > high && current < max) {
                world.setSimulationDistance(current + 1);
            }
        }
    }

    // ---------------------------------------------------------------- Commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("optimizer.admin")) {
            sender.sendMessage(msg("messages.no-permission"));
            return true;
        }

        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> sendStatus(sender);
            case "clear" -> {
                int removed = clearItems();
                sender.sendMessage(msg("messages.item-cleared", "{count}", String.valueOf(removed)));
            }
            case "limit" -> {
                int removed = limitMobs(true);
                sender.sendMessage(msg("messages.limit-done", "{count}", String.valueOf(removed)));
            }
            case "reload" -> {
                stopTasks();
                reloadConfig();
                startTasks();
                sender.sendMessage(msg("messages.reloaded"));
            }
            default -> sender.sendMessage(Component.text("/" + label + " <status|clear|limit|reload>"));
        }
        return true;
    }

    private void sendStatus(CommandSender sender) {
        double[] tps = Bukkit.getTPS();
        double mspt = Bukkit.getAverageTickTime();
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) / MB;
        long max = rt.maxMemory() / MB;

        int chunks = 0;
        int entities = 0;
        int items = 0;
        for (World w : Bukkit.getWorlds()) {
            chunks += w.getChunkCount();
            entities += w.getEntityCount();
            items += w.getEntitiesByClass(Item.class).size();
        }

        String line = String.format(Locale.ROOT,
                "<gray>TPS (1m/5m/15m): <white>%s / %s / %s</white>\n"
                        + "MSPT: <white>%.1f</white>\n"
                        + "RAM: <white>%d / %d MB</white>\n"
                        + "Chunks: <white>%d</white> | Entities: <white>%d</white> | Items am Boden: <white>%d</white>",
                fmtTps(tps[0]), fmtTps(tps[1]), fmtTps(tps[2]), mspt, used, max, chunks, entities, items);

        sender.sendMessage(msg("messages.prefix").append(Component.newline()).append(MM.deserialize(line)));
    }

    private static String fmtTps(double tps) {
        double t = Math.min(20.0, tps);
        String color = t >= 18.0 ? "green" : (t >= 15.0 ? "yellow" : "red");
        return String.format(Locale.ROOT, "<%s>%.2f</%s>", color, t, color);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && sender.hasPermission("optimizer.admin")) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("status", "clear", "limit", "reload").stream()
                    .filter(s -> s.startsWith(prefix)).toList();
        }
        return List.of();
    }

    // ---------------------------------------------------------------- Helfer

    private Component msg(String path, String... replacements) {
        String text = getConfig().getString(path, "");
        text = text.replace("{prefix}", getConfig().getString("messages.prefix", ""));
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            text = text.replace(replacements[i], replacements[i + 1]);
        }
        return MM.deserialize(text);
    }
}
