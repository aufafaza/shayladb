package mdbms.storage.block;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import mdbms.storage.exception.StorageErrorCode;
import mdbms.storage.exception.StorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlockManagerTest {
    @TempDir Path directory;

    @Test
    void persistsExactBlocksWithoutChangingNeighbors() throws Exception {
        BlockManager disk = new BlockManager(directory.toString());
        assertEquals(0, disk.blockCount("users.tbl"));
        assertFalse(Files.exists(directory.resolve("users.tbl")));
        assertEquals(0, disk.appendBlock("users.tbl"));
        assertEquals(1, disk.appendBlock("users.tbl"));
        assertEquals(0, disk.appendBlock("users.idx"));
        byte[] data = new byte[4096];
        Arrays.fill(data, (byte) 71);
        disk.writeBlock("users.tbl", 1, data);

        BlockManager reopened = new BlockManager(directory.toString());
        assertEquals(2, reopened.blockCount("users.tbl"));
        assertEquals(8192, Files.size(directory.resolve("users.tbl")));
        assertArrayEquals(new byte[4096], reopened.readBlock("users.tbl", 0));
        assertArrayEquals(data, reopened.readBlock("users.tbl", 1));
        assertArrayEquals(new byte[4096], reopened.readBlock("users.idx", 0));

        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process child = new ProcessBuilder(java, "-cp", classpath, ReopenProbe.class.getName(),
                directory.toString()).redirectErrorStream(true).start();
        try {
            assertTrue(child.waitFor(20, TimeUnit.SECONDS), "Reopen process timed out");
            assertEquals(0, child.exitValue(), new String(child.getInputStream().readAllBytes()));
        } finally {
            child.destroyForcibly();
        }
    }

    public static class ReopenProbe {
        public static void main(String[] args) {
            BlockManager disk = new BlockManager(args[0]);
            byte[] expected = new byte[4096];
            Arrays.fill(expected, (byte) 71);
            if (disk.blockCount("users.tbl") != 2
                    || !Arrays.equals(expected, disk.readBlock("users.tbl", 1))) {
                throw new AssertionError("Persisted bytes changed across JVMs");
            }
        }
    }

    @Test
    void rejectsInvalidAddressesAndSizesWithoutChangingFiles() throws IOException {
        BlockManager disk = new BlockManager(directory.toString());
        disk.appendBlock("users.tbl");
        for (int number : new int[] {-1, 1, Integer.MAX_VALUE}) {
            assertThrows(StorageException.class, () -> disk.readBlock("users.tbl", number));
            assertThrows(StorageException.class, () -> disk.writeBlock("users.tbl", number, new byte[4096]));
        }
        for (byte[] data : new byte[][] {null, new byte[0], new byte[4095], new byte[4097]}) {
            assertThrows(StorageException.class, () -> disk.writeBlock("users.tbl", 0, data));
        }
        assertEquals(4096, Files.size(directory.resolve("users.tbl")));
        assertArrayEquals(new byte[4096], disk.readBlock("users.tbl", 0));
    }

    @Test
    void missingReadsAndWritesNeverCreateFiles() {
        BlockManager disk = new BlockManager(directory.toString());
        assertThrows(StorageException.class, () -> disk.readBlock("missing.tbl", 0));
        assertThrows(StorageException.class, () -> disk.writeBlock("missing.tbl", 0, new byte[4096]));
        assertFalse(Files.exists(directory.resolve("missing.tbl")));
    }

    @Test
    void rejectsTruncatedFilesForEveryOperation() throws IOException {
        Path file = directory.resolve("broken.tbl");
        Files.write(file, new byte[4097]);
        BlockManager disk = new BlockManager(directory.toString());
        assertThrows(StorageException.class, () -> disk.blockCount("broken.tbl"));
        assertThrows(StorageException.class, () -> disk.readBlock("broken.tbl", 0));
        assertThrows(StorageException.class, () -> disk.writeBlock("broken.tbl", 0, new byte[4096]));
        assertThrows(StorageException.class, () -> disk.appendBlock("broken.tbl"));
        assertEquals(4097, Files.size(file));
    }

    @Test
    void rejectsUnsafeNames() {
        BlockManager disk = new BlockManager(directory.toString());
        for (String name : new String[] {null, "", " ", ".", "..", "../escape.tbl", "sub/file.tbl",
                "sub\\file.tbl", "C:\\escape.tbl", "/escape.tbl", "users.tbl:stream", "users.tbl.",
                "users.tbl ", "USERS~1.TBL", "CON", "NUL.tbl"}) {
            StorageException error = assertThrows(StorageException.class, () -> disk.appendBlock(name), name);
            assertEquals(StorageErrorCode.INVALID_BLOCK, error.getCode());
        }
    }

    @Test
    void createsDataDirectoryAndPreservesIoCauses() throws IOException {
        Path nested = directory.resolve("data");
        new BlockManager(nested.toString()).appendBlock("users.tbl");
        assertTrue(Files.isRegularFile(nested.resolve("users.tbl")));
        Path file = directory.resolve("not-a-directory");
        Files.write(file, new byte[0]);
        StorageException error = assertThrows(StorageException.class, () -> new BlockManager(file.toString()));
        assertEquals(StorageErrorCode.IO_FAILURE, error.getCode());
        assertInstanceOf(IOException.class, error.getCause());
    }

    // deleteFile ngapus file dan aman dipanggil dua kali
    @Test
    void deleteFileRemovesFileAndIsIdempotent() {
        BlockManager disk = new BlockManager(directory.toString());
        disk.appendBlock("drop.idx");

        disk.deleteFile("drop.idx");
        disk.deleteFile("drop.idx");

        assertFalse(Files.exists(directory.resolve("drop.idx")));
        assertEquals(0, disk.blockCount("drop.idx"));
        assertCode(() -> disk.deleteFile("../escape.idx"));
    }

    // nama file ga valid ditolak sebagai INVALID_BLOCK
    private static void assertCode(org.junit.jupiter.api.function.Executable action) {
        assertEquals(StorageErrorCode.INVALID_BLOCK, assertThrows(StorageException.class, action).getCode());
    }
}
