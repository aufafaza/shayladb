package mdbms.app;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.Test;

class MainTest {

    @Test
    void mainPrintsBanner() {
        PrintStream original = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        System.setOut(new PrintStream(output));
        try {
            Main.main(new String[0]);
        } finally {
            System.setOut(original);
        }

        assertTrue(output.toString().contains("mDBMS"));
    }
}
