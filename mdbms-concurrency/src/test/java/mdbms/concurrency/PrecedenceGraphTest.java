package mdbms.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import mdbms.models.transaction.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;


class PrecedenceGraphTest {

    private PrecedenceGraph graph;
    private Transaction t1;
    private Transaction t2;
    private Transaction t3;
    private Transaction t4;

    @BeforeEach
    void setUp() {
        graph = new PrecedenceGraph();
        t1 = new Transaction(1, 100L);
        t2 = new Transaction(2, 200L);
        t3 = new Transaction(3, 300L);
        t4 = new Transaction(4, 400L);
    }

    @Nested
    @DisplayName("Graph Construction & Edge Cases")
    class GraphConstructionTests {

        @Test
        @DisplayName("addNode ignores null node")
        void testAddNode_WithNull_ShouldDoNothing() {
            // Arrange (graph initialized)

            // Act
            graph.addNode(null);

            // Assert
            assertTrue(graph.getNodes().isEmpty());
        }

        @Test
        @DisplayName("addNode adds isolated node without edges")
        void testAddNode_IsolatedNode_ShouldBeIncludedInNodes() {
            // Arrange & Act
            graph.addNode(t1);

            // Assert
            assertEquals(Set.of(t1), graph.getNodes());
        }

        @Test
        @DisplayName("Isolated node is sorted successfully in topological sort")
        void testTopologicalSort_IsolatedNode_ShouldBeIncludedInSort() {
            // Arrange
            graph.addNode(t1);

            // Act
            List<Transaction> sort = graph.topologicalSort();

            // Assert
            assertEquals(List.of(t1), sort);
        }

        @Test
        @DisplayName("addEdge ignores null source transaction")
        void testAddEdge_WithNullSource_ShouldDoNothing() {
            // Arrange & Act
            graph.addEdge(null, t1);

            // Assert
            assertTrue(graph.getNodes().isEmpty());
            assertTrue(graph.getEdges().isEmpty());
        }

        @Test
        @DisplayName("addEdge ignores null target transaction")
        void testAddEdge_WithNullTarget_ShouldDoNothing() {
            // Arrange & Act
            graph.addEdge(t1, null);

            // Assert
            assertTrue(graph.getNodes().isEmpty());
            assertTrue(graph.getEdges().isEmpty());
        }

        @Test
        @DisplayName("addEdge ignores self-loops on same instance")
        void testAddEdge_SelfLoop_ShouldBeIgnored() {
            // Arrange & Act
            graph.addEdge(t1, t1);

            // Assert
            assertTrue(graph.getEdges().isEmpty());
        }

        @Test
        @DisplayName("addEdge ignores self-loops with same tx ID on different instances")
        void testAddEdge_SameTxIdDifferentInstances_ShouldBeIgnored() {
            // Arrange
            Transaction t1Clone = new Transaction(1, 999L);

            // Act
            graph.addEdge(t1, t1Clone);

            // Assert
            assertTrue(graph.getEdges().isEmpty());
        }

        @Test
        @DisplayName("addEdge ignores duplicate edges between same nodes")
        void testAddEdge_DuplicateEdges_ShouldOnlyStoreSingleEdge() {
            // Arrange & Act
            graph.addEdge(t1, t2);
            graph.addEdge(t1, t2);
            graph.addEdge(t1, t2);

            // Assert
            assertEquals(List.of(t2), graph.getEdges().get(t1));
        }

        @Test
        @DisplayName("getNodes returns a defensive copy preventing external mutations")
        void testGetNodes_ExternalMutation_ShouldNotAffectGraph() {
            // Arrange
            graph.addNode(t1);

            // Act
            Set<Transaction> nodesCopy = graph.getNodes();
            nodesCopy.add(t2);

            // Assert
            assertEquals(1, graph.getNodes().size());
            assertFalse(graph.getNodes().contains(t2));
        }

        @Test
        @DisplayName("getEdges returns an unmodifiable map preventing external mutations")
        void testGetEdges_ExternalMutation_ShouldThrowUnsupportedOperationException() {
            // Arrange
            graph.addEdge(t1, t2);

            // Act & Assert
            assertThrows(UnsupportedOperationException.class, () -> graph.getEdges().put(t3, List.of()));
        }
    }

    @Nested
    @DisplayName("Cycle Detection & Topological Sort Tests")
    class CycleAndSortTests {

        @Test
        @DisplayName("Empty graph returns hasCycle false")
        void testHasCycle_EmptyGraph_ShouldReturnFalse() {
            // Arrange (graph is empty)

            // Act & Assert
            assertFalse(graph.hasCycle());
        }

        @Test
        @DisplayName("Empty graph returns empty topological sort")
        void testTopologicalSort_EmptyGraph_ShouldReturnEmptyList() {
            // Arrange (graph is empty)

            // Act & Assert
            assertNotNull(graph.topologicalSort());
            assertTrue(graph.topologicalSort().isEmpty());
        }

