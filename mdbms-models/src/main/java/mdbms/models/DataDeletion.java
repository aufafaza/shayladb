package mdbms.models;

import java.util.List;

public record DataDeletion(String table, List<Condition> conditions){}
