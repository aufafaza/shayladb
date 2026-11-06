package mdbms.concurrency;

import mdbms.models.transaction.Transaction;

import  java.util.Set;
import  java.util.Map;
import  java.util.HashMap;
import  java.util.HashSet;
import  java.util.List;
import  java.util.ArrayList;

public class PrecedenceGraph {
    private Set<Transaction> nodes;
    private Map<Transaction, List<Transaction>> edges;

    public PrecedenceGraph() {
        nodes = new HashSet<>();
        edges = new HashMap<>();
    }

    void addEdge(Transaction tx1, Transaction tx2) {
        nodes.add(tx1);
        nodes.add(tx2);
        edges.computeIfAbsent(tx1, ignored -> new ArrayList<>()).add(tx2);
    }

    boolean hasCycle() {
        Set<Transaction> visited = new HashSet<>();
        Set<Transaction> active = new HashSet<>();
        for (Transaction node : nodes) {
            if (hasCycle(node, visited, active)) return true;
        }
        return false;
    }

    Set<Transaction> getNodes() {
        return new HashSet<>(nodes);
    }

    Set<Transaction> getCycleNodes() {
        Set<Transaction> cycleNodes = new HashSet<>();
        for (Transaction node : nodes) {
            if (canReach(node, node, new HashSet<>())) cycleNodes.add(node);
        }
        return cycleNodes;
    }

    private boolean hasCycle(Transaction node, Set<Transaction> visited, Set<Transaction> active) {
        if (active.contains(node)) return true;
        if (!visited.add(node)) return false;
        active.add(node);
        for (Transaction next : edges.getOrDefault(node, List.of())) {
            if (hasCycle(next, visited, active)) return true;
        }
        active.remove(node);
        return false;
    }

    private boolean canReach(
            Transaction current,
            Transaction target,
            Set<Transaction> visited
    ) {
        for (Transaction next : edges.getOrDefault(current, List.of())) {
            if (next == target) return true;
            if (visited.add(next) && canReach(next, target, visited)) return true;
        }
        return false;
    }
}
