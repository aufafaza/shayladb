package mdbms.models;

public record DataItem(String tableName, RowId rowId){
    public String getTableName(){ 
        return tableName; 
    }

    public RowId getRowId(){ 
        return rowId; 
    }
} 
