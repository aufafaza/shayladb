package mdbms.processor.operator;

import java.util.List;

import mdbms.models.query.ColumnReference;
import mdbms.models.schema.ColumnDefinition;
import mdbms.processor.IResultRow;
import mdbms.processor.ResultRow;
import mdbms.processor.ResultTableSchema;

public class ProjectOperator extends QueryUnaryOperator{
    private final List<ColumnReference> columns;
    private ResultTableSchema outputSchema;

    public ProjectOperator(IQueryOperator child,List<ColumnReference> columns){
        super(child);
        this.columns = List.copyOf(columns);
    }

    @Override public void open() {
        childOperator.open();
    }

    private void createSchema(ResultTableSchema input) {
        if (input == null) throw new IllegalStateException("Child operator tidak menghasilkan schema sesuai");
        outputSchema = new ResultTableSchema();
        for (ColumnReference ref : columns) {
            String origin = ref.getTableName() == null ? input.getColumnTableOrigin(ref.getColumnName()) : ref.getTableName();
            ColumnDefinition definition = null;
            for (int i=0; i<input.getColumns().size(); i++) {
                ColumnDefinition candidate = input.getColumns().get(i);
                if (candidate.name().equalsIgnoreCase(ref.getColumnName())
                        && (ref.getTableName() == null || ref.getTableName().equalsIgnoreCase(input.getColumnTableOrigin(i)))) {
                    definition = candidate;
                    break;
                }
            }
            if (definition == null) throw new IllegalArgumentException("Unknown column: " + ref);
            outputSchema.addColumn(origin, definition);
        }
    }


    @Override public IResultRow next(){
        IResultRow input = childOperator.next();
        if (input == null) return null;
        if (outputSchema == null) createSchema(input.getSchema());

        ResultRow output = new ResultRow(outputSchema);
        for (ColumnReference ref : columns) {
            String origin = ref.getTableName() == null ? input.getSchema().getColumnTableOrigin(ref.getColumnName()) : ref.getTableName();
            output.set(origin, ref.getColumnName(), input.get(origin, ref.getColumnName()));
        }
        output.setRowId(input.getRowId());
        return output;
    }
}
