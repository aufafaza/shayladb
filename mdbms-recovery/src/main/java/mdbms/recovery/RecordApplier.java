package mdbms.recovery;

import mdbms.models.DataChangeLogRecord;

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
