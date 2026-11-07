package mdbms.storage.index;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.storage.block.Block;
import mdbms.storage.block.BlockManager;
import mdbms.storage.exception.StorageErrorCode;
import mdbms.storage.exception.StorageException;
import mdbms.storage.serializer.ValueCodec;

// ubah BPlusNode jadi byte block dan sebaliknya, key di encode pakai codec SM3 dengan ukuran slot tetap
// layoutnya header leaf, keyCount, nextLeaf masing masing 4 byte, lalu semua key, lalu semua pointer 4 byte
public final class NodeCodec {
    public static final int HEADER_SIZE = 12;

    private static final int POINTER_SIZE = 4;
    private static final int LEAF = 1;
    private static final int INTERNAL = 0;

    private final ColumnDefinition column;
    private final ValueCodec keyCodec;
    private final int keySize;

    // simpan kolom dan codec key, ukuran slot key langsung dihitung dari tipe kolom
    public NodeCodec(ColumnDefinition column, ValueCodec keyCodec) {
        this.column = Objects.requireNonNull(column, "column");
        this.keyCodec = Objects.requireNonNull(keyCodec, "keyCodec");
        this.keySize = slotSize(column);
    }

    // baca isi block jadi node, header atau jumlah key yang ga masuk akal dianggap block rusak
    public BPlusNode read(Block block) {
        ByteBuffer buffer = ByteBuffer.wrap(block.data);
        int kind = buffer.getInt(0);
        int keyCount = buffer.getInt(4);
        if (kind != LEAF && kind != INTERNAL) {
            throw corrupt(block, "unknown node kind " + kind);
        }
        boolean leaf = kind == LEAF;
        if (keyCount < 0 || bytesNeeded(keyCount, leaf) > BlockManager.BLOCK_SIZE) {
            throw corrupt(block, "invalid key count " + keyCount);
        }

        BPlusNode node = new BPlusNode(block.blockNumber, leaf);
        node.setNextLeaf(buffer.getInt(8));
        int pointerStart = HEADER_SIZE + keyCount * keySize;
        if (!leaf) {
            node.setPointer(0, buffer.getInt(pointerStart));
            pointerStart += POINTER_SIZE;
        }
        for (int i = 0; i < keyCount; i++) {
            Object key = decodeKey(block, HEADER_SIZE + i * keySize);
            node.insertAt(i, key, buffer.getInt(pointerStart + i * POINTER_SIZE));
        }
        return node;
    }

    // tulis node ke block, ditolak kalau node kegedean buat satu block
    public void write(BPlusNode node, Block block) {
        int keyCount = node.keyCount();
        if (bytesNeeded(keyCount, node.isLeaf()) > BlockManager.BLOCK_SIZE) {
            throw new StorageException(StorageErrorCode.INVALID_BLOCK,
                    "Node with " + keyCount + " keys does not fit in block " + block.blockNumber);
        }
        Arrays.fill(block.data, (byte) 0);
        ByteBuffer buffer = ByteBuffer.wrap(block.data);
        buffer.putInt(0, node.isLeaf() ? LEAF : INTERNAL);
        buffer.putInt(4, keyCount);
        buffer.putInt(8, node.getNextLeaf());

        for (int i = 0; i < keyCount; i++) {
            byte[] encoded = keyCodec.encode(node.getKey(i), column);
            System.arraycopy(encoded, 0, block.data, HEADER_SIZE + i * keySize, encoded.length);
        }
        int pointerStart = HEADER_SIZE + keyCount * keySize;
        int pointerCount = node.isLeaf() ? keyCount : keyCount + 1;
        for (int i = 0; i < pointerCount; i++) {
            buffer.putInt(pointerStart + i * POINTER_SIZE, node.getPointer(i));
        }
    }

    // ukuran satu slot key dalam byte
    public int keySize() {
        return keySize;
    }

    // ukuran slot per tipe, INTEGER dan FLOAT 4, CHAR sepanjang kolom, VARCHAR ditambah 2 byte panjang, dipakai juga computeMaxDegree
    static int slotSize(ColumnDefinition column) {
        DataType type = Objects.requireNonNull(column.type(), "column type");
        return switch (type) {
            case INTEGER, FLOAT -> 4;
            case CHAR -> positiveLength(column);
            case VARCHAR -> 2 + positiveLength(column);
        };
    }

    // CHAR dan VARCHAR wajib punya panjang positif biar slotnya bisa dihitung
    private static int positiveLength(ColumnDefinition column) {
        if (column.length() <= 0) {
            throw new StorageException(StorageErrorCode.SCHEMA_VIOLATION,
                    "Indexed column " + column.name() + " needs a positive length");
        }
        return column.length();
    }

    // potong slot key sesuai format codec, VARCHAR cuma diambil sepanjang prefix panjangnya
    // error dari codec SM3 udah berupa StorageException jadi langsung diterusin
    private Object decodeKey(Block block, int offset) {
        int length = keySize;
        if (column.type() == DataType.VARCHAR) {
            length = 2 + Short.toUnsignedInt(ByteBuffer.wrap(block.data).getShort(offset));
            if (length > keySize) {
                throw corrupt(block, "VARCHAR key longer than its slot");
            }
        }
        return keyCodec.decode(Arrays.copyOfRange(block.data, offset, offset + length), column);
    }

    // total byte header, key, dan pointer buat node dengan jumlah key segini
    private long bytesNeeded(int keyCount, boolean leaf) {
        long pointers = leaf ? keyCount : keyCount + 1L;
        return HEADER_SIZE + (long) keyCount * keySize + pointers * POINTER_SIZE;
    }

    // exception seragam buat block index yang isinya rusak
    private static StorageException corrupt(Block block, String reason) {
        return new StorageException(StorageErrorCode.INVALID_BLOCK,
                "Corrupt index block " + block.blockNumber + ", " + reason);
    }
}
