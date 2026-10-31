package mdbms.processor.operator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mdbms.processor.ResultRow;

public class CartesianJoinOperator implements IQueryOperator {

    private final IQueryOperator leftOperator;
    private final IQueryOperator rightOperator;

    private final List<ResultRow> rightRows = new ArrayList<>();
    private ResultRow currentLeft;
    private int rightIndex;

    public CartesianJoinOperator(
            IQueryOperator leftOperator,
            IQueryOperator rightOperator) {
        this.leftOperator = leftOperator;
        this.rightOperator = rightOperator;
    }

    @Override
    public void open() {
        rightRows.clear();
        currentLeft = null;
        rightIndex = 0;

        leftOperator.open();
        rightOperator.open();

        ResultRow row;
        while ((row = rightOperator.next()) != null) {
            rightRows.add(row);
        }

        currentLeft = leftOperator.next();
    }

    @Override
    public ResultRow next() {
        while (currentLeft != null) {

            if (rightIndex < rightRows.size()) {
                ResultRow right = rightRows.get(rightIndex++);
                return combine(currentLeft, right);
            }

            currentLeft = leftOperator.next();
            rightIndex = 0;
        }

        return null;
    }

    private ResultRow combine(ResultRow left, ResultRow right) {
        Map<String, Object> combined =
                new LinkedHashMap<>(left.getValues());

        for (Map.Entry<String, Object> entry :
                right.getValues().entrySet()) {

            String key = entry.getKey();

            if (combined.containsKey(key)) {
                String base = "right." + key;
                key = base;
                int suffix = 2;

                while (combined.containsKey(key)) {
                    key = base + "_" + suffix++;
                }
            }

            combined.put(key, entry.getValue());
        }

        return new ResultRow(combined);
    }

    @Override
    public void close() {
        try {
            leftOperator.close();
        } finally {
            rightOperator.close();
            rightRows.clear();
            currentLeft = null;
            rightIndex = 0;
        }
    }
}