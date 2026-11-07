package mdbms.models.data;

public record Condition(String column, ComparisonOperator operator, Object value){}