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
        this.sokcet =socket;
    }
    
    @override
    public void run(){
        try(
            BufferedReader in  = new BufferedReader(
                new InputStreamReader(socket.getInputStream()));
            BufferedWriter out = new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream())) 
        ){
            String request =  in.readLine();
            System.out.println("Recieved: " + request);
            
            String response;
            
            if("PING".equalsIgnoreCase(request)){
                response  = "PONG";
            }else{
                response = "ERROR: UNKNOWN COMMAND";
            }
            out.write(response);
            out.newLine();
            out.flush();
            
        }catch (IOException e){
            System.err.println("Client handler error: " + e.getMessage());
        }finally{
            try{
                socket.close();
            }catch (IOException ignored) {}
        }
        
    }
    
}
