package mdbms.models;

public enum TransactionState{ 
    ACTIVE, 
    PARTIALLY_COMMITTED, 
    FAILED, 
    ABORTED, 
    COMMITTED,
}