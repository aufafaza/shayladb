package mdbms.storage.index;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import mdbms.models.data.RowId;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.IndexInfo;
import mdbms.storage.block.Block;
import mdbms.storage.block.BlockManager;
import mdbms.storage.block.BufferPool;
import mdbms.storage.exception.StorageErrorCode;
import mdbms.storage.exception.StorageException;

// index B+Tree non unique di satu file, leaf nyimpen key dan block RidBucket pertama dari key itu
// block 0 isinya meta, block lain node atau bucket, block yang dibebasin disambung jadi free list
public final class BPlusTreeIndex implements Index {
    private static final int META_BLOCK = 0;
    private static final int FIRST_ROOT = 1;
    private static final int NO_BLOCK = -1;
    private static final int META_MAGIC = 0x534D3549;
    private static final int MIN_DEGREE = 3;
    private static final int POINTER_SIZE = 4;
    // TODO ganti pakai LSN dari FRM kalau alur WAL di StorageManager udah ada, sama kayak HeapFile
    private static final long NO_LSN = 0;

    private final IndexInfo info;
    private final ColumnDefinition column;
    private int maxDegree;
    private final String fileName;
    private int rootBlock;
    private int height;
    private int freeListHead;
    private final BufferPool pool;
    private final KeyNormalizer normalizer;
    private final KeyComparator comparator;
    private final NodeCodec nodeCodec;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    // siapin index, kalau file nya udah ada meta langsung dibaca biar index lama kepake lagi
    public BPlusTreeIndex(IndexInfo info, ColumnDefinition column, int maxDegree, BufferPool pool,
            KeyNormalizer normalizer, NodeCodec codec) {
        this.info = Objects.requireNonNull(info, "info");
        this.column = Objects.requireNonNull(column, "column");
        this.pool = Objects.requireNonNull(pool, "pool");
        this.normalizer = Objects.requireNonNull(normalizer, "normalizer");
        this.nodeCodec = Objects.requireNonNull(codec, "codec");
        this.comparator = new KeyComparator(column.type());
        this.fileName = fileNameOf(info);
        int limit = computeMaxDegree(column);
        if (maxDegree < MIN_DEGREE || maxDegree > limit) {
            throw new StorageException(StorageErrorCode.SCHEMA_VIOLATION,
                    "maxDegree must be between " + MIN_DEGREE + " and " + limit + " for column " + column.name());
        }
        this.maxDegree = maxDegree;
        if (pool.blockCount(fileName) > 0) readMeta();
    }

    // nama file tempat index ini disimpan di direktori data
    public static String fileNameOf(IndexInfo info) {
        return info.indexName() + ".idx";
    }

    // jumlah pointer maksimal per node biar node terbesar masih muat satu block
    // ditolak kalau hasilnya di bawah 3 karena B+Tree butuh minimal 3 pointer
    public static int computeMaxDegree(ColumnDefinition column) {
        int keySize = NodeCodec.slotSize(column);
        int degree = (BlockManager.BLOCK_SIZE - NodeCodec.HEADER_SIZE + keySize) / (keySize + POINTER_SIZE);
        if (degree < MIN_DEGREE) {
            throw new StorageException(StorageErrorCode.SCHEMA_VIOLATION,
                    "Column " + column.name() + " is too wide to be indexed");
        }
        return degree;
    }

