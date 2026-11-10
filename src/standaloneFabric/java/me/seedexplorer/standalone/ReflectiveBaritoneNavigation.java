package me.seedexplorer.standalone;

import net.minecraft.core.BlockPos;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/** Loads Baritone only when present; no Baritone classes enter the standalone link graph. */
public final class ReflectiveBaritoneNavigation implements StandaloneNavigationCapability {
    private volatile String lastStatus = "idle";

    @Override
    public boolean available() {
        try {
            primaryBaritone();
            return true;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return false;
        }
    }

    @Override
    public Result navigate(BlockPos target, int radius) {
        if (target == null) return new Result(false, "Missing navigation target");
        try {
            Object baritone = primaryBaritone();
            Method processGetter = baritone.getClass().getMethod("getCustomGoalProcess");
            Object process = processGetter.invoke(baritone);
            Object goal = createGoal(target, Math.max(1, radius));
            Method setGoal = findSingleArgumentMethod(process.getClass(), "setGoalAndPath");
            setGoal.invoke(process, goal);
            lastStatus = "pathing to " + target.toShortString();
            return new Result(true, lastStatus);
        } catch (ReflectiveOperationException | LinkageError error) {
            lastStatus = "Baritone error: " + error.getClass().getSimpleName();
            return new Result(false, lastStatus);
        }
    }

    @Override
    public void stop() {
        try {
            Object baritone = primaryBaritone();
            Object behavior = baritone.getClass().getMethod("getPathingBehavior").invoke(baritone);
            behavior.getClass().getMethod("cancelEverything").invoke(behavior);
            lastStatus = "stopped";
        } catch (ReflectiveOperationException | LinkageError error) {
            lastStatus = "unavailable";
        }
    }

    @Override
    public String status() {
        return lastStatus;
    }

    private static Object primaryBaritone() throws ReflectiveOperationException {
        Class<?> api = Class.forName("baritone.api.BaritoneAPI");
        Object provider = api.getMethod("getProvider").invoke(null);
        Object baritone = provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
        if (baritone == null) throw new ReflectiveOperationException("Primary Baritone is unavailable");
        return baritone;
    }

    private static Object createGoal(BlockPos target, int radius) throws ReflectiveOperationException {
        Class<?> goalNear = Class.forName("baritone.api.pathing.goals.GoalNear");
        for (Constructor<?> constructor : goalNear.getConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length == 2 && parameters[0].isAssignableFrom(BlockPos.class)
                && parameters[1] == int.class) {
                return constructor.newInstance(target, radius);
            }
            if (parameters.length == 4 && parameters[0] == int.class
                && parameters[1] == int.class && parameters[2] == int.class
                && parameters[3] == int.class) {
                return constructor.newInstance(target.getX(), target.getY(), target.getZ(), radius);
            }
        }
        throw new NoSuchMethodException("Supported GoalNear constructor");
    }

    private static Method findSingleArgumentMethod(Class<?> type, String name)
        throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 1) return method;
        }
        throw new NoSuchMethodException(type.getName() + '.' + name);
    }
}
