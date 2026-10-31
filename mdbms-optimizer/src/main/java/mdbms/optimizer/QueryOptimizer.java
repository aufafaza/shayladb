package mdbms.optimizer;

import java.util.List;
import mdbms.api.IQueryOptimizer;
import mdbms.api.ISchemaCatalog;
import mdbms.models.ParsedQuery;

public class QueryOptimizer implements IQueryOptimizer {
    private final ISchemaCatalog schemaCatalog;
    private final ShaylaParser parser;
    private final IQueryValidator validator;
    private final List<IOptimizationRule> rules;
    private final ICostEstimator costEstimator;

    public QueryOptimizer(
        ISchemaCatalog schemaCatalog,
        ShaylaParser parser,
        IQueryValidator validator,
        List<IOptimizationRule> rules,
        ICostEstimator costEstimator
    ) {
        this.schemaCatalog = schemaCatalog;
        this.parser = parser;
        this.validator = validator;
        this.rules = rules;
        this.costEstimator = costEstimator;
    }

    @Override
    public ParsedQuery parseQuery(String query) {
        // TODO
        return null;
    }

    @Override
    public ParsedQuery optimizeQuery(ParsedQuery query) {
        // TODO
        return null;
    }

    @Override
    public int getCost(ParsedQuery query) {
        // TODO
        return 0;
    }
}
