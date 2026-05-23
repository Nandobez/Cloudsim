package com.example.filesystem.network;

import com.example.filesystem.FileSystemSimulator;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Servidor TCP para operações de arquivo via protocolo simples.
 * Protocolo: COMANDO|arg1|arg2|... -> OK|resultado ou ERR|mensagem
 */
public class TcpFileServer {
    private final int port;
    private final String bindAddress;
    private final FileSystemSimulator fs;
    private ServerSocket serverSocket;
    private ExecutorService executor;
    private volatile boolean running;
    private final AtomicLong connectionCount;
    private final AtomicLong commandCount;
    private final Set<ClientHandler> activeClients;
    private Instant startTime;

    public TcpFileServer(int port, String bindAddress, FileSystemSimulator fs) {
        this.port = port;
        this.bindAddress = bindAddress;
        this.fs = fs;
        this.connectionCount = new AtomicLong(0);
        this.commandCount = new AtomicLong(0);
        this.activeClients = ConcurrentHashMap.newKeySet();
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(bindAddress, port));
        executor = Executors.newCachedThreadPool();
        running = true;
        startTime = Instant.now();

        executor.submit(() -> {
            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    connectionCount.incrementAndGet();
                    ClientHandler handler = new ClientHandler(clientSocket);
                    activeClients.add(handler);
                    executor.submit(handler);
                } catch (IOException e) {
                    if (running) {
                        System.err.println("Erro ao aceitar conexão: " + e.getMessage());
                    }
                }
            }
        });
    }

    public void stop() {
        running = false;
        for (ClientHandler client : activeClients) {
            client.close();
        }
        activeClients.clear();

        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {}

        if (executor != null) {
            executor.shutdownNow();
        }
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        return port;
    }

    public long getConnectionCount() {
        return connectionCount.get();
    }

    public long getCommandCount() {
        return commandCount.get();
    }

    public int getActiveConnections() {
        return activeClients.size();
    }

    public Instant getStartTime() {
        return startTime;
    }

    // ==================== Client Handler ====================

    class ClientHandler implements Runnable {
        private final Socket socket;
        private BufferedReader reader;
        private PrintWriter writer;
        private volatile boolean clientRunning;

        public ClientHandler(Socket socket) {
            this.socket = socket;
            this.clientRunning = true;
        }

        @Override
        public void run() {
            try {
                reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

                writer.println("OK|FileSystem Server v1.0|Comandos: LIST, GET, PUT, DELETE, MKDIR, RMDIR, INFO, HELP, QUIT");

                String line;
                while (clientRunning && (line = reader.readLine()) != null) {
                    String response = processCommand(line);
                    writer.println(response);
                    commandCount.incrementAndGet();

                    if (line.trim().equalsIgnoreCase("QUIT")) {
                        break;
                    }
                }
            } catch (IOException e) {
                // Cliente desconectou
            } finally {
                close();
                activeClients.remove(this);
            }
        }

        private String processCommand(String line) {
            String[] parts = line.split("\\|");
            if (parts.length == 0) {
                return "ERR|Comando vazio";
            }

            String command = parts[0].toUpperCase().trim();

            try {
                return switch (command) {
                    case "LIST" -> handleList(parts);
                    case "GET" -> handleGet(parts);
                    case "PUT" -> handlePut(parts);
                    case "DELETE" -> handleDelete(parts);
                    case "MKDIR" -> handleMkdir(parts);
                    case "RMDIR" -> handleRmdir(parts);
                    case "EXISTS" -> handleExists(parts);
                    case "INFO" -> handleInfo();
                    case "TREE" -> handleTree();
                    case "HELP" -> handleHelp();
                    case "QUIT" -> "OK|Bye!";
                    default -> "ERR|Comando desconhecido: " + command;
                };
            } catch (Exception e) {
                return "ERR|" + e.getMessage();
            }
        }

        private String handleList(String[] parts) {
            String path = parts.length > 1 ? parts[1] : "/";
            if (!fs.exists(path)) {
                return "ERR|Caminho não encontrado: " + path;
            }
            if (!fs.isDirectory(path)) {
                return "ERR|Não é um diretório: " + path;
            }

            List<FileSystemSimulator.ListingEntry> entries = fs.list(path);
            StringBuilder sb = new StringBuilder("OK");
            for (FileSystemSimulator.ListingEntry entry : entries) {
                sb.append("|").append(entry.directory() ? "D" : "F")
                  .append(":").append(entry.name())
                  .append(":").append(entry.size());
            }
            return sb.toString();
        }

        private String handleGet(String[] parts) {
            if (parts.length < 2) {
                return "ERR|Uso: GET|<path>";
            }
            String path = parts[1];
            if (!fs.exists(path)) {
                return "ERR|Arquivo não encontrado: " + path;
            }
            if (!fs.isFile(path)) {
                return "ERR|Não é um arquivo: " + path;
            }
            var optContent = fs.readFile(path);
            if (optContent.isEmpty()) {
                return "ERR|Não foi possível ler arquivo: " + path;
            }
            return "OK|" + Base64.getEncoder().encodeToString(optContent.get());
        }

        private String handlePut(String[] parts) {
            if (parts.length < 3) {
                return "ERR|Uso: PUT|<path>|<content_base64>";
            }
            String path = parts[1];
            byte[] content;
            try {
                content = Base64.getDecoder().decode(parts[2]);
            } catch (Exception e) {
                // Se não for base64, usar como texto direto
                content = parts[2].getBytes(StandardCharsets.UTF_8);
            }
            fs.createOrOverwriteFile(path, content);
            return "OK|Arquivo criado: " + path;
        }

        private String handleDelete(String[] parts) {
            if (parts.length < 2) {
                return "ERR|Uso: DELETE|<path>";
            }
            String path = parts[1];
            if (!fs.exists(path)) {
                return "ERR|Arquivo não encontrado: " + path;
            }
            fs.deleteFile(path);
            return "OK|Arquivo deletado: " + path;
        }

        private String handleMkdir(String[] parts) {
            if (parts.length < 2) {
                return "ERR|Uso: MKDIR|<path>";
            }
            fs.createDirectory(parts[1]);
            return "OK|Diretório criado: " + parts[1];
        }

        private String handleRmdir(String[] parts) {
            if (parts.length < 2) {
                return "ERR|Uso: RMDIR|<path>|[recursive]";
            }
            boolean recursive = parts.length > 2 && parts[2].equalsIgnoreCase("true");
            fs.deleteDirectory(parts[1], recursive);
            return "OK|Diretório removido: " + parts[1];
        }

        private String handleExists(String[] parts) {
            if (parts.length < 2) {
                return "ERR|Uso: EXISTS|<path>";
            }
            String path = parts[1];
            if (!fs.exists(path)) {
                return "OK|NOT_FOUND";
            }
            return fs.isDirectory(path) ? "OK|DIRECTORY" : "OK|FILE";
        }

        private String handleInfo() {
            FileSystemSimulator.DiskInfo info = fs.diskInfo();
            return String.format("OK|total=%d|used=%d|free=%d|cluster_size=%d",
                info.clusterCount(), info.usedBytes(), info.freeBytes(), info.clusterSize());
        }

        private String handleTree() {
            String tree = fs.dumpTree();
            return "OK|" + Base64.getEncoder().encodeToString(tree.getBytes(StandardCharsets.UTF_8));
        }

        private String handleHelp() {
            return "OK|Comandos: LIST|path, GET|path, PUT|path|content, DELETE|path, MKDIR|path, RMDIR|path|recursive, EXISTS|path, INFO, TREE, HELP, QUIT";
        }

        public void close() {
            clientRunning = false;
            try {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException ignored) {}
        }
    }
}
