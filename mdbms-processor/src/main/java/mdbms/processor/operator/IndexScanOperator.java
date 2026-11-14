package mdbms.processor.operator;

import java.util.List;
import mdbms.api.IConcurrencyControl;
import mdbms.api.IDataAccess;
import mdbms.api.ISchemaCatalog;
import mdbms.models.data.DataItem;
import mdbms.models.data.Row;
import mdbms.models.data.RowId;
import mdbms.models.query.IndexScanSpec;
import mdbms.processor.IResultRow;
import mdbms.processor.ResultRow;
import mdbms.processor.ResultTableSchema;

public class IndexScanOperator implements IQueryOperator {
    private final IDataAccess dataAccess;
    private final ISchemaCatalog schemaCatalog;
    private final IConcurrencyControl concurrencyControlManager;
    private final int transactionId;
    private final String tableName;
    private final IndexScanSpec spec;
    private ResultTableSchema schema;
    private List<RowId> rowIds;
    private int position;

    public IndexScanOperator(IDataAccess dataAccess, ISchemaCatalog schemaCatalog,
            IConcurrencyControl concurrencyControlManager, int transactionId, String tableName,
            IndexScanSpec spec) {
        this.dataAccess = dataAccess;
        this.schemaCatalog = schemaCatalog;
        this.concurrencyControlManager = concurrencyControlManager;
        this.transactionId = transactionId;
        this.tableName = tableName;
        this.spec = spec;
    }

    @Override public void open() {
        if(schemaCatalog.getTableSchema(tableName)==null) throw new IllegalArgumentException("Tabel tidak ditemukan: "+tableName);

        schema = new ResultTableSchema(schemaCatalog.getTableSchema(tableName));
        rowIds = lookup();
        position = 0;
    }

    // Menerjemahkan IndexScanSpec jadi pemanggilan ke Storage Manager.
    private List<RowId> lookup() {
        String indexName = spec.index().indexName();
        Object value = spec.condition().value();

        return switch (spec.condition().operator()) {
            case EQ  -> dataAccess.searchIndex(indexName, value);
            case GT  -> dataAccess.searchIndexRange(indexName, value, null, false, false);
            case GTE -> dataAccess.searchIndexRange(indexName, value, null, true,  false);
            case LT  -> dataAccess.searchIndexRange(indexName, null, value, false, false);
            case LTE -> dataAccess.searchIndexRange(indexName, null, value, false, true);
            case NEQ -> throw new IllegalStateException("<> tidak bisa memakai index");
        };
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
