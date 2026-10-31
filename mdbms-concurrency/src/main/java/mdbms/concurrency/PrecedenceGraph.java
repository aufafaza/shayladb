package mdbms.concurrency;

import  java.util.Set;
import  java.util.Map;
import  java.util.List;

public class PrecedenceGraph {
    private Set<Transaction> nodes;
    private Map<Transaction, List<Transaction>> edges;

    void addEdge(Transaction tx1, Transaction tx2) {
        // TODO
    }

    boolean hasCycle() {
        // TODO
        return true;
    }
}
