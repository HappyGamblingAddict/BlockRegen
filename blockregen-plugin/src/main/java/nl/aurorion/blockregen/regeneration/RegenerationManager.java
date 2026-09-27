package nl.aurorion.blockregen.regeneration;

import lombok.Getter;
import lombok.extern.java.Log;
import nl.aurorion.blockregen.AutoSaveTask;
import nl.aurorion.blockregen.BlockRegenPlugin;
import nl.aurorion.blockregen.Pair;
import nl.aurorion.blockregen.material.BlockRegenMaterial;
import nl.aurorion.blockregen.preset.BlockPreset;
import nl.aurorion.blockregen.regeneration.struct.RegenerationProcess;
import nl.aurorion.blockregen.regeneration.struct.SimpleLocation;
import nl.aurorion.blockregen.region.struct.RegenerationArea;
import nl.aurorion.blockregen.scheduler.Scheduler;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

@Log
public class RegenerationManager {

    private final BlockRegenPlugin plugin;

    private final Map<Block, RegenerationProcess> cache = new ConcurrentHashMap<>();

    @Getter
    private AutoSaveTask autoSaveTask;

    @Getter
    private boolean retry = false;

    @Getter
    private volatile boolean storageLoadComplete = false;

    private final Set<UUID> bypass = ConcurrentHashMap.newKeySet();

    private final Set<UUID> dataCheck = ConcurrentHashMap.newKeySet();

    public RegenerationManager(BlockRegenPlugin plugin) {
        this.plugin = plugin;
    }

    // --- Bypass

    public boolean hasBypass(@NotNull Player player) {
        return bypass.contains(player.getUniqueId());
    }

    /**
     * Switch the bypass status of the player. Return the state after the change.
     */
    public boolean switchBypass(@NotNull Player player) {
        if (bypass.contains(player.getUniqueId())) {
            bypass.remove(player.getUniqueId());
            return false;
        } else {
            bypass.add(player.getUniqueId());
            return true;
        }
    }

    // --- Data Check

    public boolean hasDataCheck(@NotNull Player player) {
        return dataCheck.contains(player.getUniqueId());
    }

    public boolean switchDataCheck(@NotNull Player player) {
        if (dataCheck.contains(player.getUniqueId())) {
            dataCheck.remove(player.getUniqueId());
            return false;
        } else {
            dataCheck.add(player.getUniqueId());
            return true;
        }
    }

    @NotNull
    public RegenerationProcess createProcess(@NotNull Block block, @NotNull BlockRegenMaterial originalMaterial, @NotNull BlockPreset preset, @Nullable RegenerationArea area) {
        RegenerationProcess process = new RegenerationProcess(block, preset, originalMaterial);

        process.setWorldName(block.getWorld().getName());
        if (area != null) {
            process.setRegionName(area.getName());
        }
        return process;
    }

    /**
     * Helper for creating regeneration processes.
     */
    @NotNull
    public RegenerationProcess createProcess(@NotNull Block block, @NotNull BlockPreset preset, @Nullable RegenerationArea region) {
        Objects.requireNonNull(block);
        Objects.requireNonNull(preset);

        Pair<String, BlockRegenMaterial> result = plugin.getMaterialManager().getMaterial(block);

        if (result == null) {
            // todo: well what now, the preset probably already matched?
            throw new IllegalStateException("Shouldn't return null...");
        }

        RegenerationProcess process = new RegenerationProcess(block, preset, result.getSecond());

        process.setWorldName(block.getWorld().getName());
        if (region != null) {
            process.setRegionName(region.getName());
        }
        return process;
    }

    /**
     * Register the process as running.
     */
    public boolean registerProcess(@NotNull RegenerationProcess process) {
        Objects.requireNonNull(process);

        RegenerationProcess existing = cache.putIfAbsent(process.getBlock(), process);
        if (existing != null && existing != process) {
            log.fine(() -> String.format("Cache already contains process %s", process.getId()));
            return false;
        }

        log.fine(() -> "Registered regeneration process " + process);
        return true;
    }

    @Nullable
    public RegenerationProcess getProcess(@NotNull Block block) {
        return this.cache.get(block);
    }

    public boolean isRegenerating(@NotNull Block block) {
        RegenerationProcess process = getProcess(block);
        return process != null && process.getRegenerationTime() > System.currentTimeMillis();
    }

    public void removeProcess(RegenerationProcess process) {
        if (cache.remove(process.getBlock()) != null) {
            log.fine(() -> String.format("Removed process from cache: %s", process));
        } else {
            log.fine(() -> String.format("Process %s not found, not removed.", process));
        }
    }

    public void removeProcess(@NotNull Block block) {
        cache.remove(block);
    }

    public void startAutoSave() {
        this.autoSaveTask = new AutoSaveTask(plugin);

        autoSaveTask.load();
        autoSaveTask.start();
    }

