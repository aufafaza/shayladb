package mdbms.api;

import mdbms.models.access.BufferStats;

public interface IDurableStorage{
    void flushBuffer();

    BufferStats getBufferStats();
}