package mdbms.optimizer;

import mdbms.models.query.QueryTree;

public interface IQueryParser {
    QueryTree parseToTree(String sqlTree);
}
