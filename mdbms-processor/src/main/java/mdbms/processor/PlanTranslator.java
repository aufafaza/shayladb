package mdbms.processor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

import mdbms.api.IConcurrencyControl;
import mdbms.api.IDataAccess;
import mdbms.api.IFailureRecoveryManager;
import mdbms.api.IQueryOptimizer;
import mdbms.api.ISchemaCatalog;
import mdbms.models.query.ParsedQuery;
import mdbms.models.query.ColumnReference;
import mdbms.models.query.QueryTree;
import mdbms.models.query.SortDirection;
import mdbms.models.schema.IndexInfo;
import mdbms.models.schema.IndexType;
import mdbms.processor.operator.AbortOperator;
import mdbms.processor.operator.BeginOperator;
import mdbms.processor.operator.CartesianJoinOperator;
import mdbms.processor.operator.CommitOperator;
import mdbms.processor.operator.CreateIndexOperator;
import mdbms.processor.operator.DeleteOperator;
import mdbms.processor.operator.GroupByOperator;
import mdbms.processor.operator.IQueryOperator;
import mdbms.processor.operator.IndexScanOperator;
import mdbms.processor.operator.InsertOperator;
import mdbms.processor.operator.JoinOperator;
import mdbms.processor.operator.LimitOperator;
import mdbms.processor.operator.ProjectOperator;
import mdbms.processor.operator.SelectOperator;
import mdbms.processor.operator.SequentialScanOperator;
import mdbms.processor.operator.SortOperator;
import mdbms.processor.operator.UpdateOperator;

public class PlanTranslator {
    private final int JOIN_BLOCK_SIZE = 10;

    private IDataAccess dataAccess;
    private ISchemaCatalog schemaCatalog;
    private IConcurrencyControl concurrencyControl;

    public PlanTranslator(
            IDataAccess dataAccess,
            ISchemaCatalog schemaCatalog,
            IConcurrencyControl concurrencyControl) {
        this.dataAccess = dataAccess;
        this.schemaCatalog = schemaCatalog;
        this.concurrencyControl = concurrencyControl;
    }

    public IQueryOperator translate(ParsedQuery parsedQuery, int txId) {
        return translateRecursive(parsedQuery.getQueryTree(), txId);
    }

    private IQueryOperator translateRecursive(QueryTree queryTree, int txId) {
        List<IQueryOperator> childOperators = new ArrayList<>();
        for (QueryTree childQuery : queryTree.getChildren()) {
            childOperators.add(translateRecursive(childQuery, txId));
        }

        switch (queryTree.getNodeType()) {
            case TABLE_SCAN:
                switch (queryTree.getAlgorithm()) {
                    case DEFAULT:
                    case LINEAR_SCAN:
                        return new SequentialScanOperator(dataAccess, schemaCatalog, concurrencyControl, txId,
                                queryTree.getTableName());
                    case INDEX_SCAN:
                        return new IndexScanOperator(dataAccess, schemaCatalog, concurrencyControl, txId,
                                queryTree.getTableName(),
                                queryTree.getIndexName(), null);
                    // TODO: figure out what should be the key
                    // I think some how operator lain harus ngasih keynya dari condition...
                    default:
                        throw new RuntimeException("Invalid algorithm for table scan: " + queryTree.getAlgorithm());
                }
            case PROJECT:
                return new ProjectOperator(childOperators.get(0), queryTree.getProjectionColumns().stream()
                        .map(ColumnReference::toString).toList());
            case SELECT:
                return new SelectOperator(childOperators.get(0), queryTree.getConditions());
            case LIMIT:
                return new LimitOperator(childOperators.get(0), queryTree.getLimit());
            case CARTESIAN:
                return new CartesianJoinOperator(childOperators.get(0), childOperators.get(1));
            case JOIN:
                return new JoinOperator(childOperators.get(0), childOperators.get(1), queryTree.getConditions(),
                        queryTree.isNaturalJoin(), JOIN_BLOCK_SIZE);
            case SORT:
                return new SortOperator(childOperators.get(0),
                        queryTree.getOrderByClauses().stream()
                                .map((clause) -> new SortOperator.SortKey(clause.getField().getTableName(),
                                        clause.getField().getColumnName(),
                                        clause.getDirection() == SortDirection.ASC)).toList());
            case GROUP_BY:
                // return new GroupByOperator(childOperators.get(0), queryTree.getValue(),
                // false);
                return null;
            case INSERT:
                return new InsertOperator(dataAccess, schemaCatalog, concurrencyControl, txId,
                        queryTree.getTableName(), queryTree.getInsertValues());
            case UPDATE:
                return new UpdateOperator(targetRows(queryTree, txId), dataAccess, schemaCatalog, concurrencyControl,
                        txId, queryTree.getTableName(), queryTree.getUpdateColumn().getColumnName(),
                        queryTree.getUpdateExpression());
            case DELETE:
                return new DeleteOperator(targetRows(queryTree, txId), dataAccess, concurrencyControl, txId,
                        queryTree.getTableName());
            case CREATE_INDEX:
                return new CreateIndexOperator(schemaCatalog, new IndexInfo(queryTree.getIndexName(),
                        queryTree.getTableName(), queryTree.getIndexColumn().getColumnName(), IndexType.BPLUS_TREE));
            case BEGIN:
                return new BeginOperator(txId);
            case COMMIT:
                return new CommitOperator(concurrencyControl, txId);
            case ABORT:
                return new AbortOperator(concurrencyControl, txId);
            default:
                return emptyOperator();
        }
    }

    private IQueryOperator targetRows(QueryTree queryTree, int txId) {
        return new SelectOperator(
                new SequentialScanOperator(dataAccess, schemaCatalog, concurrencyControl, txId, queryTree.getTableName()),
                queryTree.getConditions());
    }

    private static IQueryOperator emptyOperator() {
        return new IQueryOperator() {
            public void open() {
            }

            public IResultRow next() {
                return null;
            }

            public void close() {
            }
        };
    }
}