    // bikin file index berisi meta dan root leaf kosong, file lama dengan nama sama ditimpa
    @Override
    public void create() {
        lock.writeLock().lock();
        try {
            while (pool.blockCount(fileName) <= FIRST_ROOT) {
                Block block = pool.appendBlock(fileName);
                pool.unpin(block, false, NO_LSN);
            }
            clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    // daftarin RowId di bawah key, key null ga diindex, key baru dapet bucket baru dan bisa bikin split
    @Override
    public void insert(Object key, RowId rowId) {
        checkTable(rowId);
        Object normalized = normalizer.normalize(key, column);
        if (normalized == null) return;
        lock.writeLock().lock();
        try {
            Deque<BPlusNode> path = new ArrayDeque<>();
            BPlusNode leaf = findLeaf(normalized, path);
            int pos = leaf.findKey(normalized, comparator);
            if (pos >= 0) {
                addToBucketChain(leaf.getPointer(pos), rowId);
                return;
            }
            int bucketBlock = newBucket(rowId);
            insertInLeaf(leaf, normalized, bucketBlock);
            if (leaf.isOverfull(maxDegree)) {
                BPlusNode right = splitLeaf(leaf);
                insertInParent(leaf, right.getKey(0), right, path);
            } else {
                writeNode(leaf);
            }
            writeMeta();
        } finally {
            lock.writeLock().unlock();
        }
    }

    // hapus RowId dari bucket key ini, kalau bucket nya habis key ikut dihapus dari leaf
    // pasangan yang ga ada atau key null diabaikan
    @Override
    public void delete(Object key, RowId rowId) {
        checkTable(rowId);
        Object normalized = normalizer.normalize(key, column);
        if (normalized == null) return;
        lock.writeLock().lock();
        try {
            Deque<BPlusNode> path = new ArrayDeque<>();
            BPlusNode leaf = findLeaf(normalized, path);
            int pos = leaf.findKey(normalized, comparator);
            if (pos < 0) return;
            int firstBlock = leaf.getPointer(pos);
            if (removeFromBucketChain(firstBlock, rowId)) {
                freeNode(firstBlock);
                deleteEntry(leaf, normalized, firstBlock, path);
            }
            writeMeta();
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ambil semua RowId buat key ini, key null ga pernah diindex jadi hasilnya kosong
    @Override
    public List<RowId> search(Object key) {
        Object normalized = normalizer.normalize(key, column);
        List<RowId> result = new ArrayList<>();
        if (normalized == null) return result;
        lock.readLock().lock();
        try {
            BPlusNode leaf = findLeaf(normalized, new ArrayDeque<>());
            int pos = leaf.findKey(normalized, comparator);
            if (pos >= 0) collectBucketChain(leaf.getPointer(pos), result);
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    // mulai dari leaf tempat low lalu jalan ke kanan lewat nextLeaf sampai lewat high
    @Override
    public List<RowId> searchRange(Object low, Object high, boolean lowInclusive, boolean highInclusive) {
        Object from = normalizer.normalize(low, column);
        Object to = normalizer.normalize(high, column);
        List<RowId> result = new ArrayList<>();
        if (from != null && to != null && comparator.compare(from, to) > 0) return result;
        lock.readLock().lock();
        try {
            BPlusNode leaf = findLeaf(from, new ArrayDeque<>());
            int i = 0;
            if (from != null) {
                int pos = leaf.findKey(from, comparator);
                i = pos >= 0 ? (lowInclusive ? pos : pos + 1) : -(pos + 1);
            }
            while (true) {
                for (; i < leaf.keyCount(); i++) {
                    if (to != null) {
                        int cmp = comparator.compare(leaf.getKey(i), to);
                        if (cmp > 0 || (cmp == 0 && !highInclusive)) return result;
                    }
                    collectBucketChain(leaf.getPointer(i), result);
                }
                if (leaf.getNextLeaf() == NO_BLOCK) return result;
                leaf = readNode(leaf.getNextLeaf());
                i = 0;
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    // balikin index ke kondisi kosong, semua block selain meta dan root masuk free list buat dipakai ulang
    @Override
    public void clear() {
        lock.writeLock().lock();
        try {
            rootBlock = FIRST_ROOT;
            height = 1;
            freeListHead = NO_BLOCK;
            for (int block = pool.blockCount(fileName) - 1; block > FIRST_ROOT; block--) {
                freeNode(block);
            }
            writeNode(new BPlusNode(FIRST_ROOT, true));
            writeMeta();
        } finally {
            lock.writeLock().unlock();
        }
    }

    // hapus file index beserta frame nya di buffer, index ini ga bisa dipakai lagi setelahnya
    @Override
    public void destroy() {
        lock.writeLock().lock();
        try {
            pool.discardFile(fileName);
            rootBlock = NO_BLOCK;
            height = 0;
            freeListHead = NO_BLOCK;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // info index ini
    @Override
    public IndexInfo getInfo() {
        return info;
    }

    // jumlah pointer maksimal per node yang dipakai index ini
    public int getMaxDegree() {
        return maxDegree;
    }

    // tinggi pohon, root leaf dihitung 1
    public int getHeight() {
        lock.readLock().lock();
        try {
            return height;
        } finally {
            lock.readLock().unlock();
        }
    }

    // turun dari root ke leaf yang mungkin berisi key, node yang dilewatin disimpan di path buat split nanti
    // key null artinya minus tak hingga jadi selalu ambil anak paling kiri
    private BPlusNode findLeaf(Object key, Deque<BPlusNode> path) {
        BPlusNode node = readNode(rootBlock);
        while (!node.isLeaf()) {
            path.push(node);
            int child = key == null ? 0 : node.findChild(key, comparator);
            node = readNode(node.getPointer(child));
        }
        return node;
    }

    // sisipin key baru ke leaf di posisi urutnya, cuma di memori dan belum ditulis
    private void insertInLeaf(BPlusNode leaf, Object key, int bucketBlock) {
        int pos = leaf.findKey(key, comparator);
        leaf.insertAt(-(pos + 1), key, bucketBlock);
    }

    // daftarin pemisah baru ke parent, kalau parent ikut penuh dia di split dan naik terus ke atas
    // kalau yang di split root maka dibikin root baru dan tinggi pohon nambah satu
    private void insertInParent(BPlusNode left, Object key, BPlusNode right, Deque<BPlusNode> path) {
        if (path.isEmpty()) {
            BPlusNode root = allocateNode(false);
            root.setPointer(0, left.getBlockNumber());
            root.insertAt(0, key, right.getBlockNumber());
            writeNode(root);
            rootBlock = root.getBlockNumber();
            height++;
            return;
        }
        BPlusNode parent = path.pop();
        parent.insertAt(childIndex(parent, left.getBlockNumber()), key, right.getBlockNumber());
        if (!parent.isOverfull(maxDegree)) {
            writeNode(parent);
            return;
        }
        InternalSplit split = splitInternal(parent);
        insertInParent(parent, split.separator(), split.right(), path);
    }

    // belah leaf yang penuh, kiri nyimpen setengah ke atas dan key pertama kanan di copy ke parent
    private BPlusNode splitLeaf(BPlusNode leaf) {
        BPlusNode right = allocateNode(true);
        int count = leaf.keyCount();
        int mid = (count + 1) / 2;
        for (int i = mid; i < count; i++) {
            right.insertAt(i - mid, leaf.getKey(i), leaf.getPointer(i));
        }
        for (int i = count - 1; i >= mid; i--) {
            leaf.removeAt(i);
        }
        right.setNextLeaf(leaf.getNextLeaf());
        leaf.setNextLeaf(right.getBlockNumber());
        writeNode(right);
        writeNode(leaf);
        return right;
    }

    // belah internal node yang penuh, key tengah dipindah naik ke parent dan ga disimpan di dua anak
    private InternalSplit splitInternal(BPlusNode node) {
        BPlusNode right = allocateNode(false);
        int count = node.keyCount();
        int mid = count / 2;
        Object separator = node.getKey(mid);
        right.setPointer(0, node.getPointer(mid + 1));
        for (int i = mid + 1; i < count; i++) {
            right.insertAt(i - mid - 1, node.getKey(i), node.getPointer(i + 1));
        }
        for (int i = count - 1; i >= mid; i--) {
            node.removeAt(i);
        }
        writeNode(right);
        writeNode(node);
        return new InternalSplit(separator, right);
    }

    // RowId dari tabel lain ditolak di awal biar ga ada block yang kebuang
    private void checkTable(RowId rowId) {
        Objects.requireNonNull(rowId, "rowId");
        if (!info.tableName().equals(rowId.tableName())) {
            throw new StorageException(StorageErrorCode.SCHEMA_VIOLATION,
                    "RowId of table " + rowId.tableName() + " does not belong to index " + info.indexName());
        }
    }

    // hapus key dan pointer dari node, root internal yang tinggal satu anak diganti anaknya
    // node non root yang kurang isi pinjam dari saudara kalau bisa, kalau ga bisa digabung
    private void deleteEntry(BPlusNode node, Object key, int pointer, Deque<BPlusNode> path) {
        node.removeAt(entryIndex(node, key, pointer));
        if (node.getBlockNumber() == rootBlock) {
            if (!node.isLeaf() && node.keyCount() == 0) {
                rootBlock = node.getPointer(0);
                height--;
                freeNode(node.getBlockNumber());
            } else {
                writeNode(node);
            }
            return;
        }
        if (!node.isUnderfull(maxDegree, false)) {
            writeNode(node);
            return;
        }
        BPlusNode parent = path.pop();
        int index = childIndex(parent, node.getBlockNumber());
        boolean nodeIsLeft = index == 0;
        int separatorIndex = nodeIsLeft ? index : index - 1;
        BPlusNode sibling = readNode(parent.getPointer(nodeIsLeft ? index + 1 : index - 1));
        Object separator = parent.getKey(separatorIndex);
        if (sibling.canLend(maxDegree)) {
            redistribute(node, sibling, separator, parent, nodeIsLeft);
        } else {
            coalesce(node, sibling, separator, parent, path);
        }
    }

    // gabung node kanan ke node kiri, pemisah di parent ikut turun kalau internal lalu dihapus dari parent
    private void coalesce(BPlusNode node, BPlusNode sibling, Object separator, BPlusNode parent, Deque<BPlusNode> path) {
        boolean nodeIsLeft = childIndex(parent, node.getBlockNumber()) < childIndex(parent, sibling.getBlockNumber());
        BPlusNode left = nodeIsLeft ? node : sibling;
        BPlusNode right = nodeIsLeft ? sibling : node;
        if (left.isLeaf()) {
            for (int i = 0; i < right.keyCount(); i++) {
                left.insertAt(left.keyCount(), right.getKey(i), right.getPointer(i));
            }
            left.setNextLeaf(right.getNextLeaf());
        } else {
            left.insertAt(left.keyCount(), separator, right.getPointer(0));
            for (int i = 0; i < right.keyCount(); i++) {
                left.insertAt(left.keyCount(), right.getKey(i), right.getPointer(i + 1));
            }
        }
        writeNode(left);
        freeNode(right.getBlockNumber());
        deleteEntry(parent, separator, right.getBlockNumber(), path);
    }

    // pindahin satu entri dari saudara ke node yang kurang isi lalu benerin pemisah di parent
    // leaf mindahin key apa adanya, internal muter lewat parent biar urutannya tetap benar
    private void redistribute(BPlusNode node, BPlusNode sibling, Object separator, BPlusNode parent, boolean nodeIsLeft) {
        int separatorIndex = childIndex(parent, (nodeIsLeft ? node : sibling).getBlockNumber());
        if (nodeIsLeft) {
            if (node.isLeaf()) {
                node.insertAt(node.keyCount(), sibling.getKey(0), sibling.getPointer(0));
                sibling.removeAt(0);
                parent.setKey(separatorIndex, sibling.getKey(0));
            } else {
                Object raised = sibling.getKey(0);
                node.insertAt(node.keyCount(), separator, sibling.getPointer(0));
                sibling.setPointer(0, sibling.getPointer(1));
                sibling.removeAt(0);
                parent.setKey(separatorIndex, raised);
            }
        } else {
            int last = sibling.keyCount() - 1;
            if (node.isLeaf()) {
                node.insertAt(0, sibling.getKey(last), sibling.getPointer(last));
                sibling.removeAt(last);
                parent.setKey(separatorIndex, node.getKey(0));
            } else {
                Object raised = sibling.getKey(last);
                int moved = sibling.getPointer(last + 1);
                sibling.removeAt(last);
                node.insertAt(0, separator, node.getPointer(0));
                node.setPointer(0, moved);
                parent.setKey(separatorIndex, raised);
            }
        }
        writeNode(node);
        writeNode(sibling);
        writeNode(parent);
    }

    // posisi entri yang mau dihapus, leaf dicari dari key dan internal dari pointer anak kanan nya
    private int entryIndex(BPlusNode node, Object key, int pointer) {
        if (node.isLeaf()) {
            int pos = node.findKey(key, comparator);
            if (pos >= 0) return pos;
        } else {
            for (int i = 0; i < node.keyCount(); i++) {
                if (node.getPointer(i + 1) == pointer) return i;
            }
        }
        throw new StorageException(StorageErrorCode.INVALID_BLOCK,
                "Entry for block " + pointer + " not found in block " + node.getBlockNumber());
    }

    // posisi pointer di parent yang nunjuk ke anak ini
    private static int childIndex(BPlusNode parent, int childBlock) {
        for (int i = 0; i <= parent.keyCount(); i++) {
            if (parent.getPointer(i) == childBlock) return i;
        }
        throw new StorageException(StorageErrorCode.INVALID_BLOCK,
                "Block " + childBlock + " is not a child of block " + parent.getBlockNumber());
    }

    // baca node dari salinan block di buffer
    private BPlusNode readNode(int blockNumber) {
        return nodeCodec.read(loadBlock(blockNumber));
    }

    // tulis node ke buffer, ditandai dirty dan baru ke disk pas flush
    private void writeNode(BPlusNode node) {
        Block block = new Block(fileName, node.getBlockNumber(), new byte[BlockManager.BLOCK_SIZE]);
        nodeCodec.write(node, block);
        pool.writeBuffer(fileName, node.getBlockNumber(), block.data, NO_LSN);
    }

    // ambil block buat node baru, belum ditulis sampai writeNode dipanggil
    private BPlusNode allocateNode(boolean leaf) {
        return new BPlusNode(allocateBlock(), leaf);
    }

    // masukin block ke free list, isi block cuma nomor block bebas berikutnya
    private void freeNode(int blockNumber) {
        byte[] data = new byte[BlockManager.BLOCK_SIZE];
        ByteBuffer.wrap(data).putInt(0, freeListHead);
        pool.writeBuffer(fileName, blockNumber, data, NO_LSN);
        freeListHead = blockNumber;
    }

    // ambil block dari free list dulu, kalau kosong baru nambah block di ujung file
    private int allocateBlock() {
        if (freeListHead != NO_BLOCK) {
            int block = freeListHead;
            freeListHead = ByteBuffer.wrap(pool.readBuffer(fileName, block)).getInt(0);
            return block;
        }
        Block block = pool.appendBlock(fileName);
        pool.unpin(block, false, NO_LSN);
        return block.blockNumber;
    }

    // buka bucket dari salinan block, perubahan harus ditulis balik pakai writeBucket
    private RidBucket openBucket(int blockNumber) {
        return new RidBucket(loadBlock(blockNumber), info.tableName());
    }

    // bikin bucket baru berisi satu RowId, balikin nomor block nya buat disimpan di leaf
    private int newBucket(RowId rowId) {
        Block block = new Block(fileName, allocateBlock(), new byte[BlockManager.BLOCK_SIZE]);
        RidBucket bucket = new RidBucket(block, info.tableName());
        bucket.setNext(RidBucket.NO_NEXT);
        bucket.add(rowId);
        writeBlock(block);
        return block.blockNumber;
    }

    // tambah RowId ke sambungan bucket, isi slot kosong pertama atau sambung block baru di ujung
    // RowId yang udah ada di salah satu bucket diabaikan
    private void addToBucketChain(int firstBlock, RowId rowId) {
        Block target = null;
        Block last = null;
        int current = firstBlock;
        while (current != RidBucket.NO_NEXT) {
            Block block = loadBlock(current);
            RidBucket bucket = new RidBucket(block, info.tableName());
            if (bucket.rowIds().contains(rowId)) return;
            if (target == null && !bucket.isFull()) target = block;
            last = block;
            current = bucket.getNext().orElse(RidBucket.NO_NEXT);
        }
        if (target != null) {
            new RidBucket(target, info.tableName()).add(rowId);
            writeBlock(target);
            return;
        }
        int added = newBucket(rowId);
        new RidBucket(last, info.tableName()).setNext(added);
        writeBlock(last);
        writeMeta();
    }

    // hapus RowId dari sambungan bucket, bucket tengah yang kosong dilepas dari sambungan
    // bucket pertama yang kosong diisi bucket berikutnya biar pointer di leaf ga berubah, true kalau semua bucket habis
    private boolean removeFromBucketChain(int firstBlock, RowId rowId) {
        Block previous = null;
        int current = firstBlock;
        while (current != RidBucket.NO_NEXT) {
            Block block = loadBlock(current);
            RidBucket bucket = new RidBucket(block, info.tableName());
            int next = bucket.getNext().orElse(RidBucket.NO_NEXT);
            if (!bucket.remove(rowId)) {
                previous = block;
                current = next;
                continue;
            }
            if (!bucket.isEmpty()) {
                writeBlock(block);
            } else if (previous != null) {
                new RidBucket(previous, info.tableName()).setNext(next);
                writeBlock(previous);
                freeNode(current);
            } else if (next != RidBucket.NO_NEXT) {
                System.arraycopy(pool.readBuffer(fileName, next), 0, block.data, 0, BlockManager.BLOCK_SIZE);
                writeBlock(block);
                freeNode(next);
            } else {
                return true;
            }
            return false;
        }
        return false;
    }

    // kumpulin semua RowId dari bucket pertama sampai ujung sambungan
    private void collectBucketChain(int firstBlock, List<RowId> out) {
        OptionalInt current = OptionalInt.of(firstBlock);
        while (current.isPresent()) {
            RidBucket bucket = openBucket(current.getAsInt());
            out.addAll(bucket.rowIds());
            current = bucket.getNext();
        }
    }

    // baca meta dari block 0, file dengan magic beda dianggap bukan file index
    private void readMeta() {
        ByteBuffer meta = ByteBuffer.wrap(pool.readBuffer(fileName, META_BLOCK));
        if (meta.getInt(0) != META_MAGIC) {
            throw new StorageException(StorageErrorCode.INVALID_BLOCK, "File " + fileName + " is not an index file");
        }
        maxDegree = meta.getInt(4);
        if (maxDegree < MIN_DEGREE || maxDegree > computeMaxDegree(column)) {
            throw new StorageException(StorageErrorCode.INVALID_BLOCK, "Corrupt maxDegree in " + fileName);
        }
        rootBlock = meta.getInt(8);
        height = meta.getInt(12);
        freeListHead = meta.getInt(16);
    }

    // tulis root, tinggi, free list, dan maxDegree ke block 0
    private void writeMeta() {
        byte[] data = new byte[BlockManager.BLOCK_SIZE];
        ByteBuffer.wrap(data)
                .putInt(0, META_MAGIC)
                .putInt(4, maxDegree)
                .putInt(8, rootBlock)
                .putInt(12, height)
                .putInt(16, freeListHead);
        pool.writeBuffer(fileName, META_BLOCK, data, NO_LSN);
    }

    // salinan isi block dibungkus Block biar bisa dipakai codec dan bucket
    private Block loadBlock(int blockNumber) {
        return new Block(fileName, blockNumber, pool.readBuffer(fileName, blockNumber));
    }

    // tulis balik salinan block ke buffer
    private void writeBlock(Block block) {
        pool.writeBuffer(fileName, block.blockNumber, block.data, NO_LSN);
    }

    // hasil split internal, key yang naik ke parent dan node kanan yang baru
    private record InternalSplit(Object separator, BPlusNode right) {}
}
