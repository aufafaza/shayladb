package mdbms.processor.operator;

import mdbms.api.ISchemaCatalog;
import mdbms.models.schema.DropBehavior;
import mdbms.processor.IResultRow;

public final class DropTableOperator implements IQueryOperator, IQueryMessage {
    private final ISchemaCatalog catalog;
    private final String table;
    private final DropBehavior behavior;
    private boolean done;
    
    public DropTableOperator(ISchemaCatalog c,String t){
        this(c, t, DropBehavior.RESTRICT);
    }

    public DropTableOperator(ISchemaCatalog c, String t, DropBehavior behavior) {
        catalog=c;
        table=t;
        this.behavior = behavior;
    }
    
    public void execute(){
        catalog.dropTable(table, behavior);
    }

    @Override public void open() { done = false; }
    @Override public IResultRow next() {
        if (!done) { execute(); done = true; }
        return null;
    }
    @Override public void close() { }
    @Override public String getMessage() { return "Table " + table + " dropped"; }
}
