package mdbms.storage.exception;


public enum StorageErrorCode {
    INVALID_BLOCK,
    BUFFER_FULL,
    INVALID_PIN,
    IO_FAILURE,
    WAL_FLUSH_FAILED
}
