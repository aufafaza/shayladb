package mdbms.recovery;

import mdbms.api.IDataAccess;
import mdbms.models.data.Row;
import mdbms.models.data.RowId;
import mdbms.models.log.DataChangeLogRecord;
import mdbms.models.log.RecordImage;

/**
 * Menerapkan operasi Redo dan Undo catatan log perubahan data ke Storage Manager melalui IDataAccess.
 */
public class RecordApplier {

    private final IDataAccess dataAccess;

    public RecordApplier() {
        this(null);
    }

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
                    applyIdempotentInsert(record.getTable(), rid, afterImg);
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
                    applyIdempotentDelete(rid);
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
                    applyIdempotentDelete(rid);
                }
                yield true;
            }
            case DELETE -> {
                if (beforeImg == null || beforeImg.values() == null) {
                    yield false;
                }
                if (dataAccess != null) {
                    applyIdempotentInsert(record.getTable(), rid, beforeImg);
                }
                yield true;
            }
            default -> false;
        };
    }

    private void applyIdempotentInsert(String table, RowId rid, RecordImage img) {
        try {
            Row existing = dataAccess.fetchByRowId(rid);
            if (existing != null) {
                dataAccess.updateByRowId(rid, img.values());
                return;
            }
        } catch (RuntimeException ignored) {
            // Slot belum dialokasikan (Invalid slot) atau berstatus kosong (Slot contains no record)
        }

        try {
            dataAccess.insertAt(rid, img.values());
        } catch (RuntimeException e) {
            for (int i = 0; i <= rid.slot(); i++) {
                RowId reserved = dataAccess.reserveRowId(table, img.values());
                dataAccess.releaseRowId(reserved);
                if (reserved.blockNumber() > rid.blockNumber()
                        || (reserved.blockNumber() == rid.blockNumber() && reserved.slot() >= rid.slot())) {
                    break;
                }
            }
            try {
                dataAccess.insertAt(rid, img.values());
            } catch (RuntimeException ex) {
                dataAccess.updateByRowId(rid, img.values());
            }
        }
    }

    private void applyIdempotentDelete(RowId rid) {
        try {
            dataAccess.deleteByRowId(rid);
        } catch (RuntimeException ignored) {
            // Jika slot belum pernah ter-flush ke disk atau sudah berstatus DELETED, abaikan dengan aman
        }
    }

    public IDataAccess getDataAccess() {
        return dataAccess;
    }
}