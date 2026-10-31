package mdbms.processor;

import mdbms.api.IConcurrencyControl;
import mdbms.api.IDataAccess;
import mdbms.api.IFailureRecoveryManager;
import mdbms.api.IQueryOptimizer;
import mdbms.api.ISchemaCatalog;
import mdbms.models.ParsedQuery;
import mdbms.processor.operator.IQueryOperator;

public class PlanTranslator {
    private IQueryOptimizer queryOptimizer;
    private IDataAccess dataAccess;
    private ISchemaCatalog schemaCatalog;
    private IFailureRecoveryManager failureRecovery;
    private IConcurrencyControl concurrencyControl;

    public PlanTranslator(
            IQueryOptimizer queryOptimizer,
            IDataAccess dataAccess,
            ISchemaCatalog schemaCatalog,
            IFailureRecoveryManager failureRecovery,
            IConcurrencyControl concurrencyControl) {
        this.queryOptimizer = queryOptimizer;
        this.dataAccess = dataAccess;
        this.schemaCatalog = schemaCatalog;
        this.failureRecovery = failureRecovery;
        this.concurrencyControl = concurrencyControl;
    }

    public IQueryOperator translate(ParsedQuery parsedQuery) {
        // TODO: translate this thing....
        return null;
    }

}
