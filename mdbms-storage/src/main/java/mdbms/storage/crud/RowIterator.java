package mdbms.storage.crud;

import java.util.Iterator;
import mdbms.models.data.Row;

public interface RowIterator extends Iterator<Row>, AutoCloseable{
    @Override
    public void close();
}