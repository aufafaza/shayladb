package mdbms.models.access;

import java.util.List;

import mdbms.models.data.Condition;

public record DataDeletion(String table, List<Condition> conditions){}
