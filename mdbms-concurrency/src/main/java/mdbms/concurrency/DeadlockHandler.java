package mdbms.concurrency;

public class DeadlockHandler {

    boolean detectDeadlock() {
        // TODO
        return true; 
    } 

    PrecedenceGraph buildWaitForGraph() {
        // TODO
        return new PrecedenceGraph();
    } 
    
    Transaction chooseVictim() {
        return new Transaction(0, 0);
    } 

}