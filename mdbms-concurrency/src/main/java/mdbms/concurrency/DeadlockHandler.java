package mdbms.concurrency;

import java.util.Map;

import mdbms.models.transaction.Transaction;

public class DeadlockHandler {
    private final LockManager lockManager;

    public DeadlockHandler() {
        this(new LockManager());
    }

    public DeadlockHandler(LockManager lockManager) {
        if (lockManager == null) throw new IllegalArgumentException("Lock manager is required");
        this.lockManager = lockManager;
    }

    boolean detectDeadlock() {
        return buildWaitForGraph().hasCycle();
    } 

    PrecedenceGraph buildWaitForGraph() {
        PrecedenceGraph graph = new PrecedenceGraph();
        Map<Transaction, LockManager.PendingLock> waiting = lockManager.getWaitingRequestsSnapshot();
        Map<mdbms.models.data.DataItem, java.util.ArrayList<Lock>> locks =
                lockManager.getLockTableSnapshot();

        waiting.forEach((requester, request) -> {
            java.util.ArrayList<Lock> holders = locks.get(request.getData());
            if (holders == null) return;
            for (Lock holder : holders) {
                if (holder.getTransaction() != requester
                        && (request.getType() == Locktype.EXCLUSIVE
                        || holder.getLocktype() == Locktype.EXCLUSIVE)) {
                    graph.addEdge(requester, holder.getTransaction());
                }
            }
        });
        return graph;
    } 
    
    Transaction chooseVictim() {
        PrecedenceGraph graph = buildWaitForGraph();
        Transaction victim = null;
        for (Transaction transaction : graph.getCycleNodes()) {
            if (victim == null
                    || transaction.getTimestamp() > victim.getTimestamp()
                    || (transaction.getTimestamp() == victim.getTimestamp()
                    && transaction.getId() > victim.getId())) {
                victim = transaction;
            }
        }
        return victim;
    } 

}