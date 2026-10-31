package mdbms.processor.operator;

public final class CreateTableOperator {
    private final ISchemaCatalog catalog;
    public CreateTableOperator(ISchemaCatalog c){
        catalog=c;
    }
    
    public void execute(TableSchema schema){
        catalog.updateTableSchema(schema);
    }
}
