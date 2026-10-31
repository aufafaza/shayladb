package mdbms.app;

import java.io.PrintStream;
import java.io.InputStream;
import java.io.InputStreamReader; 
import java.io.BufferedReader; 
import java.io.IOException; 

public class CommandLineInterface {
    // private final IQueryProcessor processor; 
    private volatile boolean running; 
    private final BufferedReader in; 
    private final PrintStream out; 

    public CommandLineInterface(/* IQueryProcessor processor*/InputStream in, PrintStream out){
        // this.processor = processor;  
        this.in = new BufferedReader(new InputStreamReader(in)); 
        this.out = out; 
    }
    public void run(){ 
        running = true;

        while (running){ 
            String statement = readStatement(); 
            if (statement == null || statement.equalsIgnoreCase("exit")){
                break; 
            }

            if (statement.isEmpty()){ 
                continue; 
            }

            try { 
                // printResult(processor.executeQuery(statement));
                out.println("tes"); 
            }catch (RuntimeException e){ 
                out.println("error: " +  e.getMessage()); 
            }
        }
        running = false; 
    }

    public String stop(){ 
        running = false; 
        StringBuilder statement = new StringBuilder(); 
        // read lines until ';' 
        out.print("mdbms> "); 

        try { 
            String line; 
            while ((line = in.readLine()) != null){ 
                String trimmed = line.trim(); 
                if (statement.length() == 0 && (trimmed.isEmpty()) || trimmed.equalsIgnoreCase("exit")){ 
                    return trimmed; 
                }

                statement.append(trimmed).append(' '); 
                if (trimmed.endsWith(";")){
                    return statement.toString().trim(); 
                }
                out.print(" ->");
            }

        }catch (IOException e){ 
            out.println("error: " + e.getMessage());
        }
            return null; 
    }
    

    private String readStatement(){ 
        return "mock"; 
    }

    private void printResult(/* result ExecutionResult*/){
        // mock do here 
    }
}

