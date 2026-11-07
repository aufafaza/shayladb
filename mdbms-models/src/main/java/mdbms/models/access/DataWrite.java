package mdbms.models.access;

import java.util.List;
import java.util.Map;

import mdbms.models.data.Condition;

public record DataWrite(String table, Map<String, Object> values, List<Condition> conditions){}
