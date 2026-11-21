package mdbms.processor.operator;

import mdbms.api.ISchemaCatalog;
import mdbms.models.schema.TableSchema;
import mdbms.processor.IResultRow;

public final class CreateTableOperator implements IQueryOperator, IQueryMessage {
    private final ISchemaCatalog catalog;
    private final TableSchema definition;
    private boolean done;
    private String message;
    public CreateTableOperator(ISchemaCatalog c){
        this(c, null);
    }

    public CreateTableOperator(ISchemaCatalog catalog, TableSchema definition) {
        this.catalog = catalog;
        this.definition = definition;
    }
    
    public void execute(TableSchema schema){
        if (catalog.listTable().stream().anyMatch(name -> name.equalsIgnoreCase(schema.tableName()))) {
            throw new IllegalArgumentException("Table already exists: " + schema.tableName());
        }
        catalog.updateTableSchema(schema);
        message = "Table " + schema.tableName() + " created";
    }

    @Override public void open() { done = false; }

    @Override public IResultRow next() {
        if (!done) {
            if (definition == null) throw new IllegalStateException("Missing CREATE TABLE definition");
            execute(definition);
            done = true;
        }
        return null;
    }

    @Override public void close() { }
    @Override public String getMessage() { return message; }
}
