package mdbms.processor.operator;

import mdbms.api.ISchemaCatalog;
import mdbms.models.ColumnDefinition;
import mdbms.models.DataType;
import mdbms.models.IndexInfo;
import mdbms.processor.IResultRow;
import mdbms.processor.ResultRow;
import mdbms.processor.ResultTableSchema;
import mdbms.models.IndexInfo;
import mdbms.processor.IResultRow;

public class CreateIndexOperator implements IQueryOperator {
    private final ISchemaCatalog schemaCatalog;
    private final IndexInfo indexInfo;
    private boolean isDone;
    private String message;

    public CreateIndexOperator(ISchemaCatalog schemaCatalog, IndexInfo indexInfo) {
        this.schemaCatalog = schemaCatalog;
        this.indexInfo = indexInfo;
    }

    public void open() {
        isDone = false;
        message = null;
    }

    public IResultRow next() {
        if (isDone) return null;
        isDone = true;

        schemaCatalog.createIndex(indexInfo.indexName(), indexInfo.tableName(), indexInfo.columnName());
        String message = "Index " + indexInfo.indexName() + " created on "
                + indexInfo.tableName() + "(" + indexInfo.columnName() + ")";

        ResultTableSchema schema = new ResultTableSchema();
        schema.addColumn(new ColumnDefinition("message", DataType.VARCHAR, message.length(), false));
        ResultRow result = new ResultRow(schema);
        result.set(schema.getTableName(), "message", message);
        return result;

        // TODO BUTUH ResultRow dari QP2 untuk mengembalikan pesan konfirmasi
        // return null;
    }

    public void close() {
    }

    public String getMessage() {
        return message;
    }
}
