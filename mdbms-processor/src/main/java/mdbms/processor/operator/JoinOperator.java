package mdbms.processor.operator;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import mdbms.processor.IResultRow;

public class JoinOperator extends QueryBinaryOperator {
    private final BiPredicate<IResultRow, IResultRow> condition;
    private final boolean natural;
    private final int blockSize;
    private final List<IResultRow> rightRows = new ArrayList<>();
    private final List<IResultRow> leftBlock = new ArrayList<>();
    private int leftIndex;
    private int rightIndex;

    public JoinOperator(IQueryOperator left, IQueryOperator right,
                        BiPredicate<IResultRow, IResultRow> condition,
                        boolean natural, int blockSize) {
        super(left, right);
        if (blockSize <= 0) throw new IllegalArgumentException("Ukuran blok harus positif");
        this.condition = condition;
        this.natural = natural;
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
        IResultRow row;
        while ((row = rightOperator.next()) != null) rightRows.add(row);
        loadBlock();
    }

    private void loadBlock() {
        leftBlock.clear();
        leftIndex = 0;
        rightIndex = 0;
        while (leftBlock.size() < blockSize) {
            IResultRow row = leftOperator.next();
            if (row == null) break;
            leftBlock.add(row);
        }
    }

    @Override
    public IResultRow next() {
        while (!leftBlock.isEmpty() && !rightRows.isEmpty()) {
            while (leftIndex < leftBlock.size()) {
                IResultRow left = leftBlock.get(leftIndex);
                while (rightIndex < rightRows.size()) {
                    IResultRow right = rightRows.get(rightIndex++);
                    boolean match = natural ? JoinRowUtil.naturalMatch(left, right)
                        : condition == null || condition.test(left, right);
                    if (match) return JoinRowUtil.combine(left, right, natural);
                }
                rightIndex = 0;
                leftIndex++;
            }
            loadBlock();
        }
        return null;
    }

    @Override
    public void close() {
        try { leftOperator.close(); }
        finally {
            try { rightOperator.close(); }
            finally { rightRows.clear(); leftBlock.clear(); leftIndex = 0; rightIndex = 0; }
        }
    }
}
