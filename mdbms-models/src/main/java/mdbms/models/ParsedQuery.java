package mdbms.models;

import java.util.List;

public record ParsedQuery(String rawQuery, QueryTree queryTree, List<String> targetTables){
    public QueryTree getQueryTree(){
        return queryTree;
    }
}
