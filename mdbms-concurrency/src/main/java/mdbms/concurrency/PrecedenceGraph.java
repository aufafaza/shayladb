package mdbms.concurrency;

import mdbms.models.transaction.Transaction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;

public class PrecedenceGraph {
    private final Set<Transaction> nodes;
    private final Map<Transaction, List<Transaction>> edges;

    public PrecedenceGraph() {
        nodes = new HashSet<>();
        edges = new HashMap<>();
    }

    public void addNode(Transaction tx) {
        if (tx != null) {
            nodes.add(tx);
        }
    }

    public void addEdge(Transaction tx1, Transaction tx2) {
        if (tx1 == null || tx2 == null) {
            return;
        }
        if (tx1.equals(tx2) || tx1.getId() == tx2.getId()) {
            return;
        }
        nodes.add(tx1);
        nodes.add(tx2);
        List<Transaction> neighbors = edges.computeIfAbsent(tx1, ignored -> new ArrayList<>());
        if (!neighbors.contains(tx2)) {
            neighbors.add(tx2);
        }
    }

    public boolean hasCycle() {
        Set<Transaction> visited = new HashSet<>();
        Set<Transaction> active = new HashSet<>();
        for (Transaction node : nodes) {
            if (!visited.contains(node)) {
                if (hasCycle(node, visited, active)) {
                    return true;
                }
            }
        }
        return false;
    }

    public Set<Transaction> getNodes() {
        return new HashSet<>(nodes);
    }

    public Map<Transaction, List<Transaction>> getEdges() {
        Map<Transaction, List<Transaction>> copy = new HashMap<>();
        for (Map.Entry<Transaction, List<Transaction>> entry : edges.entrySet()) {
            copy.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(copy);
    }

    public Set<Transaction> getCycleNodes() {
        Set<Transaction> cycleNodes = new HashSet<>();
        for (Transaction node : nodes) {
            if (canReach(node, node, new HashSet<>())) {
                cycleNodes.add(node);
            }
        }
        return cycleNodes;
    }

    public List<Transaction> topologicalSort() {
        if (hasCycle()) {
            return null;
        }

        Map<Transaction, Integer> inDegree = new HashMap<>();
        for (Transaction node : nodes) {
            inDegree.put(node, 0);
        }
        for (List<Transaction> neighborList : edges.values()) {
            for (Transaction neighbor : neighborList) {
                inDegree.put(neighbor, inDegree.getOrDefault(neighbor, 0) + 1);
            }
        }

        Queue<Transaction> queue = new PriorityQueue<>(Comparator.comparingInt(Transaction::getId));
        for (Transaction node : nodes) {
            if (inDegree.get(node) == 0) {
                queue.add(node);
            }
        }

        List<Transaction> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            Transaction current = queue.poll();
            result.add(current);
            for (Transaction neighbor : edges.getOrDefault(current, List.of())) {
                int deg = inDegree.get(neighbor) - 1;
                inDegree.put(neighbor, deg);
                if (deg == 0) {
                    queue.add(neighbor);
                }
            }
        }

        if (result.size() != nodes.size()) {
            return null;
        }
        return result;
    }

    private boolean hasCycle(Transaction node, Set<Transaction> visited, Set<Transaction> active) {
        if (active.contains(node)) {
            return true;
        }
        if (!visited.add(node)) {
            return false;
        }
        active.add(node);
        for (Transaction next : edges.getOrDefault(node, List.of())) {
            if (hasCycle(next, visited, active)) {
                return true;
            }
        }
        active.remove(node);
        return false;
    }

    private boolean canReach(Transaction current, Transaction target, Set<Transaction> visited) {
        for (Transaction next : edges.getOrDefault(current, List.of())) {
            if (next.equals(target) || next.getId() == target.getId()) {
                return true;
            }
            if (visited.add(next) && canReach(next, target, visited)) {
                return true;
            }
        }
        return false;
    }
}
