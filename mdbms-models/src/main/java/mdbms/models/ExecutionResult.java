package mdbms.models;

import java.util.List;

public record ExecutionResult(boolean success, List<String> columns, List<List<Object>> rows, int affectedRows, String message){}
