package mdbms.optimizer;

import java.util.List;

import mdbms.api.ISchemaCatalog;
import mdbms.api.IStatisticsProvider;
import mdbms.models.IndexInfo;
import mdbms.models.Statistic;

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
        // TODO
        return null;
    }

    public List<IndexInfo> getIndexes(String table) {
        // TODO
        return List.of();
    }
}
