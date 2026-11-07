package mdbms.processor.operator;

import java.util.List;

import mdbms.models.schema.ColumnDefinition;
import mdbms.processor.IResultRow;
import mdbms.processor.ResultRow;
import mdbms.processor.ResultTableSchema;

public class ProjectOperator extends QueryUnaryOperator{
    private final List<String> columns;
    private ResultTableSchema outputSchema;

    public ProjectOperator(IQueryOperator child,List<String> columns){
        super(child);
        this.columns = List.copyOf(columns);
    }

    @Override public void open() {
        childOperator.open();
    }

    private void createSchema(ResultTableSchema input) {
        if (input == null) throw new IllegalStateException("Child operator tidak menghasilkan schema sesuai");
        outputSchema = new ResultTableSchema();
        for (String ref : columns) {
            String[] p = parts(ref);
            String origin = p[0] == null ? input.getColumnTableOrigin(p[1]) : p[0];
            ColumnDefinition definition = null;
            for (int i=0; i<input.getColumns().size(); i++) {
                ColumnDefinition candidate = input.getColumns().get(i);
                if (candidate.name().equalsIgnoreCase(p[1])
                        && (p[0] == null || p[0].equalsIgnoreCase(input.getColumnTableOrigin(i)))) {
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
        for (String ref : columns) {
            String[] p = parts(ref);
            String origin = p[0] == null ? input.getSchema().getOrigin(p[1]) : p[0];
            output.set(origin, p[1], input.get(origin, p[1]));
        }
        output.setRowId(input.getRowId());
        return output;
    }

    private static String[] parts(String ref){
        String name = ref.trim();
        int dot = name.indexOf('.');
        
       return dot < 0 ? new String[] { null, name }
                : new String[] { name.substring(0, dot), name.substring(dot + 1) };
    }
}
