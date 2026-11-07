package mdbms.api;

import java.util.List;
import mdbms.models.schema.IndexInfo;
import mdbms.models.schema.TableSchema;

public interface ISchemaCatalog{
    TableSchema getTableSchema(String table);

    List<String> listTable();

    void updateTableSchema(TableSchema schema);

    void dropTable(String table);

    List<IndexInfo> getIndexes(String table);

    IndexInfo createIndex(String name, String table, String column);
}