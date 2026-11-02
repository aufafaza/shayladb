package mdbms.models.access;

public record BufferStats(int capacity, int usedBlocks, int dirtyBlocks, long hits, long misses){}