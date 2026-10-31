package mdbms.api;

import mdbms.models.ParsedQuery;

public interface IQueryOptimizer {
    ParsedQuery parseQuery(String query);

    ParsedQuery optimizeQuery(ParsedQuery query);

    int getCost(ParsedQuery query);
}
