package mdbms.storage.block;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import mdbms.storage.exception.StorageErrorCode;
import mdbms.storage.exception.StorageException;

/** Mengurus baca/tulis blok. */
public class BlockManager {
    public static final int BLOCK_SIZE = 4096;
    private final Path dataDirectory;

    /** konstruktor dengan direktori penyimpanan dan path absolut */
    public BlockManager(String dataDirectory) {
        if (dataDirectory == null || dataDirectory.isBlank()) {
            throw invalid("Data directory must not be blank");
        }
        try {
            this.dataDirectory = Files.createDirectories(Path.of(dataDirectory)).toRealPath();
        } catch (InvalidPathException e) {
            throw new StorageException(StorageErrorCode.INVALID_BLOCK, "Invalid data directory", e);
        } catch (IOException e) {
            throw ioFailure("Cannot open data directory " + dataDirectory, e);
        }
    }

    public synchronized byte[] readBlock(String fileName, int blockNumber) {
        validateNumber(blockNumber);
        Path path = pathFor(fileName);
        try (FileChannel file = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            checkAddress(file, blockNumber);
            ByteBuffer buffer = ByteBuffer.allocate(BLOCK_SIZE);
            file.position((long) blockNumber * BLOCK_SIZE);
            while (buffer.hasRemaining()) {
                if (file.read(buffer) < 0) throw new EOFException("Incomplete block " + blockNumber);
            }
            return buffer.array();
        } catch (IOException e) {
            throw ioFailure("Cannot read " + fileName + " block " + blockNumber, e);
        }
    }

    /** Nimpa blok yang udah ada dan sync ke disk. ga buat blok baru*/
    public synchronized void writeBlock(String fileName, int blockNumber, byte[] data) {
        validateNumber(blockNumber);
        validateData(data);
        Path path = pathFor(fileName);
        try (FileChannel file = FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            checkAddress(file, blockNumber);
            file.position((long) blockNumber * BLOCK_SIZE);
            writeAndSync(file, data);
        } catch (IOException e) {
            throw ioFailure("Cannot write " + fileName + " block " + blockNumber, e);
        }
    }

    /** append satu blok berisi nol di akhir file. return nomor blok barunya. */
    public synchronized int appendBlock(String fileName) {
        Path path = pathFor(fileName);
        try (FileChannel file = FileChannel.open(path, StandardOpenOption.WRITE,
                StandardOpenOption.CREATE, LinkOption.NOFOLLOW_LINKS)) {
            int number = count(file);
            if (number == Integer.MAX_VALUE) throw invalid("File has too many blocks: " + fileName);
            file.position((long) number * BLOCK_SIZE);
            // Ini hanya alokasi kosong; perubahan isinya nanti harus melalui jalur WAL di BufferPool.
            writeAndSync(file, new byte[BLOCK_SIZE]);
            return number;
        } catch (IOException e) {
            throw ioFailure("Cannot append to " + fileName, e);
        }
    }

    /** file block counter*/
    public synchronized int blockCount(String fileName) {
        Path path = pathFor(fileName);
        try (FileChannel file = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            return count(file);
        } catch (NoSuchFileException e) {
            return 0;
        } catch (IOException e) {
            throw ioFailure("Cannot count blocks in " + fileName, e);
        }
    }

    // hapus file dari disk, file yang udah ga ada dianggap beres
    public synchronized void deleteFile(String fileName) {
        Path path = pathFor(fileName);
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw ioFailure("Cannot delete " + fileName, e);
        }
    }

    /** validasi nama file di dalam direktori data */
    Path pathFor(String fileName) {
        // Tolak nama Windows yang bisa buat identitas cache inkonsisten.
        if (fileName == null || fileName.isBlank() || fileName.equals(".") || fileName.equals("..")
                || fileName.endsWith(".") || fileName.endsWith(" ")
                || fileName.matches(".*[\\\\/:*?\"<>|~\\p{Cntrl}].*")
                || fileName.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?")) {
            throw invalid("Invalid block file name: " + fileName);
        }
        try {
            return dataDirectory.resolve(fileName);
        } catch (InvalidPathException e) {
            throw new StorageException(StorageErrorCode.INVALID_BLOCK, "Invalid block file name", e);
        }
    }

    static void validateNumber(int blockNumber) {
        if (blockNumber < 0) throw invalid("Block number must not be negative: " + blockNumber);
    }

    static void validateData(byte[] data) {
        if (data == null || data.length != BLOCK_SIZE) throw invalid("Block data must contain exactly 4096 bytes");
    }

    /** memastikan blok tujuan sudah dialokasikan sebelum dibaca atau ditimpa. */
    private static void checkAddress(FileChannel file, int blockNumber) throws IOException {
        if (blockNumber >= count(file)) throw invalid("Block does not exist: " + blockNumber);
    }
    private static int count(FileChannel file) throws IOException {
        long size = file.size();
        if (size % BLOCK_SIZE != 0 || size / BLOCK_SIZE > Integer.MAX_VALUE) {
            throw new IOException("Invalid block file length: " + size);
        }
        return (int) (size / BLOCK_SIZE);
    }

    /** Menulis seluruh byte, lalu sinkronkan data dan metadata file ke disk. */
    private static void writeAndSync(FileChannel file, byte[] data) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        // habiskan seluruh buffer.
        while (buffer.hasRemaining()) file.write(buffer);
        file.force(true);
    }

    private static StorageException invalid(String message) {
        return new StorageException(StorageErrorCode.INVALID_BLOCK, message);
    }

    private static StorageException ioFailure(String message, IOException cause) {
        return new StorageException(StorageErrorCode.IO_FAILURE, message, cause);
    }
}
