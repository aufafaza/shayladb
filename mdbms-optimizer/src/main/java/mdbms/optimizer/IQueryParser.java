package mdbms.optimizer;

import mdbms.models.QueryTree;

public interface IQueryParser {
    QueryTree parseToTree(String sqlTree);
}
