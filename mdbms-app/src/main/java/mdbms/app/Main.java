package mdbms.app;

public final class Main {
    public static void main(String[] args) {
        System.out.println("mDBMS 0.1.0-SNAPSHOT"); 

        // call constructors for all
        DBMSConfig cfg = new DBMSConfig("null", 0, ConcurrencyAlgorithm.LOCK_BASED);
        DBMS dbms = new DBMS(cfg);
        
        dbms.start();
        Runtime.getRuntime().addShutdownHook(new Thread(dbms::shutdown));
        // new CommandLineInterface(dbms.getQueryProcessor()).run(); 
        dbms.shutdown(); 
    }       
}
