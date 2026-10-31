package mdbms.processor.operator;

import mdbms.api.ISchemaCatalog;
import mdbms.models.TableSchema;

public final class CreateTableOperator {
    private final ISchemaCatalog catalog;
    public CreateTableOperator(ISchemaCatalog c){
        catalog=c;
    }
    
    public void execute(TableSchema schema){
        catalog.updateTableSchema(schema);
    }
}
