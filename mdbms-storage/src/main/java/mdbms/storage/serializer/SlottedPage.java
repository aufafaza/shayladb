package mdbms.storage.serializer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class SlottedPage {
    public static final int PAGE_SIZE = 4096;
    private static final int MAGIC = 0x534D3350; // penanda halaman (SM3P)
    private static final int VERSION = 1;
    private static final int HEADER_SIZE = 16;
    private static final int SLOT_SIZE = 12;
    private static final int DELETED = 0;
    private static final int RESERVED = 1;
    private static final int OCCUPIED = 2;

    private final List<byte[]> records = new ArrayList<>();
    private final List<Integer> reservations = new ArrayList<>();

    public SlottedPage() {}

    // membuat halaman dari data biner
    public SlottedPage(byte[] bytes) {
        if (bytes == null || bytes.length != PAGE_SIZE) {
            throw new IllegalArgumentException("Page must contain exactly 4096 bytes");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        if (buffer.getInt() != MAGIC || buffer.getInt() != VERSION) {
            throw new IllegalArgumentException("Invalid page magic or version");
        }
        int count = buffer.getInt();
        int payloadStart = buffer.getInt();
        if (count < 0 || count > (PAGE_SIZE - HEADER_SIZE) / SLOT_SIZE) {
            throw new IllegalArgumentException("Invalid slot count");
        }
        int directoryEnd = HEADER_SIZE + count * SLOT_SIZE;
        if (payloadStart < directoryEnd || payloadStart > PAGE_SIZE) {
            throw new IllegalArgumentException("Invalid payload boundary");
        }
        boolean[] used = new boolean[PAGE_SIZE];
        int occupiedBytes = 0;
        long reservedBytes = 0;
        for (int i = 0; i < count; i++) {
            int offset = buffer.getInt();
            int length = buffer.getInt();
            int state = buffer.getInt();
            if (state == DELETED && offset == 0 && length == 0) {
                records.add(null);
                reservations.add(0);
            } else if (state == RESERVED && offset == -1 && length > 0) {
                records.add(null);
                reservations.add(length);
                reservedBytes += length;
            } else if (state == OCCUPIED && length > 0 && offset >= payloadStart
                    && (long) offset + length <= PAGE_SIZE) {
                for (int at = offset; at < offset + length; at++) {
                    if (used[at]) {
                        throw new IllegalArgumentException("Overlapping records");
                    }
                    used[at] = true;
                }
                records.add(Arrays.copyOfRange(bytes, offset, offset + length));
                reservations.add(0);
                occupiedBytes += length;
            } else {
                throw new IllegalArgumentException("Invalid slot entry");
            }
        }
        if (occupiedBytes != PAGE_SIZE - payloadStart
                || (long) directoryEnd + occupiedBytes + reservedBytes > PAGE_SIZE) {
            throw new IllegalArgumentException("Invalid page space accounting");
        }
    }

    public boolean hasSpace(int recordLength) {
        if (recordLength <= 0) {
            throw new IllegalArgumentException("Record length must be positive");
        }
        return usedBytes() + SLOT_SIZE + recordLength <= PAGE_SIZE;
    }

    public int slotCount() {
        return records.size();
    }

    public boolean isOccupied(int slot) {
        checkSlot(slot);
        return records.get(slot) != null;
    }

    // menyiapkan slot kosong buat record baru
    public int reserveSlot(int recordLength) {
        if (!hasSpace(recordLength)) {
            throw new IllegalStateException("Page is full");
        }
        int slot = records.size();
        records.add(null);
        reservations.add(recordLength);
        return slot;
    }

    // menyiapkan slot tertentu buat record baru
    public boolean reserveSlotAt(int slot, int length) {
        checkSlot(slot);
        if (length <= 0) {
            throw new IllegalArgumentException("Record length must be positive");
        }
        if (records.get(slot) != null || reservations.get(slot) != 0
                || usedBytes() + length > PAGE_SIZE) {
            return false;
        }
        reservations.set(slot, length);
        return true;
    }

    public void writeSlot(int slot, byte[] data) {
        checkSlot(slot);
        if (reservations.get(slot) == 0 || records.get(slot) != null) {
            throw new IllegalStateException("Slot is not reserved");
        }
        if (data == null || data.length == 0 || data.length > reservations.get(slot)) {
            throw new IllegalArgumentException("Record exceeds reserved length");
        }
        records.set(slot, data.clone());
        reservations.set(slot, 0);
    }

    // mengganti record aktif tanpa mengubah nomor slotnya
    public boolean replaceRecord(int slot, byte[] data) {
        checkSlot(slot);
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Record data must not be empty");
        }
        byte[] previous = records.get(slot);
        if (previous == null) {
            throw new IllegalStateException("Slot contains no active record");
        }
        if (usedBytes() - previous.length + data.length > PAGE_SIZE) {
            return false;
        }
        records.set(slot, data.clone());
        return true;
    }

    public byte[] readRecord(int slot) {
        checkSlot(slot);
        byte[] record = records.get(slot);
        if (record == null) {
            throw new IllegalStateException("Slot contains no record");
        }
        return record.clone();
    }

    public void deleteRecord(int slot) {
        checkSlot(slot);
        if (records.get(slot) == null) {
            throw new IllegalStateException("Slot contains no record");
        }
        records.set(slot, null);
    }

    // melihat posisi dan kondisi sebuah slot
    public SlotEntry slotEntry(int slot) {
        checkSlot(slot);
        byte[] record = records.get(slot);
        if (record == null) {
            return reservations.get(slot) > 0
                    ? new SlotEntry(-1, reservations.get(slot), false)
                    : new SlotEntry(0, 0, false);
        }
        int offset = PAGE_SIZE;
        for (int i = 0; i <= slot; i++) {
            if (records.get(i) != null) {
                offset -= records.get(i).length;
            }
        }
        return new SlotEntry(offset, record.length, true);
    }

    // menyusun seluruh halaman jadi data biner
    public byte[] toBytes() {
        byte[] page = new byte[PAGE_SIZE];
        ByteBuffer buffer = ByteBuffer.wrap(page);
        int offset = PAGE_SIZE;
        for (byte[] record : records) {
            if (record != null) {
                offset -= record.length;
            }
        }
        buffer.putInt(MAGIC).putInt(VERSION).putInt(records.size()).putInt(offset);
        int nextOffset = PAGE_SIZE;
        for (int i = 0; i < records.size(); i++) {
            byte[] record = records.get(i);
            if (record != null) {
                nextOffset -= record.length;
                buffer.putInt(nextOffset).putInt(record.length).putInt(OCCUPIED);
                System.arraycopy(record, 0, page, nextOffset, record.length);
            } else if (reservations.get(i) > 0) {
                buffer.putInt(-1).putInt(reservations.get(i)).putInt(RESERVED);
            } else {
                buffer.putInt(0).putInt(0).putInt(DELETED);
            }
        }
        return page;
    }

    // menghitung ruang yang dipakai direktori record dan reservasi
    private long usedBytes() {
        long used = HEADER_SIZE + (long) records.size() * SLOT_SIZE;
        for (int i = 0; i < records.size(); i++) {
            used += records.get(i) == null ? reservations.get(i) : records.get(i).length;
        }
        return used;
    }

    private void checkSlot(int slot) {
        if (slot < 0 || slot >= records.size()) {
            throw new IllegalArgumentException("Invalid slot " + slot);
        }
    }
}
