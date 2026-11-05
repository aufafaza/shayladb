package mdbms.models.access;

import java.util.List;

import mdbms.models.data.Condition;

public record DataRetrieval(String table, List<String> columns, List<Condition> conditions){}
