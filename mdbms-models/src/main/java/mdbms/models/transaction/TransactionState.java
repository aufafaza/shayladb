package mdbms.models.transaction;

public enum TransactionState{ 
    ACTIVE, 
    PARTIALLY_COMMITTED, 
    FAILED, 
    ABORTED, 
    COMMITTED,
}