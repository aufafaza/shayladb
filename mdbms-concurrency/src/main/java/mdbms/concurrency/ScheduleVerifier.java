package mdbms.concurrency;

import mdbms.models.transaction.Operation;
import mdbms.models.transaction.OperationType;
import mdbms.models.transaction.Transaction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ScheduleVerifier {
    private List<Operation> history;

    public ScheduleVerifier() {
        this.history = new ArrayList<>();
    }

    public ScheduleVerifier(List<Operation> history) {
        this.history = history != null ? new ArrayList<>(history) : new ArrayList<>();
    }

    public List<Operation> getHistory() {
        return Collections.unmodifiableList(history);
    }

    public void setHistory(List<Operation> history) {
        this.history = history != null ? new ArrayList<>(history) : new ArrayList<>();
    }

    public void addOperation(Operation operation) {
        if (operation != null) {
            this.history.add(operation);
        }
    }

    public PrecedenceGraph buildPrecedenceGraph() {
        PrecedenceGraph graph = new PrecedenceGraph();
        if (history == null || history.isEmpty()) {
            return graph;
        }

        for (Operation op : history) {
            if (op != null && op.getTransaction() != null) {
                graph.addNode(op.getTransaction());
            }
        }

        int n = history.size();
        for (int i = 0; i < n; i++) {
            Operation op1 = history.get(i);
            if (!isDataOperation(op1)) {
                continue;
            }

            for (int j = i + 1; j < n; j++) {
                Operation op2 = history.get(j);
                if (!isDataOperation(op2)) {
                    continue;
                }

                if (isConflict(op1, op2)) {
                    graph.addEdge(op1.getTransaction(), op2.getTransaction());
                }
            }
        }

        return graph;
    }

    public boolean isSerializable() {
        PrecedenceGraph graph = buildPrecedenceGraph();
        return graph != null && !graph.hasCycle();
    }

    public List<Transaction> getEquivalentSerialSchedule() {
        PrecedenceGraph graph = buildPrecedenceGraph();
        if (graph == null || graph.hasCycle()) {
            return null;
        }
        return graph.topologicalSort();
    }

    private static boolean isDataOperation(Operation op) {
        return op != null
                && op.getTransaction() != null
                && op.getDataItem() != null
                && (op.getType() == OperationType.READ || op.getType() == OperationType.WRITE);
    }

    private static boolean isConflict(Operation op1, Operation op2) {
        if (op1.getTransaction().getId() == op2.getTransaction().getId()) {
            return false;
        }
        if (!op1.getDataItem().equals(op2.getDataItem())) {
            return false;
        }
        return op1.getType() == OperationType.WRITE || op2.getType() == OperationType.WRITE;
    }
}
