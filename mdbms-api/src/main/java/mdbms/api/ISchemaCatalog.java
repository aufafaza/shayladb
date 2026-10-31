package mdbms.api;

import java.util.List;
import mdbms.models.IndexInfo;
import mdbms.models.TableSchema;

public interface ISchemaCatalog{
    TableSchema getTableSchema(String table);

    List<String> listTable();

    void updateTableSchema(TableSchema schema);

    List<IndexInfo> getIndexes(String table);

    IndexInfo createIndex(String name, String table, String column);
}