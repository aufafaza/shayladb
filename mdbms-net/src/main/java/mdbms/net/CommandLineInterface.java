package mdbms.net;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

import mdbms.models.ExecutionResult;

public class CommandLineInterface {
    private BufferedReader reader;
    private PrintWriter writer;

    public CommandLineInterface(Socket socket) throws IOException {
        this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        this.writer = new PrintWriter(socket.getOutputStream(), true);
    }

    public String readQuery() throws IOException {
        StringBuilder stringBuilder = new StringBuilder();
        int charCode;
        while ((charCode = reader.read()) != -1) { 
            char ch = (char) charCode;
            stringBuilder.append(ch);
            if (ch == ';') {
                return stringBuilder.toString();
            }
        }
        return null;
    }

    public void printResult(ExecutionResult result) {
        // TODO: print result as table
        writer.println("Result printed");
    }
}
