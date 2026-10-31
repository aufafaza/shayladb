package mdbms.recovery;

import java.util.List;
import mdbms.models.LogRecord;

public class WalFileHandler {

    public WalFileHandler() {
    }

    public boolean append(LogRecord record) {
        return true;
    }

    public boolean flushUpTo(long lsn) {
        return lsn >= 0;
    }

    public List<LogRecord> readAll() {
        return List.of();
    }
}
