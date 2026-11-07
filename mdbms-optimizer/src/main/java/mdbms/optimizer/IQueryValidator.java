package mdbms.optimizer;

import mdbms.models.query.QueryTree;

public interface IQueryValidator {
    boolean validate(QueryTree tree);
}
