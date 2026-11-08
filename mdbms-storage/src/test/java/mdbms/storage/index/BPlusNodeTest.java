package mdbms.storage.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mdbms.models.schema.DataType;
import org.junit.jupiter.api.Test;

// unit test BPlusNode, ngecek operasi key dan pointer serta aturan penuh dan kurang
class BPlusNodeTest {

    private final KeyComparator cmp = new KeyComparator(DataType.INTEGER);

    // helper bikin leaf berisi key berurutan dengan pointer key dikali sepuluh
    private static BPlusNode leafWith(int... keys) {
        BPlusNode node = new BPlusNode(1, true);
        for (int i = 0; i < keys.length; i++) {
            node.insertAt(i, keys[i], keys[i] * 10);
        }
        return node;
    }

    // helper bikin internal dengan anak paling kiri 100 lalu tiap key punya anak kanan 101, 102, dan seterusnya
    private static BPlusNode internalWith(int... keys) {
        BPlusNode node = new BPlusNode(2, false);
        node.setPointer(0, 100);
        for (int i = 0; i < keys.length; i++) {
            node.insertAt(i, keys[i], 101 + i);
        }
        return node;
    }

    // node baru kosong, internal udah punya satu slot pointer, nextLeaf belum nyambung
    @Test
    void newNodeIsEmpty() {
        BPlusNode leaf = new BPlusNode(7, true);
        assertEquals(7, leaf.getBlockNumber());
        assertTrue(leaf.isLeaf());
        assertEquals(0, leaf.keyCount());
        assertEquals(-1, leaf.getNextLeaf());

        BPlusNode internal = new BPlusNode(8, false);
        assertFalse(internal.isLeaf());
        assertEquals(-1, internal.getPointer(0));
    }

    // di leaf key dan pointer selalu sejajar di posisi yang sama
    @Test
    void leafInsertKeepsKeyAndPointerAligned() {
        BPlusNode node = leafWith(10, 30);
        node.insertAt(1, 20, 200);
        assertEquals(3, node.keyCount());
        assertEquals(20, node.getKey(1));
        assertEquals(200, node.getPointer(1));
        assertEquals(300, node.getPointer(2));
    }

    // di internal pointer baru masuk di kanan key nya
    @Test
    void internalInsertPutsPointerRightOfKey() {
        BPlusNode node = internalWith(10, 30);
        node.insertAt(1, 20, 999);
        assertEquals(100, node.getPointer(0));
        assertEquals(101, node.getPointer(1));
        assertEquals(999, node.getPointer(2));
        assertEquals(102, node.getPointer(3));
    }

    // removeAt di leaf hapus pasangan key dan pointer nya
    @Test
    void leafRemoveDropsKeyAndPointer() {
        BPlusNode node = leafWith(10, 20, 30);
        node.removeAt(1);
        assertEquals(2, node.keyCount());
        assertEquals(30, node.getKey(1));
        assertEquals(300, node.getPointer(1));
    }

    // removeAt di internal hapus key dan pointer di kanannya, anak paling kiri tetap
    @Test
    void internalRemoveDropsRightPointer() {
        BPlusNode node = internalWith(10, 20);
        node.removeAt(0);
        assertEquals(1, node.keyCount());
        assertEquals(20, node.getKey(0));
        assertEquals(100, node.getPointer(0));
        assertEquals(102, node.getPointer(1));
    }

    // setKey dan setPointer ganti nilai tanpa geser posisi
    @Test
    void settersReplaceInPlace() {
        BPlusNode node = internalWith(10, 20);
        node.setKey(1, 25);
        node.setPointer(0, 55);
        assertEquals(25, node.getKey(1));
        assertEquals(55, node.getPointer(0));
        assertEquals(2, node.keyCount());
    }

    // findKey balikin posisi kalau ketemu, kalau ga ketemu balikin posisi sisip dalam bentuk negatif
    @Test
    void findKeyReturnsPositionOrInsertionPoint() {
        BPlusNode node = leafWith(10, 20, 30);
        assertEquals(0, node.findKey(10, cmp));
        assertEquals(2, node.findKey(30, cmp));
        assertEquals(-1, node.findKey(5, cmp));
        assertEquals(-3, node.findKey(25, cmp));
        assertEquals(-4, node.findKey(99, cmp));
        assertEquals(-1, new BPlusNode(1, true).findKey(1, cmp));
    }

    // findChild pilih anak yang bener, key yang sama dengan separator masuk ke kanan
    @Test
    void findChildRoutesEqualKeysRight() {
        BPlusNode node = internalWith(10, 20);
        assertEquals(0, node.findChild(5, cmp));
        assertEquals(1, node.findChild(10, cmp));
        assertEquals(1, node.findChild(15, cmp));
        assertEquals(2, node.findChild(20, cmp));
        assertEquals(2, node.findChild(25, cmp));
    }

    // nextLeaf bisa disambung ke leaf lain
    @Test
    void nextLeafCanBeLinked() {
        BPlusNode node = new BPlusNode(1, true);
        node.setNextLeaf(9);
        assertEquals(9, node.getNextLeaf());
    }

    // dengan maxDegree 4 node boleh punya 3 key, key keempat bikin overfull
    @Test
    void overfullAfterMaxKeys() {
        assertFalse(leafWith(1, 2, 3).isOverfull(4));
        assertTrue(leafWith(1, 2, 3, 4).isOverfull(4));
        assertFalse(internalWith(1, 2, 3).isOverfull(4));
        assertTrue(internalWith(1, 2, 3, 4).isOverfull(4));
    }

    // leaf non root minimal setengah kapasitas key, maxDegree 5 artinya minimal 2 key
    @Test
    void leafUnderfullAndLendRules() {
        assertTrue(leafWith(1).isUnderfull(5, false));
        assertFalse(leafWith(1, 2).isUnderfull(5, false));
        assertFalse(leafWith(1, 2).canLend(5));
        assertTrue(leafWith(1, 2, 3).canLend(5));
    }

    // internal non root minimal setengah kapasitas pointer, maxDegree 5 artinya minimal 3 anak atau 2 key
    @Test
    void internalUnderfullAndLendRules() {
        assertTrue(internalWith(1).isUnderfull(5, false));
        assertFalse(internalWith(1, 2).isUnderfull(5, false));
        assertFalse(internalWith(1, 2).canLend(5));
        assertTrue(internalWith(1, 2, 3).canLend(5));
    }

    // root leaf boleh kosong, root internal kurang kalau ga punya key lagi
    @Test
    void rootHasRelaxedRules() {
        assertFalse(new BPlusNode(1, true).isUnderfull(5, true));
        assertFalse(internalWith(1).isUnderfull(5, true));
        assertTrue(new BPlusNode(2, false).isUnderfull(5, true));
    }
}
