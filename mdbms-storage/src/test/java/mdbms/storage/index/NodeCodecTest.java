package mdbms.storage.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.storage.block.Block;
import mdbms.storage.block.BlockManager;
import mdbms.storage.exception.StorageErrorCode;
import mdbms.storage.exception.StorageException;
import mdbms.storage.serializer.CharCodec;
import mdbms.storage.serializer.FloatCodec;
import mdbms.storage.serializer.IntegerCodec;
import mdbms.storage.serializer.VarcharCodec;
import org.junit.jupiter.api.Test;

// test NodeCodec bolak balik node ke byte untuk tiap tipe key
class NodeCodecTest {

    private static final ColumnDefinition ID = new ColumnDefinition("id", DataType.INTEGER, 0, false);
    private static final ColumnDefinition GPA = new ColumnDefinition("gpa", DataType.FLOAT, 0, false);
    private static final ColumnDefinition CODE = new ColumnDefinition("code", DataType.CHAR, 5, false);
    private static final ColumnDefinition NAME = new ColumnDefinition("name", DataType.VARCHAR, 10, false);

    // block kosong ukuran standar
    private static Block newBlock(int number) {
        return new Block("idx.idx", number, new byte[BlockManager.BLOCK_SIZE]);
    }

    // tulis node lalu baca balik dari block yang sama
    private static BPlusNode roundTrip(NodeCodec codec, BPlusNode node) {
        Block block = newBlock(node.getBlockNumber());
        codec.write(node, block);
        return codec.read(block);
    }

    // ambil semua key node jadi list biar gampang dibandingin
    private static List<Object> keysOf(BPlusNode node) {
        List<Object> keys = new ArrayList<>();
        for (int i = 0; i < node.keyCount(); i++) keys.add(node.getKey(i));
        return keys;
    }

    // ukuran slot key ngikutin tipe kolom
    @Test
    void keySizeFollowsColumnType() {
        assertEquals(4, new NodeCodec(ID, new IntegerCodec()).keySize());
        assertEquals(4, new NodeCodec(GPA, new FloatCodec()).keySize());
        assertEquals(5, new NodeCodec(CODE, new CharCodec()).keySize());
        assertEquals(12, new NodeCodec(NAME, new VarcharCodec()).keySize());
    }

    // leaf balik utuh termasuk pointer bucket dan nextLeaf
    @Test
    void leafRoundTrip() {
        NodeCodec codec = new NodeCodec(ID, new IntegerCodec());
        BPlusNode leaf = new BPlusNode(4, true);
        leaf.insertAt(0, 10, 100);
        leaf.insertAt(1, 20, 200);
        leaf.setNextLeaf(9);

        BPlusNode read = roundTrip(codec, leaf);

        assertTrue(read.isLeaf());
        assertEquals(4, read.getBlockNumber());
        assertEquals(List.of(10, 20), keysOf(read));
        assertEquals(100, read.getPointer(0));
        assertEquals(200, read.getPointer(1));
        assertEquals(9, read.getNextLeaf());
    }

    // internal node balik utuh dengan pointer satu lebih banyak dari key
    @Test
    void internalRoundTrip() {
        NodeCodec codec = new NodeCodec(GPA, new FloatCodec());
        BPlusNode internal = new BPlusNode(2, false);
        internal.setPointer(0, 5);
        internal.insertAt(0, 2.5f, 6);
        internal.insertAt(1, 3.5f, 7);

        BPlusNode read = roundTrip(codec, internal);

        assertFalse(read.isLeaf());
        assertEquals(List.of(2.5f, 3.5f), keysOf(read));
        assertEquals(5, read.getPointer(0));
        assertEquals(6, read.getPointer(1));
        assertEquals(7, read.getPointer(2));
    }

    // VARCHAR beda panjang dan CHAR tetap kebaca benar dari slot tetap
    @Test
    void textKeysRoundTrip() {
        NodeCodec varchar = new NodeCodec(NAME, new VarcharCodec());
        BPlusNode names = new BPlusNode(1, true);
        names.insertAt(0, "", 1);
        names.insertAt(1, "bob", 2);
        names.insertAt(2, "jonathanxy", 3);
        assertEquals(List.of("", "bob", "jonathanxy"), keysOf(roundTrip(varchar, names)));

        NodeCodec chars = new NodeCodec(CODE, new CharCodec());
        BPlusNode codes = new BPlusNode(1, true);
        codes.insertAt(0, "IF", 1);
        codes.insertAt(1, "STI12", 2);
        assertEquals(List.of("IF", "STI12"), keysOf(roundTrip(chars, codes)));
    }

    // node kosong juga bisa ditulis, dipakai buat root leaf awal
    @Test
    void emptyLeafRoundTrip() {
        NodeCodec codec = new NodeCodec(ID, new IntegerCodec());
        BPlusNode read = roundTrip(codec, new BPlusNode(1, true));

        assertTrue(read.isLeaf());
        assertEquals(0, read.keyCount());
        assertEquals(-1, read.getNextLeaf());
    }

    // key yang kepanjangan dalam byte ditolak sebagai SCHEMA_VIOLATION
    @Test
    void rejectsKeyLongerThanSlot() {
        NodeCodec codec = new NodeCodec(CODE, new CharCodec());
        BPlusNode leaf = new BPlusNode(1, true);
        leaf.insertAt(0, "ééé", 1);

        StorageException e = assertThrows(StorageException.class, () -> codec.write(leaf, newBlock(1)));
        assertEquals(StorageErrorCode.SCHEMA_VIOLATION, e.getCode());
    }

    // node yang ga muat satu block ditolak
    @Test
    void rejectsNodeTooLargeForBlock() {
        NodeCodec codec = new NodeCodec(ID, new IntegerCodec());
        BPlusNode leaf = new BPlusNode(1, true);
        for (int i = 0; i < 600; i++) leaf.insertAt(i, i, i);

        StorageException e = assertThrows(StorageException.class, () -> codec.write(leaf, newBlock(1)));
        assertEquals(StorageErrorCode.INVALID_BLOCK, e.getCode());
    }

    // header rusak dan kolom teks tanpa panjang ditolak
    @Test
    void rejectsCorruptBlockAndBadColumn() {
        NodeCodec codec = new NodeCodec(ID, new IntegerCodec());
        Block badKind = newBlock(1);
        ByteBuffer.wrap(badKind.data).putInt(0, 7);
        Block badCount = newBlock(1);
        ByteBuffer.wrap(badCount.data).putInt(0, 1).putInt(4, 5000);

        assertEquals(StorageErrorCode.INVALID_BLOCK, assertThrows(StorageException.class, () -> codec.read(badKind)).getCode());
        assertEquals(StorageErrorCode.INVALID_BLOCK, assertThrows(StorageException.class, () -> codec.read(badCount)).getCode());
        assertThrows(StorageException.class,
                () -> new NodeCodec(new ColumnDefinition("x", DataType.VARCHAR, 0, false), new VarcharCodec()));
    }
}
