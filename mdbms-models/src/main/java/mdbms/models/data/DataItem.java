package mdbms.models.data;

public record DataItem(String tableName, RowId rowId){
    private static final RowId TABLE_RANGE = new RowId("__RANGE__", -1, -1);

    /** Lock target that stands for "the set of rows of a table" (used for phantom protection). */
    public static DataItem tableRange(String tableName){
        return new DataItem(tableName, TABLE_RANGE);
    }

    public boolean isTableRange(){
        return TABLE_RANGE.equals(rowId);
    }

    public String getTableName(){ 
        return tableName; 
    }

    public RowId getRowId(){ 
        return rowId; 
    }
} 
