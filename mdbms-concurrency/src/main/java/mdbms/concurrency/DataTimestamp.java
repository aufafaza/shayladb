package mdbms.concurrency;

import mdbms.models.transaction.AccessStatus;
import mdbms.models.transaction.Transaction;

import java.util.Objects;

public class DataTimestamp {
    private long readTS;
    private long writeTS;
    private long uncommittedWriteTS;
    private Integer uncommittedWriterTxId;

    public DataTimestamp(long readTS, long writeTS) {
        this.readTS = readTS;
        this.writeTS = writeTS;
        this.uncommittedWriteTS = writeTS;
        this.uncommittedWriterTxId = null;
    }

    public synchronized long getWriteTS() {
        return Math.max(this.writeTS, (this.uncommittedWriterTxId != null ? this.uncommittedWriteTS : 0L));
    }

    public synchronized long getReadTS() {
        return this.readTS;
    }

    public synchronized void setWriteTS(long writeTS) {
        this.writeTS = writeTS;
        this.uncommittedWriteTS = Math.max(this.uncommittedWriteTS, writeTS);
    }

    public synchronized void setReadTS(long readTS) {
        this.readTS = readTS;
    }

    public synchronized void updateReadTS(long timestamp) {
        this.readTS = Math.max(this.readTS, timestamp);
    }

    public synchronized void updateWriteTS(long timestamp) {
        this.writeTS = Math.max(this.writeTS, timestamp);
        this.uncommittedWriteTS = Math.max(this.uncommittedWriteTS, timestamp);
    }

    public synchronized AccessStatus checkReadAllowed(Transaction tx) {
        if (tx == null) {
            return AccessStatus.ABORTED;
        }
        long ts = tx.getTimestamp();
        if (ts < this.writeTS) {
            return AccessStatus.ABORTED;
        }
        if (this.uncommittedWriterTxId != null && this.uncommittedWriterTxId != tx.getId()) {
            if (ts < this.uncommittedWriteTS) {
                return AccessStatus.ABORTED;
            }
            return AccessStatus.WAITING;
        }
        return AccessStatus.GRANTED;
    }

    public synchronized void applyRead(Transaction tx) {
        if (tx != null) {
            this.readTS = Math.max(this.readTS, tx.getTimestamp());
        }
    }

    public synchronized AccessStatus checkAndApplyRead(Transaction tx) {
        AccessStatus status = checkReadAllowed(tx);
        if (status == AccessStatus.GRANTED) {
            applyRead(tx);
        }
        return status;
    }

    public synchronized AccessStatus checkWriteAllowed(Transaction tx) {
        if (tx == null) {
            return AccessStatus.ABORTED;
        }
        long ts = tx.getTimestamp();
        if (ts < this.readTS || ts < this.writeTS) {
            return AccessStatus.ABORTED;
        }
        if (this.uncommittedWriterTxId != null && this.uncommittedWriterTxId != tx.getId()) {
            if (ts < this.uncommittedWriteTS) {
                return AccessStatus.ABORTED;
            }
            return AccessStatus.WAITING;
        }
        return AccessStatus.GRANTED;
    }

    public synchronized void applyWrite(Transaction tx) {
        if (tx != null) {
            this.uncommittedWriterTxId = tx.getId();
            this.uncommittedWriteTS = tx.getTimestamp();
        }
    }

    public synchronized AccessStatus checkAndApplyWrite(Transaction tx) {
        AccessStatus status = checkWriteAllowed(tx);
        if (status == AccessStatus.GRANTED) {
            applyWrite(tx);
        }
        return status;
    }

    public synchronized void commitWrite(Transaction tx) {
        if (tx != null && Objects.equals(this.uncommittedWriterTxId, tx.getId())) {
            this.writeTS = Math.max(this.writeTS, this.uncommittedWriteTS);
            this.uncommittedWriterTxId = null;
        }
    }

    public synchronized void rollbackWrite(Transaction tx) {
        if (tx != null && Objects.equals(this.uncommittedWriterTxId, tx.getId())) {
            this.uncommittedWriteTS = this.writeTS;
            this.uncommittedWriterTxId = null;
        }
    }
}
