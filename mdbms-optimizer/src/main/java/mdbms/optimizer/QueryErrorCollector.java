package mdbms.optimizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class QueryErrorCollector {
    private final List<QueryProcessingException> exceptions = new ArrayList<>();

    public void addException(QueryProcessingException exception) {
        if (exception != null) {
            exceptions.add(exception);
        }
    }

    public boolean hasError() {
        return !exceptions.isEmpty();
    }

    public boolean hasFatalError() {
        for (QueryProcessingException exception : exceptions) {
            if (exception.getCategory() == ErrorCategory.LEXICAL_ERROR
                || exception.getCategory() == ErrorCategory.SYNTAX_ERROR) {
                return true;
            }
        }
        return false;
    }

    public List<QueryProcessingException> getAllExceptions() {
        return Collections.unmodifiableList(exceptions);
    }

    public void printAllExpections(){
        if (hasError()) {
            for (QueryProcessingException e : exceptions) {
                System.err.println(e.getFormattedError());
            }
        }
    }
}
