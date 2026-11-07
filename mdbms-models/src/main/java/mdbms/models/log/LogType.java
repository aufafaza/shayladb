package mdbms.models.log;

public enum LogType {
    BEGIN,
    COMMIT,
    ABORT,
    INSERT,
    UPDATE,
    DELETE,
    CHECKPOINT
}
