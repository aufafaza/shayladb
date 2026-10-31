package mdbms.optimizer;

import java.util.List;

public class QueryErrorCollector {
    public void addException(QueryProcessingException exception) {
        // TODO
    }

    public boolean hasError() {
        // TODO
        return false;
    }

    public boolean hasFatalError() {
        // TODO
        return false;
    }

    public List<QueryProcessingException> getAllExceptions() {
        // TODO
        return List.of();
    }
}