    public void reloadAutoSave() {
        if (autoSaveTask == null) {
            startAutoSave();
        } else {
            autoSaveTask.stop();
            autoSaveTask.load();
            autoSaveTask.start();
        }
    }

    // Revert blocks before disabling
    public void revertAll() {
        cache.values().forEach(RegenerationProcess::revertBlock);
    }

    public void stopAll() {
        cache.values().forEach(RegenerationProcess::stop);
    }

    public void save() {
        save(false);
    }

    public void save(boolean sync) {
        if (!storageLoadComplete) {
            log.warning("Skipped saving regeneration data because startup loading did not complete.");
            return;
        }

        final File dataFile = new File(plugin.getDataFolder(), "/Data.json");

        if (cache.isEmpty()) {
            log.fine(() -> "No processes to save.");
            try {
                Files.write(dataFile.toPath(), "[]\n".getBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            } catch (IOException e) {
                log.severe(() -> "Failed to create empty Data.json.");

                // Try to force delete.
                //noinspection ResultOfMethodCallIgnored
                dataFile.delete();
            }
            return;
        }

        cache.values().stream()
                .filter(RegenerationProcess::shouldRegenerate)
                .forEach(process -> process.setTimeLeft(Math.max(0L,
                        process.getRegenerationTime() - System.currentTimeMillis())));

        final List<RegenerationProcess> finalCache = new ArrayList<>(cache.values());

        CompletableFuture<Void> future = plugin.getGsonHelper().save(finalCache, dataFile.toPath())
                .exceptionally(e -> {
                    log.log(Level.SEVERE, "Could not save processes: " + e.getMessage(), e);
                    return null;
                });

        if (sync) {
            future.join();
        }

        log.fine(() -> "Saved " + finalCache.size() + " regeneration processes..");
    }

    private boolean convertProcess(@NotNull RegenerationProcess process) {
        return process.convertLocation() && process.convertPreset();
    }

    private CompletableFuture<List<RegenerationProcess>> loadFromStorage() {
        return plugin.getGsonHelper().loadListAsync(plugin.getDataFolder().getPath() + "/Data.json", RegenerationProcess.class);
    }

    public void load() {
        loadFromStorage().thenAccept(loadedProcesses -> Scheduler.runGlobal(plugin, () -> {
            if (loadedProcesses == null) {
                storageLoadComplete = true;
                return;
            }

            if (plugin.getPresetManager().isRetry() && this.retry) {
                log.warning("Some process couldn't be loaded, but might be salvageable. Trying again after a complete server load...");
            } else {
                activateProcesses(loadedProcesses);
            }
        })).exceptionally(e -> {
            log.log(Level.SEVERE, "Could not load processes: " + e.getMessage(), e);
            return null;
        });
    }

    public void reattemptLoad() {
        if (!retry) {
            return;
        }

        this.retry = false;
        this.storageLoadComplete = false;

        loadFromStorage().thenAccept(loadedProcesses -> Scheduler.runGlobal(plugin, () -> {
            if (loadedProcesses == null) {
                throw new RuntimeException("Could not load processes from storage.");
            }

            activateProcesses(loadedProcesses);
        })).exceptionally(e -> {
            log.log(Level.SEVERE, "Could not load processes: " + e.getMessage(), e);
            return null;
        });
    }

    private void activateProcesses(List<RegenerationProcess> loadedProcesses) {
        Map<RegenerationProcess, Location> pending = new LinkedHashMap<>();
        for (RegenerationProcess process : loadedProcesses) {
            if (process == null || process.getLocation() == null) {
                continue;
            }

            SimpleLocation stored = process.getLocation();
            World world = plugin.getServer().getWorld(stored.getWorld());
            if (world == null) {
                log.warning("Could not load process " + process + ", world is invalid or not loaded.");
                continue;
            }

            Location location = new Location(world, stored.getX(), stored.getY(), stored.getZ());
            pending.put(process, location);
        }

        AtomicInteger remaining = new AtomicInteger(pending.size());
        for (Map.Entry<RegenerationProcess, Location> entry : pending.entrySet()) {
            RegenerationProcess process = entry.getKey();
            Scheduler.runAt(plugin, entry.getValue(), () -> {
                try {
                    if (convertProcess(process)) {
                        process.start();
                    }
                } finally {
                    if (remaining.decrementAndGet() == 0) {
                        storageLoadComplete = true;
                    }
                }
            });
        }
        if (remaining.get() == 0) {
            storageLoadComplete = true;
        }
        log.info("Scheduled " + remaining.get() + " regeneration process(es) for loading...");
    }

    @NotNull
    public Collection<RegenerationProcess> getCache() {
        return Collections.unmodifiableCollection(cache.values());
    }
}
