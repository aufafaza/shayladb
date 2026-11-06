package mdbms.storage.index;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// satu node B+Tree di memori, nanti dibaca dan ditulis ke block lewat NodeCodec
// leaf nyimpen key dan block RidBucket nya, internal nyimpen key dan pointer anak yang jumlahnya selalu satu lebih banyak
public final class BPlusNode {

    // penanda ga ada block, dipakai buat nextLeaf terakhir dan pointer anak yang belum diisi
    private static final int NO_BLOCK = -1;

    private final int blockNumber;
    private final boolean leaf;
    private final List<Object> keys = new ArrayList<>();
    private final List<Integer> pointers = new ArrayList<>();
    private int nextLeaf = NO_BLOCK;

    // bikin node kosong, internal langsung punya satu slot pointer paling kiri yang belum diisi
    public BPlusNode(int blockNumber, boolean leaf) {
        this.blockNumber = blockNumber;
        this.leaf = leaf;
        if (!leaf) {
            pointers.add(NO_BLOCK);
        }
    }

    // nomor block tempat node ini disimpan di file index
    public int getBlockNumber() {
        return blockNumber;
    }

    public boolean isLeaf() {
        return leaf;
    }

    // jumlah key yang lagi disimpan
    public int keyCount() {
        return keys.size();
    }

    // ambil key di posisi i
    public Object getKey(int i) {
        return keys.get(i);
    }

    // di leaf ini block RidBucket punya key i, di internal ini block anak ke i
    public int getPointer(int i) {
        return pointers.get(i);
    }

    // ganti key di posisi i, dipakai pas separator di parent berubah
    public void setKey(int i, Object key) {
        keys.set(i, key);
    }

    // ganti pointer di posisi i, dipakai buat ngisi atau geser anak paling kiri di internal
    public void setPointer(int i, int pointer) {
        pointers.set(i, pointer);
    }

    // sisipin key di posisi i, di leaf pointernya ikut di posisi i
    // di internal pointernya masuk di kanan key itu, pas buat naro anak kanan hasil split
    public void insertAt(int i, Object key, int pointer) {
        keys.add(i, key);
        pointers.add(leaf ? i : i + 1, pointer);
    }

    // hapus key di posisi i, di leaf pointernya ikut kehapus, di internal yang kehapus pointer di kanan key itu
    public void removeAt(int i) {
        keys.remove(i);
        pointers.remove(leaf ? i : i + 1);
    }

    // binary search key, balikin posisinya kalau ketemu
    // kalau ga ketemu balikin minus posisi sisip dikurang satu, sama kayak Collections binarySearch
    public int findKey(Object key, KeyComparator cmp) {
        return Collections.binarySearch(keys, key, cmp);
    }

    // posisi anak yang harus dikunjungi buat key ini, yaitu jumlah key yang lebih kecil atau sama
    // key yang sama dengan separator masuk ke kanan karena separator itu key pertama di anak kanan
    public int findChild(Object key, KeyComparator cmp) {
        int pos = findKey(key, cmp);
        return pos >= 0 ? pos + 1 : -(pos + 1);
    }

    // block leaf berikutnya buat range scan, minus satu kalau ini leaf terakhir
    public int getNextLeaf() {
        return nextLeaf;
    }

    // sambungin leaf ini ke leaf sebelah kanannya
    public void setNextLeaf(int blockNumber) {
        nextLeaf = blockNumber;
    }

    // true kalau key kebanyakan dan harus di split, maxDegree itu jumlah pointer maksimal jadi key maksimalnya maxDegree kurang satu
    public boolean isOverfull(int maxDegree) {
        return keys.size() > maxDegree - 1;
    }

    // true kalau key kurang dari batas minimal setelah delete
    // root leaf boleh kosong, root internal dianggap kurang kalau tinggal satu anak biar tingginya bisa turun
    public boolean isUnderfull(int maxDegree, boolean isRoot) {
        if (isRoot) {
            return !leaf && keys.isEmpty();
        }
        return keys.size() < minKeys(maxDegree);
    }

    // true kalau node masih aman setelah minjemin satu key ke sibling
    public boolean canLend(int maxDegree) {
        return keys.size() - 1 >= minKeys(maxDegree);
    }

    // key minimal node non root, leaf setengah kapasitas key, internal setengah kapasitas pointer dikurang satu
    private int minKeys(int maxDegree) {
        return leaf ? maxDegree / 2 : (maxDegree + 1) / 2 - 1;
    }
}
