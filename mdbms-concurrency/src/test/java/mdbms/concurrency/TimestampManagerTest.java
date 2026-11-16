package mdbms.concurrency;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import mdbms.models.data.DataItem;
import mdbms.models.data.RowId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TimestampManagerTest {

    private static final DataItem ITEM_1 = new DataItem("products", new RowId("products", 1, 0));
    private static final DataItem ITEM_2 = new DataItem("products", new RowId("products", 2, 0));

    private TimestampManager manager;

    @BeforeEach
    void setUp() {
        manager = new TimestampManager();
    }

    @Nested
    @DisplayName("Default & Custom Initial Timestamps")
    class InitializationTests {

        @Test
        @DisplayName("Default initial read timestamp for unaccessed item is 0")
        void testGetReadTS_UnaccessedItemDefault_ShouldReturnZero() {
            // Arrange (item unaccessed)

            // Act & Assert
            assertEquals(0L, manager.getReadTS(ITEM_1));
        }

        @Test
        @DisplayName("Default initial write timestamp for unaccessed item is 0")
        void testGetWriteTS_UnaccessedItemDefault_ShouldReturnZero() {
            // Arrange (item unaccessed)

            // Act & Assert
            assertEquals(0L, manager.getWriteTS(ITEM_1));
        }

        @Test
        @DisplayName("Custom initial timestamp applies to unaccessed read timestamp")
        void testGetReadTS_CustomInitialTimestamp_ShouldReturnConfiguredValue() {
            // Arrange
            TimestampManager customManager = new TimestampManager(100L);

            // Act & Assert
            assertEquals(100L, customManager.getReadTS(ITEM_1));
        }

        @Test
        @DisplayName("Custom initial timestamp applies to unaccessed write timestamp")
        void testGetWriteTS_CustomInitialTimestamp_ShouldReturnConfiguredValue() {
            // Arrange
            TimestampManager customManager = new TimestampManager(100L);

            // Act & Assert
            assertEquals(100L, customManager.getWriteTS(ITEM_1));
        }
    }

    @Nested
    @DisplayName("Null Handling & Validation")
    class NullHandlingTests {

        @Test
        @DisplayName("getReadTS throws NullPointerException on null data")
        void testGetReadTS_WithNullData_ShouldThrowNullPointerException() {
            // Arrange & Act & Assert
            assertThrows(NullPointerException.class, () -> manager.getReadTS(null));
        }

        @Test
        @DisplayName("getWriteTS throws NullPointerException on null data")
        void testGetWriteTS_WithNullData_ShouldThrowNullPointerException() {
            // Arrange & Act & Assert
            assertThrows(NullPointerException.class, () -> manager.getWriteTS(null));
        }

        @Test
        @DisplayName("updateReadTS throws NullPointerException on null data")
        void testUpdateReadTS_WithNullData_ShouldThrowNullPointerException() {
            // Arrange & Act & Assert
            assertThrows(NullPointerException.class, () -> manager.updateReadTS(null, 50L));
        }

        @Test
        @DisplayName("updateWriteTS throws NullPointerException on null data")
        void testUpdateWriteTS_WithNullData_ShouldThrowNullPointerException() {
            // Arrange & Act & Assert
            assertThrows(NullPointerException.class, () -> manager.updateWriteTS(null, 50L));
        }

        @Test
        @DisplayName("remove with null data item does not throw")
        void testRemove_WithNullData_ShouldNotThrow() {
            // Arrange & Act & Assert
            assertDoesNotThrow(() -> manager.remove(null));
        }
    }

    @Nested
    @DisplayName("Monotonic Updates & State Management")
    class StateAndMonotonicityTests {

        @Test
        @DisplayName("updateReadTS ignores smaller timestamp and retains maximum")
        void testUpdateReadTS_WithSmallerTimestamp_ShouldRetainMax() {
            // Arrange
            manager.updateReadTS(ITEM_1, 50L);

            // Act
            manager.updateReadTS(ITEM_1, 20L); // Smaller

            // Assert
            assertEquals(50L, manager.getReadTS(ITEM_1));
        }

        @Test
        @DisplayName("updateReadTS updates timestamp when given a larger value")
        void testUpdateReadTS_WithLargerTimestamp_ShouldUpdateValue() {
            // Arrange
            manager.updateReadTS(ITEM_1, 50L);

            // Act
            manager.updateReadTS(ITEM_1, 80L); // Larger

            // Assert
            assertEquals(80L, manager.getReadTS(ITEM_1));
        }

        @Test
        @DisplayName("updateWriteTS ignores smaller timestamp and retains maximum")
        void testUpdateWriteTS_WithSmallerTimestamp_ShouldRetainMax() {
            // Arrange
            manager.updateWriteTS(ITEM_1, 70L);

            // Act
            manager.updateWriteTS(ITEM_1, 30L); // Smaller

            // Assert
            assertEquals(70L, manager.getWriteTS(ITEM_1));
        }

        @Test
        @DisplayName("updateWriteTS updates timestamp when given a larger value")
        void testUpdateWriteTS_WithLargerTimestamp_ShouldUpdateValue() {
            // Arrange
            manager.updateWriteTS(ITEM_1, 70L);

            // Act
            manager.updateWriteTS(ITEM_1, 95L); // Larger

            // Assert
            assertEquals(95L, manager.getWriteTS(ITEM_1));
        }

        @Test
        @DisplayName("remove resets item timestamps back to default")
        void testRemove_ExistingItem_ShouldResetItemTimestamps() {
            // Arrange
            manager.updateReadTS(ITEM_1, 50L);
            manager.updateWriteTS(ITEM_1, 60L);

            // Act
            manager.remove(ITEM_1);

            // Assert
            assertEquals(0L, manager.getReadTS(ITEM_1));
            assertEquals(0L, manager.getWriteTS(ITEM_1));
        }

        @Test
        @DisplayName("clear empties all tracked items in manager")
        void testClear_WhenInvoked_ShouldRemoveAllEntries() {
            // Arrange
            manager.updateReadTS(ITEM_1, 10L);
            manager.updateWriteTS(ITEM_2, 20L);

            // Act
            manager.clear();

            // Assert
            assertTrue(manager.isEmpty());
            assertEquals(0, manager.size());
        }
    }

    @Nested
    @DisplayName("Concurrency & Thread Safety")
    class ConcurrencyTests {

        @Test
        @DisplayName("Concurrent timestamp updates maintain maximum timestamp reliably")
        void testConcurrentUpdates_MultipleThreads_ShouldMaintainMaximumTimestamp() throws InterruptedException {
            // Arrange
            int threads = 10;
            int updatesPerThread = 100;
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            CountDownLatch latch = new CountDownLatch(threads);

            // Act
            for (int i = 0; i < threads; i++) {
                final int threadId = i;
                executor.submit(() -> {
                    try {
                        for (int j = 1; j <= updatesPerThread; j++) {
                            long ts = (long) threadId * 1000 + j;
                            manager.updateReadTS(ITEM_1, ts);
                            manager.updateWriteTS(ITEM_1, ts);
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            executor.shutdown();

            // Assert
            long expectedMax = (long) (threads - 1) * 1000 + updatesPerThread;
            assertEquals(expectedMax, manager.getReadTS(ITEM_1));
            assertEquals(expectedMax, manager.getWriteTS(ITEM_1));
        }
    }
}
