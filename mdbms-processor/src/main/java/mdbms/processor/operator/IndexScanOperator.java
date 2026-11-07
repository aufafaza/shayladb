package mdbms.processor.operator;

import java.util.List;
import mdbms.api.IConcurrencyControl;
import mdbms.api.IDataAccess;
import mdbms.api.ISchemaCatalog;
import mdbms.models.data.DataItem;
import mdbms.models.data.Row;
import mdbms.models.data.RowId;
import mdbms.processor.IResultRow;
import mdbms.processor.ResultRow;
import mdbms.processor.ResultTableSchema;

public class IndexScanOperator implements IQueryOperator {
    private final IDataAccess dataAccess;
    private final ISchemaCatalog schemaCatalog;
    private final IConcurrencyControl concurrencyControlManager;
    private final int transactionId;
    private final String tableName;
    private final String indexName;
    private final Object key;
    private ResultTableSchema schema;
    private List<RowId> rowIds;
    private int position;

    public IndexScanOperator(IDataAccess dataAccess, ISchemaCatalog schemaCatalog,
            IConcurrencyControl concurrencyControlManager, int transactionId, String tableName,
            String indexName, Object key) {
        this.dataAccess = dataAccess;
        this.schemaCatalog = schemaCatalog;
        this.concurrencyControlManager = concurrencyControlManager;
        this.transactionId = transactionId;
        this.tableName = tableName;
        this.indexName = indexName;
        this.key = key;
    }

    @Override public void open() {
        if(schemaCatalog.getTableSchema(tableName)==null) throw new IllegalArgumentException("Tabel tidak ditemukan: "+tableName);

        schema = new ResultTableSchema(schemaCatalog.getTableSchema(tableName));
        rowIds = dataAccess.searchIndex(indexName, key);
        position = 0;
    }

    @Override public IResultRow next() {
        if (position >= rowIds.size()) return null;
        RowId rowId = rowIds.get(position++);

        AccessPermission.read(concurrencyControlManager, transactionId, new DataItem(tableName, rowId));
        Row row = dataAccess.fetchByRowId(rowId);

        ResultRow result = new ResultRow(schema, row.values());
        result.setRowId(rowId);
        return result;
    }

    @Override public void close() {
        rowIds = List.of(); 
        position = 0;
    }
}
