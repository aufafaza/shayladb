package mdbms.storage.serializer;

// menyimpan posisi dan kondisi satu slot
public record SlotEntry(int offset, int length, boolean occupied) {}
