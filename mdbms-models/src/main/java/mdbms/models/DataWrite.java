package mdbms.models;

import java.util.List;
import java.util.Map;

public record DataWrite(String table, Map<String, Object> values, List<Condition> conditions){}
