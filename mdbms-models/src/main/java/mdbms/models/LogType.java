package mdbms.models;

public enum LogType {
    BEGIN,
    COMMIT,
    ABORT,
    INSERT,
    UPDATE,
    DELETE,
    CHECKPOINT
}
