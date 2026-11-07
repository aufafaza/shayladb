package mdbms.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DBMSConfigTest {

    @Test
    void defaultsAreUsable() {
        DBMSConfig cfg = new DBMSConfig();

        assertEquals("data", cfg.getDataDirectory());
        assertEquals(64, cfg.getBufferCapacity());
        assertEquals(ConcurrencyAlgorithm.TIMESTAMP_BASED, cfg.getConcurrencyAlgorithm());
        assertEquals(7654, cfg.getPort());
    }

    @Test
    void constructorKeepsValues() {
        DBMSConfig cfg = new DBMSConfig("dir", 8, ConcurrencyAlgorithm.LOCK_BASED);

        assertEquals("dir", cfg.getDataDirectory());
        assertEquals(8, cfg.getBufferCapacity());
        assertEquals(ConcurrencyAlgorithm.LOCK_BASED, cfg.getConcurrencyAlgorithm());
    }

    @Test
    void rejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new DBMSConfig("dir", 0, ConcurrencyAlgorithm.TIMESTAMP_BASED));
        assertThrows(NullPointerException.class,
                () -> new DBMSConfig(null, 8, ConcurrencyAlgorithm.TIMESTAMP_BASED));
        assertThrows(NullPointerException.class, () -> new DBMSConfig("dir", 8, null));
        assertThrows(IllegalArgumentException.class,
                () -> new DBMSConfig("dir", 8, ConcurrencyAlgorithm.TIMESTAMP_BASED, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new DBMSConfig("dir", 8, ConcurrencyAlgorithm.TIMESTAMP_BASED, 65536));
    }
}
