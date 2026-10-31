package mdbms.optimizer;

import mdbms.models.QueryTree;

public interface IQueryValidator {
    boolean validate(QueryTree tree);
}
