package nl.aurorion.blockregen;

import lombok.Getter;
import lombok.extern.java.Log;
import nl.aurorion.blockregen.scheduler.Scheduler;
import nl.aurorion.blockregen.scheduler.TaskHandle;

@Log
public class AutoSaveTask implements Runnable {

    private int period;

    private TaskHandle task;

    @Getter
    private boolean running = false;

    private final BlockRegenPlugin plugin;

    public AutoSaveTask(BlockRegenPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        this.period = plugin.getConfig().getInt("Auto-Save.Interval", 300);
    }

    public void start() {
        if (running) {
            stop();
        }

        running = true;
        task = Scheduler.repeatGlobal(plugin, this, period * 20L, period * 20L);
        log.info("Starting auto-save.. with an interval of " + period + " seconds.");
    }

    public void stop() {
        if (!running) {
            return;
        }

        if (task == null) {
            running = false;
            return;
        }

        task.cancel();
        task = null;
        running = false;
    }

    @Override
    public void run() {
        plugin.getRegenerationManager().save();
        plugin.getRegionManager().save();
    }
}
