package mdbms.concurrency;

/** Dependency boundary owned by the failure-recovery component. */
public interface FailureRecoveryManager {
    boolean writeLog(LogRecord log);

    void recoverSystem();

    void rollbackTransaction(int txId);
}
