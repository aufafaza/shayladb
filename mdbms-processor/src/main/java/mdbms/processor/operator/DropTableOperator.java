package mdbms.processor.operator;

import mdbms.api.ISchemaCatalog;

public final class DropTableOperator {
    private final ISchemaCatalog catalog;
    private final String table;
    
    public DropTableOperator(ISchemaCatalog c,String t){
        catalog=c;
        table=t;
    }
    
    public void execute(){
        catalog.dropTable(table);
    }
}
