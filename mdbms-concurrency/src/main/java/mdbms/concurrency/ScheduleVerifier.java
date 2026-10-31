package mdbms.concurrency;

import mdbms.models.Transaction;
import mdbms.models.Operation;
import java.util.List;

public class ScheduleVerifier {
    private List<Operation> history;

    public PrecedenceGraph buildPrecedenceGraph() {
        // TODO
        return  null;
    }

    public boolean isSerializable() {
        // TODO
        return  true;
    }

    public List<Transaction> getEquivalentSerialSchedule() {
        // TODO
        return null;
    }
}
