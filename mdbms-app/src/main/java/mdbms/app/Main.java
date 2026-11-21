package mdbms.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.TimeUnit;

import mdbms.net.DBMSServer;
import mdbms.net.InteractiveDBMSServer;

public final class Main {
    public static void main(String[] args) throws IOException {
        System.out.println("mDBMS 0.1.0-SNAPSHOT");

        DBMSConfig cfg = new DBMSConfig();
        DBMS dbms = new DBMS(cfg);
        dbms.start();

        DBMSServer jsonServer = new DBMSServer(
                cfg.getPort(), dbms.getQueryProcessor(), dbms.getConcurrencyControl());
        InteractiveDBMSServer interactiveServer = new InteractiveDBMSServer(
                cfg.getInteractivePort(), dbms.getQueryProcessor(), dbms.getConcurrencyControl());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            shutdownServer(interactiveServer);
            shutdownServer(jsonServer);
            dbms.shutdown();
        }, "mdbms-shutdown"));

        Thread interactiveThread = new Thread(() -> {
            try {
                interactiveServer.start();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, "mdbms-interactive-server");
        interactiveThread.start();
        try {
            try {
                if (!interactiveServer.awaitReady(5, TimeUnit.SECONDS)) {
                    throw new IOException("Interactive server failed to start");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for interactive server startup", e);
            }
            jsonServer.start();
        } finally {
            shutdownServer(interactiveServer);
        }
    }

    private static void shutdownServer(DBMSServer server) {
        try {
            server.shutdown();
        } catch (IOException e) {
            System.err.println("[ERROR] Could not stop server: " + e.getMessage());
        }
    }
}
