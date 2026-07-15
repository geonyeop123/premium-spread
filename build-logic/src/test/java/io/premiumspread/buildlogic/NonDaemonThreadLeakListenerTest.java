package io.premiumspread.buildlogic;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class NonDaemonThreadLeakListenerTest {
    @Test
    void springCacheCleanupIsNoOpWhenSpringTestIsAbsent() {
        assertDoesNotThrow(NonDaemonThreadLeakListener::closeSpringTestContextCache);
    }

    @Test
    void ignoresThreadThatTerminatesDuringGracePeriod() throws InterruptedException {
        String propertyName = "premiumspread.test.leak-detection";
        String previousValue = System.getProperty(propertyName);
        System.setProperty(propertyName, "true");

        NonDaemonThreadLeakListener listener = new NonDaemonThreadLeakListener();
        listener.launcherSessionOpened(null);
        Thread finishingThread = new Thread(() -> sleep(150L), "short-lived-test-thread");
        finishingThread.setDaemon(false);
        finishingThread.start();

        try {
            listener.launcherSessionClosed(null);
        } finally {
            finishingThread.join(2_000L);
            restoreProperty(propertyName, previousValue);
        }
    }

    @Test
    void rejectsNewNonDaemonThreadThatSurvivesTheLauncherSession() throws InterruptedException {
        String propertyName = "premiumspread.test.leak-detection";
        String previousValue = System.getProperty(propertyName);
        System.setProperty(propertyName, "true");

        NonDaemonThreadLeakListener listener = new NonDaemonThreadLeakListener();
        listener.launcherSessionOpened(null);

        CountDownLatch releaseThread = new CountDownLatch(1);
        Thread leakedThread = new Thread(() -> await(releaseThread), "intentional-leak-test-thread");
        leakedThread.setDaemon(false);
        leakedThread.start();

        try {
            assertThrows(AssertionError.class, () -> listener.launcherSessionClosed(null));
        } finally {
            releaseThread.countDown();
            leakedThread.join(2_000L);
            restoreProperty(propertyName, previousValue);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void restoreProperty(String propertyName, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(propertyName);
        } else {
            System.setProperty(propertyName, previousValue);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
