package mdbms.models;

public record Condition(String column, ComparisonOperator operator, Object value){}