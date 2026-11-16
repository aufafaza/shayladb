package mdbms.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import mdbms.models.data.DataItem;
import mdbms.models.data.RowId;
import mdbms.models.transaction.Operation;
import mdbms.models.transaction.OperationType;
import mdbms.models.transaction.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ScheduleVerifierTest {

    private static final DataItem ITEM_X = new DataItem("accounts", new RowId("accounts", 1, 0));
    private static final DataItem ITEM_Y = new DataItem("accounts", new RowId("accounts", 2, 0));
    private static final DataItem ITEM_Z = new DataItem("accounts", new RowId("accounts", 3, 0));

    private Transaction t1;
    private Transaction t2;
    private Transaction t3;
    private Transaction t4;

    @BeforeEach
    void setUp() {
        t1 = new Transaction(1, 100L);
        t2 = new Transaction(2, 200L);
        t3 = new Transaction(3, 300L);
        t4 = new Transaction(4, 400L);
    }

    @Nested
    @DisplayName("Empty & Edge Case Inputs")
    class EdgeCaseInputTests {

        @Test
        @DisplayName("Empty schedule is serializable")
        void testIsSerializable_EmptySchedule_ShouldReturnTrue() {
            // Arrange
            ScheduleVerifier verifier = new ScheduleVerifier();

            // Act & Assert
            assertTrue(verifier.isSerializable());
        }

        @Test
        @DisplayName("Empty schedule returns empty equivalent serial order")
        void testGetEquivalentSerialSchedule_EmptySchedule_ShouldReturnEmptyList() {
            // Arrange
            ScheduleVerifier verifier = new ScheduleVerifier();

            // Act
            List<Transaction> serialOrder = verifier.getEquivalentSerialSchedule();

            // Assert
            assertNotNull(serialOrder);
            assertTrue(serialOrder.isEmpty());
        }

        @Test
        @DisplayName("null history in constructor defaults to empty history safely")
        void testConstructor_WithNullHistory_ShouldDefaultToEmptyList() {
            // Arrange & Act
            ScheduleVerifier verifier = new ScheduleVerifier(null);

            // Assert
            assertTrue(verifier.getHistory().isEmpty());
            assertTrue(verifier.isSerializable());
        }

        @Test
        @DisplayName("setHistory with null resets history to empty list safely")
        void testSetHistory_WithNull_ShouldResetToEmptyList() {
            // Arrange
            ScheduleVerifier verifier = new ScheduleVerifier(List.of(
                new Operation(t1, OperationType.READ, ITEM_X)
            ));

            // Act
            verifier.setHistory(null);

            // Assert
            assertTrue(verifier.getHistory().isEmpty());
            assertTrue(verifier.isSerializable());
        }

        @Test
        @DisplayName("addOperation ignores null operation")
        void testAddOperation_WithNull_ShouldBeIgnored() {
            // Arrange
            ScheduleVerifier verifier = new ScheduleVerifier();

            // Act
            verifier.addOperation(null);

            // Assert
            assertTrue(verifier.getHistory().isEmpty());
        }

        @Test
        @DisplayName("Operations with null transaction are ignored during graph construction")
        void testBuildPrecedenceGraph_WithNullTransaction_ShouldIgnoreOperation() {
            // Arrange
            List<Operation> history = List.of(
                new Operation(null, OperationType.READ, ITEM_X),
                new Operation(t1, OperationType.WRITE, ITEM_X),
                new Operation(t2, OperationType.READ, ITEM_X)
            );
            ScheduleVerifier verifier = new ScheduleVerifier(history);

            // Act
            List<Transaction> serialOrder = verifier.getEquivalentSerialSchedule();

            // Assert
            assertEquals(List.of(t1, t2), serialOrder);
        }

        @Test
        @DisplayName("Operations with null data item are ignored during graph construction")
        void testBuildPrecedenceGraph_WithNullDataItem_ShouldIgnoreOperation() {
            // Arrange
            List<Operation> history = List.of(
                new Operation(t1, OperationType.WRITE, null),
                new Operation(t1, OperationType.WRITE, ITEM_X),
                new Operation(t2, OperationType.READ, ITEM_X)
            );
            ScheduleVerifier verifier = new ScheduleVerifier(history);

            // Act
            List<Transaction> serialOrder = verifier.getEquivalentSerialSchedule();

            // Assert
            assertEquals(List.of(t1, t2), serialOrder);
        }

        @Test
        @DisplayName("Non-data operations (COMMIT, ABORT) are ignored during conflict detection")
        void testBuildPrecedenceGraph_NonDataOperations_ShouldBeIgnored() {
            // Arrange
            List<Operation> history = List.of(
                new Operation(t1, OperationType.READ, ITEM_X),
                new Operation(t1, OperationType.WRITE, ITEM_X),
                new Operation(t1, OperationType.COMMIT),
                new Operation(t2, OperationType.READ, ITEM_X),
                new Operation(t2, OperationType.ABORT)
            );
            ScheduleVerifier verifier = new ScheduleVerifier(history);

            // Act
            List<Transaction> serialOrder = verifier.getEquivalentSerialSchedule();

            // Assert
            assertEquals(List.of(t1, t2), serialOrder);
        }
    }

    @Nested
    @DisplayName("Conflict Detection Rules")
    class ConflictDetectionTests {

        @Test
        @DisplayName("Read-Read on same item is NOT a conflict: R1(X), R2(X)")
        void testBuildPrecedenceGraph_ReadRead_ShouldNotProduceEdge() {
            // Arrange
            List<Operation> history = List.of(
                new Operation(t1, OperationType.READ, ITEM_X),
                new Operation(t2, OperationType.READ, ITEM_X)
            );
            ScheduleVerifier verifier = new ScheduleVerifier(history);

            // Act
            PrecedenceGraph graph = verifier.buildPrecedenceGraph();

            // Assert: Nodes exist but no directed edges between T1 and T2
            assertTrue(graph.getEdges().isEmpty());
        }

        @Test
        @DisplayName("Read-Write conflict produces edge: R1(X) then W2(X) -> T1 -> T2")
        void testBuildPrecedenceGraph_ReadWriteConflict_ShouldProduceEdge() {
            // Arrange
            List<Operation> history = List.of(
                new Operation(t1, OperationType.READ, ITEM_X),
                new Operation(t2, OperationType.WRITE, ITEM_X)
            );
            ScheduleVerifier verifier = new ScheduleVerifier(history);

            // Act
            PrecedenceGraph graph = verifier.buildPrecedenceGraph();

            // Assert
            assertEquals(List.of(t2), graph.getEdges().get(t1));
        }

        @Test
        @DisplayName("Write-Read conflict produces edge: W1(X) then R2(X) -> T1 -> T2")
        void testBuildPrecedenceGraph_WriteReadConflict_ShouldProduceEdge() {
            // Arrange
            List<Operation> history = List.of(
                new Operation(t1, OperationType.WRITE, ITEM_X),
                new Operation(t2, OperationType.READ, ITEM_X)
            );
            ScheduleVerifier verifier = new ScheduleVerifier(history);

            // Act
            PrecedenceGraph graph = verifier.buildPrecedenceGraph();

            // Assert
            assertEquals(List.of(t2), graph.getEdges().get(t1));
        }

        @Test
        @DisplayName("Write-Write conflict produces edge: W1(X) then W2(X) -> T1 -> T2")
        void testBuildPrecedenceGraph_WriteWriteConflict_ShouldProduceEdge() {
            // Arrange
            List<Operation> history = List.of(
                new Operation(t1, OperationType.WRITE, ITEM_X),
                new Operation(t2, OperationType.WRITE, ITEM_X)
            );
            ScheduleVerifier verifier = new ScheduleVerifier(history);

            // Act
            PrecedenceGraph graph = verifier.buildPrecedenceGraph();

            // Assert
            assertEquals(List.of(t2), graph.getEdges().get(t1));
        }

        @Test
        @DisplayName("Same transaction multiple operations do not create self-loops: R1(X), W1(X)")
        void testBuildPrecedenceGraph_SameTransactionOperations_ShouldNotProduceSelfLoop() {
            // Arrange
            List<Operation> history = List.of(
                new Operation(t1, OperationType.READ, ITEM_X),
                new Operation(t1, OperationType.WRITE, ITEM_X)
            );
            ScheduleVerifier verifier = new ScheduleVerifier(history);

            // Act
            PrecedenceGraph graph = verifier.buildPrecedenceGraph();

            // Assert
            assertTrue(graph.getEdges().isEmpty());
        }

        @Test
        @DisplayName("Multiple conflicts between same pair create only one edge: R1(X), W2(X), W1(Y), W2(Y)")
        void testBuildPrecedenceGraph_MultipleConflictsSamePair_ShouldOnlyAddSingleEdge() {
            // Arrange
            List<Operation> history = List.of(
                new Operation(t1, OperationType.READ, ITEM_X),
                new Operation(t2, OperationType.WRITE, ITEM_X),
                new Operation(t1, OperationType.WRITE, ITEM_Y),
                new Operation(t2, OperationType.WRITE, ITEM_Y)
            );
            ScheduleVerifier verifier = new ScheduleVerifier(history);

            // Act
            PrecedenceGraph graph = verifier.buildPrecedenceGraph();

            // Assert
            assertEquals(List.of(t2), graph.getEdges().get(t1));
            assertEquals(1, graph.getEdges().get(t1).size());
        }

        @Test
        @DisplayName("Operations on different data items do not create conflicts: W1(X), W2(Y)")
        void testBuildPrecedenceGraph_OperationsOnDifferentItems_ShouldNotConflict() {
            // Arrange
            List<Operation> history = List.of(
                new Operation(t1, OperationType.WRITE, ITEM_X),
                new Operation(t2, OperationType.WRITE, ITEM_Y)
            );
            ScheduleVerifier verifier = new ScheduleVerifier(history);

            // Act
            PrecedenceGraph graph = verifier.buildPrecedenceGraph();

            // Assert
            assertTrue(graph.getEdges().isEmpty());
        }
    }

    @Nested
    @DisplayName("Complex Multi-Transaction Schedules & Serializability")
    class MultiTransactionSchedules {

        @Test
        @DisplayName("Direct 2-transaction cycle returns isSerializable false")
        void testIsSerializable_TwoTransactionsCycle_ShouldReturnFalse() {
            // Arrange
            // R1(X) then W2(X) -> T1 -> T2
            // W2(X) then W1(X) -> T2 -> T1
            List<Operation> history = List.of(
                new Operation(t1, OperationType.READ, ITEM_X),
                new Operation(t2, OperationType.WRITE, ITEM_X),
                new Operation(t1, OperationType.WRITE, ITEM_X)
            );
            ScheduleVerifier verifier = new ScheduleVerifier(history);

            // Act & Assert
            assertFalse(verifier.isSerializable());
        }

        @Test
        @DisplayName("Direct 2-transaction cycle returns null equivalent serial schedule")
        void testGetEquivalentSerialSchedule_TwoTransactionsCycle_ShouldReturnNull() {
            // Arrange
            List<Operation> history = List.of(
                new Operation(t1, OperationType.READ, ITEM_X),
                new Operation(t2, OperationType.WRITE, ITEM_X),
                new Operation(t1, OperationType.WRITE, ITEM_X)
            );
            ScheduleVerifier verifier = new ScheduleVerifier(history);

            // Act & Assert
            assertNull(verifier.getEquivalentSerialSchedule());
        }

        @Test
        @DisplayName("3-transaction cycle across different items detects non-serializable schedule")
        void testIsSerializable_ThreeTransactionsDifferentItemsCycle_ShouldReturnFalse() {
            // Arrange:
            // X: W1(X) then R2(X) -> T1 -> T2
            // Y: W2(Y) then R3(Y) -> T2 -> T3
            // Z: W3(Z) then R1(Z) -> T3 -> T1
            List<Operation> history = List.of(
                new Operation(t1, OperationType.WRITE, ITEM_X),
                new Operation(t2, OperationType.READ, ITEM_X),
                new Operation(t2, OperationType.WRITE, ITEM_Y),
                new Operation(t3, OperationType.READ, ITEM_Y),
                new Operation(t3, OperationType.WRITE, ITEM_Z),
                new Operation(t1, OperationType.READ, ITEM_Z)
            );
            ScheduleVerifier verifier = new ScheduleVerifier(history);

            // Act & Assert
            assertFalse(verifier.isSerializable());
            assertNull(verifier.getEquivalentSerialSchedule());
        }

        @Test
        @DisplayName("4-transaction serializable pipeline produces correct topological order")
        void testGetEquivalentSerialSchedule_FourTransactionsPipeline_ShouldReturnValidOrder() {
            // Arrange
            List<Operation> history = List.of(
                new Operation(t1, OperationType.WRITE, ITEM_X),
                new Operation(t2, OperationType.READ, ITEM_X),
                new Operation(t2, OperationType.WRITE, ITEM_Y),
                new Operation(t3, OperationType.READ, ITEM_Y),
                new Operation(t3, OperationType.WRITE, ITEM_Z),
                new Operation(t4, OperationType.READ, ITEM_Z)
            );
            ScheduleVerifier verifier = new ScheduleVerifier(history);

            // Act
            List<Transaction> serialOrder = verifier.getEquivalentSerialSchedule();

            // Assert
            assertTrue(verifier.isSerializable());
            assertEquals(List.of(t1, t2, t3, t4), serialOrder);
        }

        @Test
        @DisplayName("CCM verifySchedule returns true for serializable schedule")
        void testVerifySchedule_SerializableSchedule_ShouldReturnTrue() {
            // Arrange
            ConcurrencyControlManager ccm = new ConcurrencyControlManager();
            List<Operation> serializableSchedule = List.of(
                new Operation(t1, OperationType.WRITE, ITEM_X),
                new Operation(t2, OperationType.READ, ITEM_X)
            );

            // Act & Assert
            assertTrue(ccm.verifySchedule(serializableSchedule));
        }

        @Test
        @DisplayName("CCM verifySchedule returns false for cyclic schedule")
        void testVerifySchedule_CyclicSchedule_ShouldReturnFalse() {
            // Arrange
            ConcurrencyControlManager ccm = new ConcurrencyControlManager();
            List<Operation> cyclicSchedule = List.of(
                new Operation(t1, OperationType.READ, ITEM_X),
                new Operation(t2, OperationType.WRITE, ITEM_X),
                new Operation(t1, OperationType.WRITE, ITEM_X)
            );

            // Act & Assert
            assertFalse(ccm.verifySchedule(cyclicSchedule));
        }
    }
}
