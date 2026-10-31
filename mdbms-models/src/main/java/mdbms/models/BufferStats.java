package mdbms.models;

public record BufferStats(int capacity, int usedBlocks, int dirtyBlocks, long hits, long misses){}