package mdbms.recovery;

import java.util.regex.Pattern;

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

    public boolean  redo(DataChangeLogRecord record) {
        if (dataAccess == null || record == null || record.getRowId() == null || record.getType() == null) {
            return false;
        }
        RowId rid = record.getRowId();
        RecordImage afterImg = record.getAfterImage();

        return switch (record.getType()) {
            case INSERT -> {
                if (afterImg == null || afterImg.values() == null) {
                    yield false;
                }
                applyIdempotentInsert(record.getTable(), rid, afterImg);
                yield true;
            }
            case UPDATE -> {
                if (afterImg == null || afterImg.values() == null) {
                    yield false;
                }
                if (record.isRelocation()) {
                    // Redo relokasi: pastikan oldRid bersih dan newRid terpasang dengan afterImg
                    applyIdempotentDelete(rid);
                    applyIdempotentInsert(record.getTable(), record.getNewRowId(), afterImg);
                } else {
                    applyIdempotentUpdate(record.getTable(), rid, afterImg);
                }
                yield true;
            }
            case DELETE -> {
                applyIdempotentDelete(rid);
                yield true;
            }
            default -> false;
        };
    }

    public boolean undo(DataChangeLogRecord record) {
        if (dataAccess == null || record == null || record.getRowId() == null || record.getType() == null) {
            return false;
        }
        RowId rid = record.getRowId();
        RecordImage beforeImg = record.getBeforeImage();

        return switch (record.getType()) {
            case UPDATE -> {
                if (beforeImg == null || beforeImg.values() == null) {
                    yield false;
                }
                if (record.isRelocation()) {
                    applyIdempotentDeleteAndRelease(record.getNewRowId());
                    applyIdempotentInsert(record.getTable(), rid, beforeImg);
                } else {
                    applyIdempotentUpdate(record.getTable(), rid, beforeImg);
                }
                yield true;
            }
            case INSERT -> {
                applyIdempotentDelete(rid);
                yield true;
            }
            case DELETE -> {
                if (beforeImg == null || beforeImg.values() == null) {
                    yield false;
                }
                applyIdempotentInsert(record.getTable(), rid, beforeImg);
                yield true;
            }
            default -> false;
        };
    }

    private static final Pattern INVALID_SLOT_INDEX_PATTERN =
            Pattern.compile("invalid slot -?\\d+");
    private static final Pattern BLOCK_NOT_EXIST_PATTERN =
            Pattern.compile("block does not exist:\\s*-?\\d+");

    private void applyIdempotentInsert(String table, RowId rid, RecordImage img) {
        try {
            Row existing = dataAccess.fetchByRowId(rid);
            if (existing != null) {
                if (!img.values().equals(existing.values())) {
                    dataAccess.updateByRowId(rid, img.values());
                }
                return;
            }
        } catch (RuntimeException e) {
            if (!isMissingSlotOrRowError(e)) {
                throw e;
            }
        }

        try {
            dataAccess.insertAt(rid, img.values());
        } catch (RuntimeException e) {
            if (!isMissingSlotOrRowError(e)) {
                throw e;
            }
            final int maxAllocations = 10_000;
            boolean reached = false;
            for (int i = 0; i < maxAllocations; i++) {
                RowId reserved = dataAccess.reserveRowId(table, img.values());
                dataAccess.releaseRowId(reserved);
                if (reserved.blockNumber() > rid.blockNumber()
                        || (reserved.blockNumber() == rid.blockNumber() && reserved.slot() >= rid.slot())) {
                    reached = true;
                    break;
                }
            }
            if (!reached) {
                throw new IllegalStateException("Gagal mengalokasikan slot/block target: " + rid, e);
            }
            dataAccess.insertAt(rid, img.values());
        }
    }

    private void applyIdempotentDelete(RowId rid) {
        try {
            dataAccess.deleteByRowId(rid);
        } catch (RuntimeException e) {
            if (!isMissingSlotOrRowError(e)) {
                throw e;
            }
        }
    }

    private void applyIdempotentDeleteAndRelease(RowId rid) {
        Row deleted = null;
        try {
            deleted = dataAccess.deleteByRowId(rid);
        } catch (RuntimeException e) {
            if (!isMissingSlotOrRowError(e)) {
                throw e;
            }
        }
        if (deleted == null) {
            try {
                dataAccess.releaseRowId(rid);
            } catch (RuntimeException e) {
                if (!isMissingSlotOrRowError(e) && !isNotReservedError(e)) {
                    throw e;
                }
            }
        }
    }

    private void applyIdempotentUpdate(String table, RowId rid, RecordImage img) {
        try {
            dataAccess.updateByRowId(rid, img.values());
        } catch (RuntimeException e) {
            if (!isMissingSlotOrRowError(e)) {
                throw e;
            }
            applyIdempotentInsert(table, rid, img);
        }
    }

    private boolean isNotReservedError(RuntimeException e) {
        if (hasIoOrCorruptionCause(e)) {
            return false;
        }
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return msg.contains("not reserved") || msg.contains("no reservation");
    }

    private boolean isMissingSlotOrRowError(RuntimeException e) {
        if (hasIoOrCorruptionCause(e)) {
            return false;
        }
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return INVALID_SLOT_INDEX_PATTERN.matcher(msg).find()
                || BLOCK_NOT_EXIST_PATTERN.matcher(msg).find()
                || msg.contains("row not found")
                || msg.contains("slot contains no record")
                || msg.contains("already deleted")
                || (msg.contains("slot") && msg.contains("empty"));
    }

    private boolean hasIoOrCorruptionCause(Throwable t) {
        Throwable curr = t;
        while (curr != null) {
            if (curr instanceof java.io.IOException || curr instanceof java.io.UncheckedIOException) {
                return true;
            }
            String msg = curr.getMessage() == null ? "" : curr.getMessage().toLowerCase();
            if (msg.contains("corrupt") || msg.contains("i/o") || msg.contains("disk")
                    || msg.contains("invalid slot count") || msg.contains("invalid slot entry")) {
                return true;
            }
            curr = curr.getCause();
        }
        return false;
    }

    public IDataAccess getDataAccess() {
        return dataAccess;
    }
}