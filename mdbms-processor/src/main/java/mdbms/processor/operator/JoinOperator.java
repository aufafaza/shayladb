package mdbms.processor.operator;

import java.util.*;
import java.util.function.BiPredicate;
import mdbms.processor.ResultRow;

public class JoinOperator implements IQueryOperator {

    private final IQueryOperator leftOperator;
    private final IQueryOperator rightOperator;
    private final BiPredicate<ResultRow, ResultRow> condition;
    private final boolean naturalJoin;
    private final int blockSize;

    private final List<ResultRow> rightRows = new ArrayList<>();
    private final List<ResultRow> leftBlock = new ArrayList<>();

    private int leftIndex;
    private int rightIndex;

    public JoinOperator(
            IQueryOperator leftOperator,
            IQueryOperator rightOperator,
            BiPredicate<ResultRow, ResultRow> condition,
            boolean naturalJoin,
            int blockSize) {

        if (blockSize <= 0) {
            throw new IllegalArgumentException("Invalid block size");
        }

        this.leftOperator = leftOperator;
        this.rightOperator = rightOperator;
        this.condition = condition;
        this.naturalJoin = naturalJoin;
        this.blockSize = blockSize;
    }

    @Override
    public void open() {
        rightRows.clear();
        leftBlock.clear();
        leftIndex = 0;
        rightIndex = 0;

        leftOperator.open();
        rightOperator.open();

        ResultRow row;
        while ((row = rightOperator.next()) != null) {
            rightRows.add(row);
        }

        loadNextBlock();
    }

    private void loadNextBlock() {
        leftBlock.clear();
        leftIndex = 0;
        rightIndex = 0;

        while (leftBlock.size() < blockSize) {
            ResultRow row = leftOperator.next();

            if (row == null) {
                break;
            }

            leftBlock.add(row);
        }
    }

    @Override
    public ResultRow next() {
        while (!leftBlock.isEmpty()) {

            while (rightIndex < rightRows.size()) {
                ResultRow left = leftBlock.get(leftIndex);
                ResultRow right = rightRows.get(rightIndex++);

                boolean matches = naturalJoin
                        ? naturalMatch(left, right)
                        : condition == null || condition.test(left, right);

                if (matches) {
                    return combine(left, right);
                }
            }

            rightIndex = 0;
            leftIndex++;

            if (leftIndex >= leftBlock.size()) {
                loadNextBlock();
            }
        }

        return null;
    }

    private boolean naturalMatch(ResultRow left, ResultRow right) {
        Map<String, Object> a = left.getValues();
        Map<String, Object> b = right.getValues();

        for (String column : a.keySet()) {
            if (b.containsKey(column)) {
                Object av = a.get(column);
                Object bv = b.get(column);

                if (av == null || bv == null || !av.equals(bv)) {
                    return false;
                }
            }
        }

        return true;
    }

    private ResultRow combine(ResultRow left, ResultRow right) {
        Map<String, Object> combined =
                new LinkedHashMap<>(left.getValues());

        for (Map.Entry<String, Object> entry :
                right.getValues().entrySet()) {

            String key = entry.getKey();

            if (naturalJoin && combined.containsKey(key)) {
                continue;
            }

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
            leftBlock.clear();
            leftIndex = 0;
            rightIndex = 0;
        }
    }
}