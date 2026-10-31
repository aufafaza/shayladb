package mdbms.models;

import java.util.Map;

public record DataWrite(String table, Map<String, Object> values, Condition condition){}