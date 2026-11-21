package mdbms.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class CCMTestDriverTest {

    @Test
    void testDriverRunsAllScenariosWithZeroFailures() {
        CCMTestDriver driver = new CCMTestDriver();
        driver.runAll();

        assertFalse(driver.getReport().isEmpty(), "Report should not be empty");
        assertEquals(0L, driver.getFailedCount(), () -> "Failed scenarios: " + driver.getReport());
    }
}
