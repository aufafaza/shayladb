package mdbms.optimizer;

import java.util.List;
import java.util.Objects;
import mdbms.api.IQueryOptimizer;
import mdbms.api.ISchemaCatalog;
import mdbms.models.query.ParsedQuery;
import mdbms.models.query.QueryTree;
import mdbms.optimizer.binder.BindingContext;
import mdbms.optimizer.binder.QueryBinder;
import mdbms.optimizer.binder.QueryValidator;
import mdbms.optimizer.exception.QuerySemanticException;
import mdbms.optimizer.binder.IQueryValidator;
import mdbms.optimizer.cost.ICostEstimator;
import mdbms.optimizer.parser.ShaylaParser;
import mdbms.optimizer.rule.IOptimizationRule;
import mdbms.optimizer.rule.JoinConversionRule;
import mdbms.optimizer.rule.JoinOrderingRule;
import mdbms.optimizer.rule.SelectionPushDownRule;
import mdbms.optimizer.rule.ProjectionPushDownRule;
import mdbms.optimizer.cost.CardinalityEstimator;
import mdbms.optimizer.cost.SystemCostEstimator;
import mdbms.optimizer.rule.OptimizationContext;
import mdbms.optimizer.rule.RuleBasedOptimizer;
import mdbms.api.IStatisticsProvider;

public class QueryOptimizer implements IQueryOptimizer {
    private final ISchemaCatalog schemaCatalog;
    private final ShaylaParser parser;
    private final IQueryValidator validator;
    private final List<IOptimizationRule> rules;
    private final ICostEstimator costEstimator;

    public QueryOptimizer(ISchemaCatalog schemaCatalog, ShaylaParser parser) {
        this(schemaCatalog, parser, new QueryValidator(schemaCatalog),
            List.of(new SelectionPushDownRule(), new JoinConversionRule(), new JoinOrderingRule(),
                new ProjectionPushDownRule()), null);
    }

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
        this.costEstimator = costEstimator != null ? costEstimator : new SystemCostEstimator(
            schemaCatalog instanceof IStatisticsProvider provider ? provider : null, new CardinalityEstimator());
    }

    @Override
    public ParsedQuery parseQuery(String query) {
        Objects.requireNonNull(query, "query");
        QueryTree tree = parser.parseToTree(query);
        BindingContext bindings = new QueryBinder(schemaCatalog).bind(tree);
        if (validator instanceof QueryValidator queryValidator) {
            queryValidator.validate(tree, bindings);
        } else if (!validator.validate(tree)) {
            throw new QuerySemanticException("Query validation failed");
        }
        List<String> tables = bindings.getTableBindings().values().stream()
            .map(binding -> binding.getTableName()).distinct().toList();
        return new ParsedQuery(query, tree, tables);
    }

    @Override
    public ParsedQuery optimizeQuery(ParsedQuery query) {
        Objects.requireNonNull(query, "query");
        BindingContext bindings = new QueryBinder(schemaCatalog).bind(query.getQueryTree());
        IStatisticsProvider statistics = schemaCatalog instanceof IStatisticsProvider provider ? provider : null;
        OptimizationContext context = new OptimizationContext(bindings, schemaCatalog, statistics, costEstimator);
        QueryTree result = new RuleBasedOptimizer(rules).optimize(query.getQueryTree(), context);
        return new ParsedQuery(query.rawQuery(), result, query.targetTables());
    }

    /** Estimates the complete supplied plan and refreshes each node's local cost. */
    @Override
    public int getCost(ParsedQuery query) {
        Objects.requireNonNull(query, "query");
        return costEstimator.calculateTotalCost(Objects.requireNonNull(query.getQueryTree(), "queryTree"));
    }
}
