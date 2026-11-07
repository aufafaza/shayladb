package mdbms.processor;

import java.util.ArrayList;
import java.util.List;

import mdbms.api.IConcurrencyControl;
import mdbms.api.IDataAccess;
import mdbms.api.IFailureRecoveryManager;
import mdbms.api.IQueryOptimizer;
import mdbms.api.ISchemaCatalog;
import mdbms.models.query.ParsedQuery;
import mdbms.models.query.QueryTree;
import mdbms.processor.operator.CartesianJoinOperator;
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

    public IQueryOperator translate(ParsedQuery parsedQuery) {
        return translateRecursive(parsedQuery.getQueryTree());
    }

    private IQueryOperator translateRecursive(QueryTree queryTree) {
        List<IQueryOperator> childOperators = new ArrayList<>();
        for (QueryTree childQuery : queryTree.getChildren()) {
            childOperators.add(translateRecursive(childQuery));
        }

        
        switch (queryTree.getNodeType()) {
            case TABLE_SCAN:
                switch (queryTree.getAlgorithm()) {
                    case LINEAR_SCAN:
                        return new SequentialScanOperator(dataAccess, schemaCatalog, queryTree.getValue());
                    case INDEX_SCAN:
                        // return new IndexScanOperator(dataAccess, schemaCatalog, queryTree.getValue(),
                        // indexName);
                        return null;
                    default:
                        throw new RuntimeException("Invalid algorithm for table scan: " + queryTree.getAlgorithm());
                }
            case PROJECT:
                List<String> projectedColumns = new ArrayList<>(List.of(queryTree.getValue().split(" ")));
                return new ProjectOperator(childOperators.get(0), projectedColumns);
            case SELECT:
                return new SelectOperator(childOperators.get(0), queryTree.getConditions());
            case LIMIT:
                return new LimitOperator(childOperators.get(0), Long.parseLong(queryTree.getValue()));
            case CARTESIAN:
                return new CartesianJoinOperator(childOperators.get(0), childOperators.get(1));
            case JOIN:
                // return new JoinOperator(childOperators.get(0), childOperators.get(1));
                return null;
            case SORT:
                return new SortOperator(childOperators.get(0), queryTree.getValue(), false);
            case GROUP_BY:
                // return new GroupByOperator(childOperators.get(0), queryTree.getValue(),
                // false);
                return null;
            case INSERT:
                return new InsertOperator(dataAccess, concurrencyControl, 0, null);
            case UPDATE:
                return new UpdateOperator(childOperators.get(0), dataAccess, schemaCatalog, concurrencyControl,
                        0, null, null, null);
            case DELETE:
                // return new DeleteOperator(dataAccess, schemaCatalog, queryTree.getValue(),
                // queryTree.getConditions());
                return null;
            default:
                return emptyOperator();
        }
    }

    private static IQueryOperator emptyOperator() {
        return new IQueryOperator() {
            public void open() {}
            public IResultRow next() { return null; }
            public void close() {}
        }
    }
}
