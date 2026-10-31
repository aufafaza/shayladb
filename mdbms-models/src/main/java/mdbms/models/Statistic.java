package mdbms.models;

import java.util.Map;

public record(String tableName, int nr, int br, int lr, int fr, Map<String, Integer> distinctValues){}