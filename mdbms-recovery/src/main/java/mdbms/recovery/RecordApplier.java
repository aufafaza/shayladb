package mdbms.recovery;

import mdbms.api.IDataAccess;
import mdbms.models.data.RowId;
import mdbms.models.log.DataChangeLogRecord;
import mdbms.models.log.RecordImage;

/**
 * Menerapkan operasi Redo dan Undo catatan log perubahan data ke Storage Manager melalui IDataAccess.
 */
public class RecordApplier {

    private final IDataAccess dataAccess;

    public RecordApplier(IDataAccess dataAccess) {
        this.dataAccess = dataAccess;
    }

    public boolean redo(DataChangeLogRecord record) {
        if (record == null || record.getRowId() == null || record.getType() == null) {
            return false;
        }
        RowId rid = record.getRowId();
        RecordImage afterImg = record.getAfterImage();

        return switch (record.getType()) {
            case INSERT -> {
                if (afterImg == null || afterImg.values() == null) {
                    yield false;
                }
                if (dataAccess != null) {
                    dataAccess.insertAt(rid, afterImg.values());
                }
                yield true;
            }
            case UPDATE -> {
                if (afterImg == null || afterImg.values() == null) {
                    yield false;
                }
                if (dataAccess != null) {
                    dataAccess.updateByRowId(rid, afterImg.values());
                }
                yield true;
            }
            case DELETE -> {
                if (dataAccess != null) {
                    dataAccess.deleteByRowId(rid);
                }
                yield true;
            }
            default -> false;
        };
    }

    public boolean undo(DataChangeLogRecord record) {
        if (record == null || record.getRowId() == null || record.getType() == null) {
            return false;
        }
        RowId rid = record.getRowId();
        RecordImage beforeImg = record.getBeforeImage();

        return switch (record.getType()) {
            case UPDATE -> {
                if (beforeImg == null || beforeImg.values() == null) {
                    yield false;
                }
                if (dataAccess != null) {
                    dataAccess.updateByRowId(rid, beforeImg.values());
                }
                yield true;
            }
            case INSERT -> {
                if (dataAccess != null) {
                    dataAccess.deleteByRowId(rid);
                }
                yield true;
            }
            case DELETE -> {
                if (beforeImg == null || beforeImg.values() == null) {
                    yield false;
                }
                if (dataAccess != null) {
                    dataAccess.insertAt(rid, beforeImg.values());
                }
                yield true;
            }
            default -> false;
        };
    }

    public IDataAccess getDataAccess() {
        return dataAccess;
    }
}
