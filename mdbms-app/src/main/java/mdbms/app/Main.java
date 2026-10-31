package mdbms.app;

import java.io.IOException;
import mdbms.net.DBMSServer;

public final class Main {
    public static void main(String[] args) throws IOException {
        System.out.println("mDBMS 0.1.0-SNAPSHOT");

        DBMSConfig cfg = new DBMSConfig();
        DBMS dbms = new DBMS(cfg);
        dbms.start();
        Runtime.getRuntime().addShutdownHook(new Thread(dbms::shutdown));

        new DBMSServer(cfg.getPort(), dbms.getQueryProcessor(), dbms.getConcurrencyControl()).start();
    }
}
