package mdbms.recovery;

import mdbms.models.log.DataChangeLogRecord;

public class RecordApplier {

    public RecordApplier() {
    }

    public boolean redo(DataChangeLogRecord record) {
        return true;
    }

    public boolean undo(DataChangeLogRecord record) {
        return true;
    }
}
