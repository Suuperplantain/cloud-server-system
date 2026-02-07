/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

/**
 *
 * @author ntu-user
 */
import java.io.*;
import java.net.*;
public class ClientHandler implements Runnable{
    private final Socket socket;
    
    public ClientHandler (Socket socket){
        this.socket =socket;
    }
    
    @Override
    public void run(){
        try(
            BufferedReader in  = new BufferedReader(
                new InputStreamReader(socket.getInputStream()));
            BufferedWriter out = new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream())) 
        ){
            String request;
            while ((request = in.readLine()) != null) {
            System.out.println("Received: " + request);
            
            if ("EXIT".equalsIgnoreCase(request)) {
                out.write("GOODBYE");
                out.newLine();
                out.flush();
                break;
            }
            
            String response;
            
            if("PING".equalsIgnoreCase(request)){
                response  = "PONG FROM STORAGE-1";
            }else if ("TIME".equalsIgnoreCase(request)) {
                response = new java.util.Date().toString();
            } else if ("HELLO".equalsIgnoreCase(request)) {
                response = "HELLO CLIENT!";
            } else {
                response = "ERROR: UNKNOWN COMMAND";
            }
            out.write(response);
            out.newLine();
            out.flush();
            }
        }catch (IOException e){
            System.err.println("Client handler error: " + e.getMessage());
        }finally{
            try{
                socket.close();
            }catch (IOException ignored) {}
        }
        
    }
    
}
