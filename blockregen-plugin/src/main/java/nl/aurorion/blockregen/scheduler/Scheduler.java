package nl.aurorion.blockregen.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class Scheduler {

    private static final boolean FOLIA = classExists("io.papermc.paper.threadedregions.RegionizedServer");

    private Scheduler() {
    }

    public static boolean isFolia() {
        return FOLIA;
    }

    public static TaskHandle runGlobal(Plugin plugin, Runnable runnable) {
        if (!FOLIA) {
            return wrap(Bukkit.getScheduler().runTask(plugin, runnable));
        }
        return invokeTask(scheduler("getGlobalRegionScheduler"), "run",
                new Class<?>[]{Plugin.class, Consumer.class}, plugin, consumer(runnable));
    }

    public static TaskHandle runGlobalLater(Plugin plugin, Runnable runnable, long delayTicks) {
        if (!FOLIA) {
            return wrap(Bukkit.getScheduler().runTaskLater(plugin, runnable, delayTicks));
        }
        return invokeTask(scheduler("getGlobalRegionScheduler"), "runDelayed",
                new Class<?>[]{Plugin.class, Consumer.class, long.class}, plugin, consumer(runnable), validDelay(delayTicks));
    }

    public static TaskHandle repeatGlobal(Plugin plugin, Runnable runnable, long delayTicks, long periodTicks) {
        if (!FOLIA) {
            return wrap(Bukkit.getScheduler().runTaskTimer(plugin, runnable, delayTicks, periodTicks));
        }
        return invokeTask(scheduler("getGlobalRegionScheduler"), "runAtFixedRate",
                new Class<?>[]{Plugin.class, Consumer.class, long.class, long.class},
                plugin, consumer(runnable), validDelay(delayTicks), validDelay(periodTicks));
    }

    public static TaskHandle runAt(Plugin plugin, Location location, Runnable runnable) {
        if (!FOLIA) {
            return wrap(Bukkit.getScheduler().runTask(plugin, runnable));
        }
        return invokeTask(scheduler("getRegionScheduler"), "run",
                new Class<?>[]{Plugin.class, Location.class, Consumer.class}, plugin, location, consumer(runnable));
    }

    public static TaskHandle runAtLater(Plugin plugin, Location location, Runnable runnable, long delayTicks) {
        if (!FOLIA) {
            return wrap(Bukkit.getScheduler().runTaskLater(plugin, runnable, delayTicks));
        }
        return invokeTask(scheduler("getRegionScheduler"), "runDelayed",
                new Class<?>[]{Plugin.class, Location.class, Consumer.class, long.class},
                plugin, location, consumer(runnable), validDelay(delayTicks));
    }

    public static TaskHandle runFor(Plugin plugin, Entity entity, Runnable runnable) {
        if (!FOLIA) {
            return wrap(Bukkit.getScheduler().runTask(plugin, runnable));
        }
        return invokeTask(entityScheduler(entity), "run",
                new Class<?>[]{Plugin.class, Consumer.class, Runnable.class}, plugin, consumer(runnable), null);
    }

    public static TaskHandle runForLater(Plugin plugin, Entity entity, Runnable runnable, long delayTicks) {
        if (!FOLIA) {
            return wrap(Bukkit.getScheduler().runTaskLater(plugin, runnable, delayTicks));
        }
        return invokeTask(entityScheduler(entity), "runDelayed",
                new Class<?>[]{Plugin.class, Consumer.class, Runnable.class, long.class},
                plugin, consumer(runnable), null, validDelay(delayTicks));
    }

    public static TaskHandle runAsync(Plugin plugin, Runnable runnable) {
        if (!FOLIA) {
            return wrap(Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable));
        }
        return invokeTask(scheduler("getAsyncScheduler"), "runNow",
                new Class<?>[]{Plugin.class, Consumer.class}, plugin, consumer(runnable));
    }

    public static TaskHandle runAsyncLater(Plugin plugin, Runnable runnable, long delayTicks) {
        if (!FOLIA) {
            return wrap(Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, runnable, delayTicks));
        }
        return invokeTask(scheduler("getAsyncScheduler"), "runDelayed",
                new Class<?>[]{Plugin.class, Consumer.class, long.class, TimeUnit.class},
                plugin, consumer(runnable), validDelay(delayTicks) * 50L, TimeUnit.MILLISECONDS);
    }

    private static Object scheduler(String method) {
        return invoke(Bukkit.getServer(), method, new Class<?>[0]);
    }

    private static Object entityScheduler(Entity entity) {
        return invoke(entity, "getScheduler", new Class<?>[0]);
    }

    private static TaskHandle invokeTask(Object target, String method, Class<?>[] parameterTypes, Object... arguments) {
        Object task = invoke(target, method, parameterTypes, arguments);
        return task == null ? EmptyTaskHandle.INSTANCE : new ReflectiveTaskHandle(task);
    }

    private static Object invoke(Object target, String method, Class<?>[] parameterTypes, Object... arguments) {
        try {
            Method reflectedMethod = target.getClass().getMethod(method, parameterTypes);
            return reflectedMethod.invoke(target, arguments);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new IllegalStateException("Folia scheduler API is unavailable", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IllegalStateException("Folia scheduler invocation failed", cause);
        }
    }

    private static Consumer<Object> consumer(Runnable runnable) {
        return ignored -> runnable.run();
    }

    private static long validDelay(long ticks) {
        return Math.max(1L, ticks);
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, Scheduler.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    private static TaskHandle wrap(BukkitTask task) {
        return new TaskHandle() {
            @Override
            public void cancel() {
                task.cancel();
            }

            @Override
            public String getId() {
                return String.valueOf(task.getTaskId());
            }
        };
    }

    private static final class ReflectiveTaskHandle implements TaskHandle {
        private final Object task;

        private ReflectiveTaskHandle(Object task) {
            this.task = task;
        }

        @Override
        public void cancel() {
            invoke(task, "cancel", new Class<?>[0]);
        }

        @Override
        public String getId() {
            return Integer.toHexString(System.identityHashCode(task));
        }
    }

    private enum EmptyTaskHandle implements TaskHandle {
        INSTANCE;

        @Override
        public void cancel() {
        }

        @Override
        public String getId() {
            return "retired";
        }
    }
}
