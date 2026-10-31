package mdbms.processor;

import java.util.LinkedHashMap;
import java.util.Map;

public class ResultRow {

    private final Map<String, Object> values;

    public ResultRow(Map<String, Object> values) {
        this.values = new LinkedHashMap<>(values);
    }

    public Object getValue(String column) {
        return values.get(column);
    }

    public Map<String, Object> getValues() {
        return new LinkedHashMap<>(values);
    }
}