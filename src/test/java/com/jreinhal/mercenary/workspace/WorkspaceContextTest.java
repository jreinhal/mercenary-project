package com.jreinhal.mercenary.workspace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link WorkspaceContext} ThreadLocal isolation and
 * concurrent thread safety.
 */
class WorkspaceContextTest {

    @BeforeEach
    void setUp() {
        WorkspaceContext.clear();
        WorkspaceContext.setDefaultWorkspaceId("workspace_default");
    }

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
    }

    @Nested
    @DisplayName("Basic ThreadLocal Behavior")
    class BasicThreadLocal {

        @Test
        @DisplayName("Should return default workspace when no context set")
        void shouldReturnDefaultWhenNoContextSet() {
            assertEquals("workspace_default", WorkspaceContext.getCurrentWorkspaceId());
        }

        @Test
        @DisplayName("Should return set workspace ID")
        void shouldReturnSetWorkspaceId() {
            WorkspaceContext.setCurrentWorkspaceId("ws_alpha");
            assertEquals("ws_alpha", WorkspaceContext.getCurrentWorkspaceId());
        }

        @Test
        @DisplayName("Should fall back to default for null input")
        void shouldFallBackForNull() {
            WorkspaceContext.setCurrentWorkspaceId("ws_alpha");
            WorkspaceContext.setCurrentWorkspaceId(null);
            assertEquals("workspace_default", WorkspaceContext.getCurrentWorkspaceId());
        }

        @Test
        @DisplayName("Should fall back to default for blank input")
        void shouldFallBackForBlank() {
            WorkspaceContext.setCurrentWorkspaceId("ws_alpha");
            WorkspaceContext.setCurrentWorkspaceId("   ");
            assertEquals("workspace_default", WorkspaceContext.getCurrentWorkspaceId());
        }

        @Test
        @DisplayName("Should trim workspace ID")
        void shouldTrimWorkspaceId() {
            WorkspaceContext.setCurrentWorkspaceId("  ws_alpha  ");
            assertEquals("ws_alpha", WorkspaceContext.getCurrentWorkspaceId());
        }

        @Test
        @DisplayName("Clear should remove context and fall back to default")
        void clearShouldRemoveContext() {
            WorkspaceContext.setCurrentWorkspaceId("ws_alpha");
            assertEquals("ws_alpha", WorkspaceContext.getCurrentWorkspaceId());

            WorkspaceContext.clear();
            assertEquals("workspace_default", WorkspaceContext.getCurrentWorkspaceId());
        }
    }

    @Nested
    @DisplayName("Default Workspace ID")
    class DefaultWorkspaceId {

        @Test
        @DisplayName("Should update default workspace ID")
        void shouldUpdateDefaultWorkspaceId() {
            WorkspaceContext.setDefaultWorkspaceId("new_default");
            assertEquals("new_default", WorkspaceContext.getDefaultWorkspaceId());
            // Restore original for other tests
            WorkspaceContext.setDefaultWorkspaceId("workspace_default");
        }

        @Test
        @DisplayName("Should ignore null default workspace")
        void shouldIgnoreNullDefault() {
            WorkspaceContext.setDefaultWorkspaceId("custom");
            WorkspaceContext.setDefaultWorkspaceId(null);
            assertEquals("custom", WorkspaceContext.getDefaultWorkspaceId(),
                    "Null should not change the default");
            WorkspaceContext.setDefaultWorkspaceId("workspace_default");
        }

        @Test
        @DisplayName("Should ignore blank default workspace")
        void shouldIgnoreBlankDefault() {
            WorkspaceContext.setDefaultWorkspaceId("custom");
            WorkspaceContext.setDefaultWorkspaceId("   ");
            assertEquals("custom", WorkspaceContext.getDefaultWorkspaceId(),
                    "Blank should not change the default");
            WorkspaceContext.setDefaultWorkspaceId("workspace_default");
        }
    }

    @Nested
    @DisplayName("Concurrent Thread Safety")
    class ConcurrentThreadSafety {

        @Test
        @DisplayName("Concurrent threads should have isolated workspace contexts")
        void concurrentThreadsShouldHaveIsolatedContexts() throws Exception {
            AtomicReference<String> thread1Value = new AtomicReference<>();
            AtomicReference<String> thread2Value = new AtomicReference<>();
            CountDownLatch bothSet = new CountDownLatch(2);
            CountDownLatch bothRead = new CountDownLatch(2);

            Thread t1 = new Thread(() -> {
                WorkspaceContext.setCurrentWorkspaceId("ws_thread1");
                bothSet.countDown();
                try {
                    bothSet.await(); // Wait for both threads to set their values
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                thread1Value.set(WorkspaceContext.getCurrentWorkspaceId());
                bothRead.countDown();
                WorkspaceContext.clear();
            });

            Thread t2 = new Thread(() -> {
                WorkspaceContext.setCurrentWorkspaceId("ws_thread2");
                bothSet.countDown();
                try {
                    bothSet.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                thread2Value.set(WorkspaceContext.getCurrentWorkspaceId());
                bothRead.countDown();
                WorkspaceContext.clear();
            });

            t1.start();
            t2.start();
            bothRead.await();
            t1.join();
            t2.join();

            assertEquals("ws_thread1", thread1Value.get(),
                    "Thread 1 should see its own workspace, not thread 2's");
            assertEquals("ws_thread2", thread2Value.get(),
                    "Thread 2 should see its own workspace, not thread 1's");
        }

        @Test
        @DisplayName("Child thread should not inherit parent workspace context")
        void childThreadShouldNotInheritContext() throws Exception {
            WorkspaceContext.setCurrentWorkspaceId("ws_parent");
            AtomicReference<String> childValue = new AtomicReference<>();

            Thread child = new Thread(() -> {
                childValue.set(WorkspaceContext.getCurrentWorkspaceId());
                WorkspaceContext.clear();
            });
            child.start();
            child.join();

            assertEquals("workspace_default", childValue.get(),
                    "Child thread should get default workspace, not parent's context");
            assertEquals("ws_parent", WorkspaceContext.getCurrentWorkspaceId(),
                    "Parent context should be unaffected by child thread");
        }
    }
}
