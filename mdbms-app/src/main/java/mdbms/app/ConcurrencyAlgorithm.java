package mdbms.app;

public enum ConcurrencyAlgorithm {
    LOCK_BASED,
    TIMESTAMP_BASED,
    VALIDATION_BASED; 
}
