package mdbms.models;

import java.util.List;

public record DataRetrieval(String table, List<String> columns, List<Condition> conditions){}
