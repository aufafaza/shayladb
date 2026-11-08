package mdbms.optimizer.rule;

import java.util.List;

import mdbms.api.ISchemaCatalog;
import mdbms.api.IStatisticsProvider;
import mdbms.models.schema.IndexInfo;
import mdbms.models.schema.Statistic;
import mdbms.optimizer.binder.BindingContext;
import mdbms.optimizer.cost.ICostEstimator;

public class OptimizationContext {
    private final BindingContext bindingContext;
    private final ISchemaCatalog schemaCatalog;
    private final IStatisticsProvider statisticsProvider;
    private final ICostEstimator costEstimator;

    public OptimizationContext(
        BindingContext bindingContext,
        ISchemaCatalog schemaCatalog,
        IStatisticsProvider statisticsProvider,
        ICostEstimator costEstimator
    ) {
        this.bindingContext = bindingContext;
        this.schemaCatalog = schemaCatalog;
        this.statisticsProvider = statisticsProvider;
        this.costEstimator = costEstimator;
    }

    public BindingContext getBindingContext() {
        return bindingContext;
    }

    public Statistic getStatistics(String table) {
        return (statisticsProvider != null && table != null) ? statisticsProvider.getStatistics(table) : null;
    }

    public List<IndexInfo> getIndexes(String table) {
        return (schemaCatalog != null && table != null) ? schemaCatalog.getIndexes(table) : List.of();
    }

    public ICostEstimator getCostEstimator() {
        return costEstimator;
    }
}
