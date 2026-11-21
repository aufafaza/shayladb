package mdbms.api;

import mdbms.models.query.ParsedQuery;

public interface IQueryOptimizer {
    ParsedQuery parseQuery(String query);

    ParsedQuery optimizeQuery(ParsedQuery query);

    int getCost(ParsedQuery query);

    // Teks plan sebelum dan sesudah optimasi beserta biaya totalnya, dipakai perintah EXPLAIN
    String explainQuery(String query);
}