        @Test
        @DisplayName("Empty graph returns empty cycle nodes")
        void testGetCycleNodes_EmptyGraph_ShouldReturnEmptySet() {
            // Arrange (graph is empty)

            // Act & Assert
            assertTrue(graph.getCycleNodes().isEmpty());
        }

        @Test
        @DisplayName("Linear DAG (T1 -> T2 -> T3) returns valid topological sort")
        void testTopologicalSort_LinearDAG_ShouldReturnValidOrder() {
            // Arrange
            graph.addEdge(t1, t2);
            graph.addEdge(t2, t3);

            // Act
            List<Transaction> sort = graph.topologicalSort();

            // Assert
            assertEquals(List.of(t1, t2, t3), sort);
        }

        @Test
        @DisplayName("Diamond DAG (T1 -> T2 -> T4 and T1 -> T3 -> T4) is NOT cyclic")
        void testHasCycle_DiamondDAG_ShouldReturnFalse() {
            // Arrange: Diamond structure
            //      T1
            //     /  \
            //    T2   T3
            //     \  /
            //      T4
            graph.addEdge(t1, t2);
            graph.addEdge(t1, t3);
            graph.addEdge(t2, t4);
            graph.addEdge(t3, t4);

            // Act
            boolean hasCycle = graph.hasCycle();

            // Assert
            assertFalse(hasCycle, "Diamond DAG must not be reported as cyclic");
        }

        @Test
        @DisplayName("Diamond DAG produces valid topological sort")
        void testTopologicalSort_DiamondDAG_ShouldProduceValidSort() {
            // Arrange
            graph.addEdge(t1, t2);
            graph.addEdge(t1, t3);
            graph.addEdge(t2, t4);
            graph.addEdge(t3, t4);

            // Act
            List<Transaction> sort = graph.topologicalSort();

            // Assert
            assertNotNull(sort);
            assertEquals(4, sort.size());
            assertEquals(t1, sort.get(0)); // T1 must come first
            assertEquals(t4, sort.get(3)); // T4 must come last
        }

        @Test
        @DisplayName("Disconnected components are sorted deterministically by transaction ID")
        void testTopologicalSort_DisconnectedComponents_ShouldSortDeterministically() {
            // Arrange: Component 1 (T1 -> T2), Component 2 (T3 -> T4)
            graph.addEdge(t1, t2);
            graph.addEdge(t3, t4);

            // Act
            List<Transaction> sort = graph.topologicalSort();

            // Assert
            // In-degree 0 initially: T1, T3. T1 polled first, freeing T2 (ID=2).
            // T2 (ID=2) has priority over T3 (ID=3), so deterministic order is T1 -> T2 -> T3 -> T4.
            assertEquals(List.of(t1, t2, t3, t4), sort);
        }

        @Test
        @DisplayName("Direct 2-node cycle (T1 -> T2 -> T1) returns hasCycle true")
        void testHasCycle_TwoNodesCycle_ShouldReturnTrue() {
            // Arrange
            graph.addEdge(t1, t2);
            graph.addEdge(t2, t1); // Cycle

            // Act & Assert
            assertTrue(graph.hasCycle());
        }

        @Test
        @DisplayName("Direct 2-node cycle returns null topological sort")
        void testTopologicalSort_TwoNodesCycle_ShouldReturnNull() {
            // Arrange
            graph.addEdge(t1, t2);
            graph.addEdge(t2, t1); // Cycle

            // Act & Assert
            assertNull(graph.topologicalSort());
        }

        @Test
        @DisplayName("Direct 2-node cycle identifies participating nodes in getCycleNodes")
        void testGetCycleNodes_TwoNodesCycle_ShouldReturnCycleMembers() {
            // Arrange
            graph.addEdge(t1, t2);
            graph.addEdge(t2, t1);

            // Act & Assert
            assertEquals(Set.of(t1, t2), graph.getCycleNodes());
        }

        @Test
        @DisplayName("3-node cycle (T1 -> T2 -> T3 -> T1) detects all cycle members")
        void testGetCycleNodes_ThreeNodesCycle_ShouldDetectAllCycleMembers() {
            // Arrange
            graph.addEdge(t1, t2);
            graph.addEdge(t2, t3);
            graph.addEdge(t3, t1);

            // Act & Assert
            assertTrue(graph.hasCycle());
            assertEquals(Set.of(t1, t2, t3), graph.getCycleNodes());
        }

        @Test
        @DisplayName("Partial cycle with an acyclic incoming node only reports nodes in cycle")
        void testGetCycleNodes_PartialCycleWithIncomingNode_ShouldOnlyReportCycleNodes() {
            // Arrange: T1 points to T2, but T1 is not part of the cycle (T2 <-> T3)
            graph.addEdge(t1, t2);
            graph.addEdge(t2, t3);
            graph.addEdge(t3, t2);

            // Act
            Set<Transaction> cycleNodes = graph.getCycleNodes();

            // Assert
            assertTrue(cycleNodes.contains(t2));
            assertTrue(cycleNodes.contains(t3));
            assertFalse(cycleNodes.contains(t1), "T1 is not in the cycle and must not be in cycleNodes");
        }
    }
}
