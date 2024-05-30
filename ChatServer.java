import java.net.*;
import java.io.*;
import java.util.*;

public class ChatServer {
    private static Map<String, ClientHandler> clientHandlers = new HashMap<>();
    private static Map<String, Set<String>> groups = new HashMap<>();

    public static void main(String[] args) throws Exception {
        ServerSocket server = new ServerSocket(1234);
        System.out.println("Server is ready to accept connections...");

        while (true) {
            Socket socket = server.accept();
            System.out.println("New client connected: " + socket);

            ClientHandler clientHandler = new ClientHandler(socket);
            new Thread(clientHandler).start();
        }
    }

    public static void broadcast(String message, ClientHandler excludeClient) {
        for (ClientHandler client : clientHandlers.values()) {
            if (client != excludeClient) {
                client.sendMessage(message);
            }
        }
    }

    public static void sendPrivateMessage(String message, String recipientName, ClientHandler sender) {
        ClientHandler recipient = clientHandlers.get(recipientName);
        if (recipient != null) {
            recipient.sendMessage("Private from " + sender.getClientName() + ": " + message);
            sender.sendMessage("Private to " + recipientName + ": " + message);
        } else {
            sender.sendMessage("Client " + recipientName + " not found.");
        }
    }

    public static void createGroup(String groupName, ClientHandler creator) {
        if (groups.containsKey(groupName)) {
            creator.sendMessage("Group " + groupName + " already exists.");
        } else {
            groups.put(groupName, new HashSet<>());
            groups.get(groupName).add(creator.getClientName());
            creator.sendMessage("Group " + groupName + " created.");
        }
    }

    public static void addParticipantToGroup(String groupName, String participantName, ClientHandler requester) {
        Set<String> group = groups.get(groupName);
        if (group == null) {
            requester.sendMessage("Group " + groupName + " does not exist.");
        } else if (!clientHandlers.containsKey(participantName)) {
            requester.sendMessage("Client " + participantName + " does not exist.");
        } else {
            group.add(participantName);
            requester.sendMessage("Added " + participantName + " to group " + groupName + ".");
        }
    }

    public static void sendGroupMessage(String groupName, String message, ClientHandler sender) {
        Set<String> group = groups.get(groupName);
        if (group == null) {
            sender.sendMessage("Group " + groupName + " does not exist.");
        } else if (!group.contains(sender.getClientName())) {
            sender.sendMessage("You are not a member of group " + groupName + ".");
        } else {
            for (String member : group) {
                if (!member.equals(sender.getClientName())) {
                    clientHandlers.get(member).sendMessage("Group " + groupName + " from " + sender.getClientName() + ": " + message);
                }
            }
        }
    }

    public static void addClient(String clientName, ClientHandler clientHandler) {
        clientHandlers.put(clientName, clientHandler);
    }

    public static void removeClient(ClientHandler clientHandler) {
        clientHandlers.values().remove(clientHandler);
    }
}

class ClientHandler implements Runnable {
    private Socket socket;
    private DataInputStream dis;
    private DataOutputStream dos;
    private String clientName;

    public ClientHandler(Socket socket) throws IOException {
        this.socket = socket;
        this.dis = new DataInputStream(socket.getInputStream());
        this.dos = new DataOutputStream(socket.getOutputStream());
    }

    @Override
    public void run() {
        try {
            dos.writeUTF("Enter your name: ");
            clientName = dis.readUTF();
            ChatServer.addClient(clientName, this);
            ChatServer.broadcast(clientName + " has joined the chat", this);

            String msg;
            while (true) {
                msg = dis.readUTF();
                if (msg.equalsIgnoreCase("Quit")) {
                    break;
                }
                if (msg.startsWith("@")) {
                    int spaceIndex = msg.indexOf(' ');
                    if (spaceIndex != -1) {
                        String recipientName = msg.substring(1, spaceIndex);
                        String privateMessage = msg.substring(spaceIndex + 1);
                        ChatServer.sendPrivateMessage(privateMessage, recipientName, this);
                    } else {
                        dos.writeUTF("Incorrect format. Use @recipientName message");
                    }
                } else if (msg.startsWith("GROUP CREATE")) {
                    String groupName = msg.substring("GROUP CREATE".length()).trim();
                    ChatServer.createGroup(groupName, this);
                } else if (msg.startsWith("GROUP ADD")) {
                    String[] parts = msg.split(" ");
                    String groupName = parts[2];
                    String participantName = parts[3];
                    ChatServer.addParticipantToGroup(groupName, participantName, this);
                } else if (msg.startsWith("GMSG")) {
                    int firstSpaceIndex = msg.indexOf(' ');
                    int secondSpaceIndex = msg.indexOf(' ', firstSpaceIndex + 1);
                    if (secondSpaceIndex != -1) {
                        String groupName = msg.substring(firstSpaceIndex + 1, secondSpaceIndex);
                        String groupMessage = msg.substring(secondSpaceIndex + 1);
                        ChatServer.sendGroupMessage(groupName, groupMessage, this);
                    } else {
                        dos.writeUTF("Incorrect format. Use GMSG groupName message");
                    }
                } 
                
                else if (msg.startsWith("FILE")) {
                    receiveFile(msg);
                } else if (msg.startsWith("DOWNLOAD")) {
                    String fileName = msg.split(" ", 2)[1];
                    sendFile(fileName);
                } else {
                    ChatServer.broadcast(clientName + ": " + msg, this);
                }
            }

            dis.close();
            dos.close();
            socket.close();
            ChatServer.broadcast(clientName + " has left the chat", this);
            ChatServer.removeClient(this);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void sendMessage(String message) {
        try {
            dos.writeUTF(message);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void receiveFile(String fileInfo) throws IOException {
        String[] parts = fileInfo.split(" ");
        String fileName = parts[1];
        long fileSize = Long.parseLong(parts[2]);

        FileOutputStream fos = new FileOutputStream("server_" + fileName);
        byte[] buffer = new byte[4096];
        int bytesRead;
        long totalRead = 0;

        while (totalRead < fileSize && (bytesRead = dis.read(buffer, 0, (int)Math.min(buffer.length, fileSize - totalRead))) != -1) {
            totalRead += bytesRead;
            fos.write(buffer, 0, bytesRead);
        }

        fos.close();
        ChatServer.broadcast(clientName + " shared a file: " + fileName, this);
    }

    public void sendFile(String fileName) {
        try {
            File file = new File("server_" + fileName);
            if (!file.exists()) {
                dos.writeUTF("File not found: " + fileName);
                return;
            }

            dos.writeUTF("FILE " + file.getName() + " " + file.length());
            FileInputStream fis = new FileInputStream(file);
            byte[] buffer = new byte[4096];
            int bytesRead;

            while ((bytesRead = fis.read(buffer)) != -1) {
                dos.write(buffer, 0, bytesRead);
            }

            fis.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public String getClientName() {
        return clientName;
    }
}
