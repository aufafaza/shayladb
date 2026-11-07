package mdbms.optimizer.binder;

import mdbms.models.query.QueryTree;

public interface IQueryValidator {
    boolean validate(QueryTree tree);
}
