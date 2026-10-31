package mdbms.api;

import mdbms.models.BufferStats;

public interface IDurableStorage{
    void flushBuffer();

    BufferStats getBufferStats();
}