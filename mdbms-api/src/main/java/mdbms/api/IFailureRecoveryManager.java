package mdbms.api;

import java.time.Instant;
import java.util.List;
import mdbms.models.LogRecord;
import mdbms.models.LogType;
import mdbms.models.RecordImage;
import mdbms.models.RecoveryResult;
import mdbms.models.RowId;

/*
Interface dari komponen Failure Recovery Manager (FRM)
Digunakan oleh Concurrency Control Manager (CCM) dan Storage Manager (SM)
*/
public interface IFailureRecoveryManager {

    /* Mencatat peristiwa siklus hidup transaksi (BEGIN, COMMIT, ABORT) ke dalam WAL (FR-02) */
    RecoveryResult writeTransactionLog(int txId, LogType eventType);

    /* Mencatat operasi perubahan data (INSERT, UPDATE, DELETE) ke dalam WAL (FR-01) */
    RecoveryResult writeDataLog(
        int txId,
        LogType opType,
        String table,
        RowId rid,
        RecordImage beforeImg,
        RecordImage afterImg
    );

    /* Memastikan seluruh catatan log hingga LSN tertentu telah tersimpan permanen di disk (FR-01, SM-12) */
    boolean flushLogUpTo(long lsn);

    /* Membatalkan seluruh perubahan transaksi aktif dalam urutan terbalik / Undo (FR-04) */
    RecoveryResult rollbackTransaction(int txId);

    /* Membuat penanda checkpoint dan menyelaraskan buffer data ke disk (FR-06) */
    RecoveryResult createCheckpoint();

    /* Menjalankan pemulihan otomatis (Analysis, Redo, Undo) saat sistem dinyalakan kembali pasca-crash (FR-07) */
    RecoveryResult recoverOnStartup();

    /* Memulihkan keadaan basis data ke titik waktu tertentu / PITR (FR-05) */
    RecoveryResult recoverToPointInTime(Instant targetTime);

    /* Memulihkan keadaan basis data hingga batas LSN COMMIT dari transaksi tertentu (FR-05) */
    RecoveryResult recoverToTransaction(int targetTxId);

    /* 
    Membaca isi log yang tersimpan, dengan opsi penyaringan berdasarkan ID transaksi (FR-03)
    Jika filterTxId bernilai null, seluruh catatan log akan dikembalikan 
    */
    List<LogRecord> readLogs(Integer filterTxId);
}