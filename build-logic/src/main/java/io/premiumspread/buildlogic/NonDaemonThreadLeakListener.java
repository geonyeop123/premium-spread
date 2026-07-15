package io.premiumspread.buildlogic;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/** Fails a test worker when a test-owned non-daemon thread survives the launcher session. */
public final class NonDaemonThreadLeakListener implements LauncherSessionListener {
    private Set<Long> baselineThreadIds = Set.of();

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        if (enabled()) {
            baselineThreadIds = liveNonDaemonThreads().keySet();
        }
    }

    @Override
    public void launcherSessionClosed(LauncherSession session) {
        if (!enabled()) {
            return;
        }

        closeSpringTestContextCache();
        Map<Long, Thread> leakedThreads = awaitLeakedThreads();
        leakedThreads.values().removeIf(thread -> !thread.isAlive());
        if (!leakedThreads.isEmpty()) {
            String details = leakedThreads.values().stream()
                .sorted(Comparator.comparing(Thread::getName).thenComparingLong(Thread::threadId))
                .map(thread -> thread.getName() + "(id=" + thread.threadId() + ", state=" + thread.getState() + ")")
                .collect(Collectors.joining(", "));
            throw new AssertionError("Non-daemon test thread leak detected: " + details);
        }
    }

    private Map<Long, Thread> awaitLeakedThreads() {
        Map<Long, Thread> leakedThreads = Map.of();
        for (int attempt = 0; attempt < 10; attempt++) {
            leakedThreads = liveNonDaemonThreads();
            leakedThreads.keySet().removeAll(baselineThreadIds);
            leakedThreads.remove(Thread.currentThread().threadId());
            if (leakedThreads.isEmpty()) {
                return leakedThreads;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while checking non-daemon test thread leaks", exception);
            }
        }
        leakedThreads = liveNonDaemonThreads();
        leakedThreads.keySet().removeAll(baselineThreadIds);
        leakedThreads.remove(Thread.currentThread().threadId());
        return leakedThreads;
    }

    static void closeSpringTestContextCache() {
        try {
            Class<?> delegate = Class.forName(
                "org.springframework.test.context.cache.DefaultCacheAwareContextLoaderDelegate"
            );
            var cacheField = delegate.getDeclaredField("defaultContextCache");
            cacheField.setAccessible(true);
            Object cache = cacheField.get(null);
            var contextMapField = cache.getClass().getDeclaredField("contextMap");
            contextMapField.setAccessible(true);
            Object contextMapValue = contextMapField.get(cache);
            if (!(contextMapValue instanceof Map<?, ?> contextMap)) {
                throw new AssertionError("Spring Test contextMap is not a Map");
            }

            List<Object> contexts;
            synchronized (contextMap) {
                contexts = new ArrayList<>(contextMap.values());
            }
            Collections.reverse(contexts);
            Class<?> configurableContext = Class.forName(
                "org.springframework.context.ConfigurableApplicationContext"
            );
            var closeMethod = configurableContext.getMethod("close");
            Set<Object> closedByIdentity = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Object context : contexts) {
                if (configurableContext.isInstance(context) && closedByIdentity.add(context)) {
                    closeMethod.invoke(context);
                }
            }

            Class<?> contextCache = Class.forName("org.springframework.test.context.cache.ContextCache");
            contextCache.getMethod("clear").invoke(cache);
        } catch (ClassNotFoundException exception) {
            // Non-Spring unit tests have no shared context lifecycle to close.
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Failed to close the Spring Test application context cache", exception);
        }
    }

    private static Map<Long, Thread> liveNonDaemonThreads() {
        return Thread.getAllStackTraces().keySet().stream()
            .filter(Thread::isAlive)
            .filter(thread -> !thread.isDaemon())
            .collect(Collectors.toMap(Thread::threadId, thread -> thread));
    }

    private static boolean enabled() {
        return Boolean.parseBoolean(System.getProperty("premiumspread.test.leak-detection", "false"));
    }
}
