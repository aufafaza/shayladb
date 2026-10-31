package mdbms.models;

public enum PhysicalAlgorithm {
    LINEAR_SCAN, 
    INDEX_SCAN, 
    
    NESTED_LOOP_JOIN, 
    BLOCK_NESTED_LOOP_JOIN, 
    INDEX_NESTED_LOOP_JOIN,
    MERGE_JOIN,
    HASH_JOIN,

    EXTERNAL_MERGE_SORT,
    DEFAULT
}
