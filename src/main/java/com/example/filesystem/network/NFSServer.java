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
 * NFS Server simplificado (Network File System).
 */
public class NFSServer {

    public static class Export {
        private final String path;
        private final String fsPath; // Caminho no filesystem local
        private final Set<String> allowedHosts;
        private final boolean readOnly;
        private final boolean sync;

        public Export(String path, String fsPath, boolean readOnly, boolean sync) {
            this.path = path;
            this.fsPath = fsPath;
            this.allowedHosts = ConcurrentHashMap.newKeySet();
            this.readOnly = readOnly;
            this.sync = sync;
        }

        public String getPath() { return path; }
        public String getFsPath() { return fsPath; }
        public boolean isReadOnly() { return readOnly; }
        public boolean isSync() { return sync; }

        public void allowHost(String host) { allowedHosts.add(host); }
        public void denyHost(String host) { allowedHosts.remove(host); }
        public void allowAll() { allowedHosts.add("*"); }

        public boolean isAllowed(String host) {
            return allowedHosts.contains("*") || allowedHosts.contains(host);
        }

        @Override
        public String toString() {
            String opts = (readOnly ? "ro" : "rw") + (sync ? ",sync" : ",async");
            return String.format("%s -> %s (%s) [%s]", path, fsPath, opts, allowedHosts);
        }
    }

    public static class Mount {
        private final String clientHost;
        private final String exportPath;
        private final String mountPoint;
        private final Instant mountedAt;
        private final AtomicLong bytesRead;
        private final AtomicLong bytesWritten;
        private final AtomicLong operations;

        public Mount(String clientHost, String exportPath, String mountPoint) {
            this.clientHost = clientHost;
            this.exportPath = exportPath;
            this.mountPoint = mountPoint;
            this.mountedAt = Instant.now();
            this.bytesRead = new AtomicLong(0);
            this.bytesWritten = new AtomicLong(0);
            this.operations = new AtomicLong(0);
        }

        public String getClientHost() { return clientHost; }
        public String getExportPath() { return exportPath; }
        public String getMountPoint() { return mountPoint; }
        public Instant getMountedAt() { return mountedAt; }
        public long getBytesRead() { return bytesRead.get(); }
        public long getBytesWritten() { return bytesWritten.get(); }
        public long getOperations() { return operations.get(); }

        public void addRead(long bytes) { bytesRead.addAndGet(bytes); operations.incrementAndGet(); }
        public void addWrite(long bytes) { bytesWritten.addAndGet(bytes); operations.incrementAndGet(); }

        @Override
        public String toString() {
            return String.format("%s:%s on %s (since %s, ops=%d)",
                clientHost, exportPath, mountPoint, mountedAt, operations.get());
        }
    }

    public static class FileHandle {
        private final String handleId;
        private final String path;
        private final boolean isDirectory;
        private final Instant createdAt;

        public FileHandle(String path, boolean isDirectory) {
            this.handleId = UUID.randomUUID().toString().substring(0, 16);
            this.path = path;
            this.isDirectory = isDirectory;
            this.createdAt = Instant.now();
        }

        public String getHandleId() { return handleId; }
        public String getPath() { return path; }
        public boolean isDirectory() { return isDirectory; }
        public Instant getCreatedAt() { return createdAt; }
    }

    private final FileSystemSimulator fs;
    private final int port;
    private final Map<String, Export> exports;
    private final Map<String, Mount> activeMounts;
    private final Map<String, FileHandle> fileHandles;
    private ServerSocket serverSocket;
    private ExecutorService executor;
    private volatile boolean running;
    private final AtomicLong totalOperations;
    private Instant startTime;

    public NFSServer(FileSystemSimulator fs, int port) {
        this.fs = fs;
        this.port = port;
        this.exports = new ConcurrentHashMap<>();
        this.activeMounts = new ConcurrentHashMap<>();
        this.fileHandles = new ConcurrentHashMap<>();
        this.totalOperations = new AtomicLong(0);
    }

    public void addExport(String path, String fsPath, boolean readOnly) {
        Export export = new Export(path, fsPath, readOnly, true);
        export.allowAll();
        exports.put(path, export);

        // Garantir que o diretório existe
        if (!fs.exists(fsPath)) {
            fs.createDirectory(fsPath);
        }
    }

    public void addExport(String path, String fsPath) {
        addExport(path, fsPath, false);
    }

    public void removeExport(String path) {
        exports.remove(path);
        // Desmontar todos os clientes
        activeMounts.entrySet().removeIf(e -> e.getValue().getExportPath().equals(path));
    }

    public Export getExport(String path) {
        return exports.get(path);
    }

    public Collection<Export> getExports() {
        return Collections.unmodifiableCollection(exports.values());
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        executor = Executors.newCachedThreadPool();
        running = true;
        startTime = Instant.now();

        executor.submit(() -> {
            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    executor.submit(new ClientHandler(clientSocket));
                } catch (IOException e) {
                    if (running) {
                        System.err.println("NFS accept error: " + e.getMessage());
                    }
                }
            }
        });
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {}
        if (executor != null) executor.shutdownNow();
    }

    public boolean isRunning() { return running; }
    public int getPort() { return port; }

    private class ClientHandler implements Runnable {
        private final Socket socket;
        private BufferedReader reader;
        private PrintWriter writer;

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

                String line;
                while ((line = reader.readLine()) != null) {
                    String response = processCommand(line);
                    writer.println(response);
                    totalOperations.incrementAndGet();
                }
            } catch (IOException e) {
                // Cliente desconectou
            } finally {
                try { socket.close(); } catch (IOException ignored) {}
            }
        }

        private String processCommand(String line) {
            String[] parts = line.split("\\|");
            if (parts.length == 0) return "ERR|Comando vazio";

            String clientHost = socket.getInetAddress().getHostAddress();
            String command = parts[0].toUpperCase();

            try {
                return switch (command) {
                    case "MOUNT" -> handleMount(parts, clientHost);
                    case "UNMOUNT" -> handleUnmount(parts, clientHost);
                    case "LOOKUP" -> handleLookup(parts, clientHost);
                    case "READ" -> handleRead(parts, clientHost);
                    case "WRITE" -> handleWrite(parts, clientHost);
                    case "CREATE" -> handleCreate(parts, clientHost);
                    case "REMOVE" -> handleRemove(parts, clientHost);
                    case "MKDIR" -> handleMkdir(parts, clientHost);
                    case "RMDIR" -> handleRmdir(parts, clientHost);
                    case "READDIR" -> handleReaddir(parts, clientHost);
                    case "GETATTR" -> handleGetattr(parts, clientHost);
                    case "EXPORTS" -> handleExports();
                    default -> "ERR|Comando desconhecido: " + command;
                };
            } catch (Exception e) {
                return "ERR|" + e.getMessage();
            }
        }

        private String handleMount(String[] parts, String clientHost) {
            if (parts.length < 3) return "ERR|Uso: MOUNT|exportPath|mountPoint";

            String exportPath = parts[1];
            String mountPoint = parts[2];

            Export export = exports.get(exportPath);
            if (export == null) {
                return "ERR|Export não encontrado: " + exportPath;
            }

            if (!export.isAllowed(clientHost)) {
                return "ERR|Acesso negado para " + clientHost;
            }

            String mountKey = clientHost + ":" + exportPath;
            Mount mount = new Mount(clientHost, exportPath, mountPoint);
            activeMounts.put(mountKey, mount);

            return "OK|Montado " + exportPath + " em " + mountPoint;
        }

        private String handleUnmount(String[] parts, String clientHost) {
            if (parts.length < 2) return "ERR|Uso: UNMOUNT|exportPath";

            String exportPath = parts[1];
            String mountKey = clientHost + ":" + exportPath;

            if (activeMounts.remove(mountKey) != null) {
                return "OK|Desmontado " + exportPath;
            }
            return "ERR|Não está montado: " + exportPath;
        }

        private String handleLookup(String[] parts, String clientHost) {
            if (parts.length < 3) return "ERR|Uso: LOOKUP|exportPath|path";

            String exportPath = parts[1];
            String path = parts[2];

            Export export = exports.get(exportPath);
            if (export == null) return "ERR|Export não encontrado";

            String fullPath = export.getFsPath() + path;
            if (!fs.exists(fullPath)) {
                return "ERR|Não encontrado: " + path;
            }

            FileHandle handle = new FileHandle(fullPath, fs.isDirectory(fullPath));
            fileHandles.put(handle.getHandleId(), handle);

            return "OK|" + handle.getHandleId() + "|" + (handle.isDirectory() ? "DIR" : "FILE");
        }

        private String handleRead(String[] parts, String clientHost) {
            if (parts.length < 2) return "ERR|Uso: READ|handleId";

            String handleId = parts[1];
            FileHandle handle = fileHandles.get(handleId);
            if (handle == null) return "ERR|Handle inválido";
            if (handle.isDirectory()) return "ERR|É um diretório";

            var optContent = fs.readFile(handle.getPath());
            if (optContent.isEmpty()) {
                return "ERR|Não foi possível ler arquivo";
            }
            byte[] content = optContent.get();
            String encoded = Base64.getEncoder().encodeToString(content);

            // Atualizar estatísticas do mount
            updateMountStats(clientHost, content.length, 0);

            return "OK|" + encoded;
        }

        private String handleWrite(String[] parts, String clientHost) {
            if (parts.length < 3) return "ERR|Uso: WRITE|handleId|contentBase64";

            String handleId = parts[1];
            FileHandle handle = fileHandles.get(handleId);
            if (handle == null) return "ERR|Handle inválido";

            // Verificar se export é read-only
            for (Export export : exports.values()) {
                if (handle.getPath().startsWith(export.getFsPath()) && export.isReadOnly()) {
                    return "ERR|Export é read-only";
                }
            }

            byte[] content;
            try {
                content = Base64.getDecoder().decode(parts[2]);
            } catch (Exception e) {
                content = parts[2].getBytes(StandardCharsets.UTF_8);
            }

            fs.createOrOverwriteFile(handle.getPath(), content);
            updateMountStats(clientHost, 0, content.length);

            return "OK|Escrito " + content.length + " bytes";
        }

        private String handleCreate(String[] parts, String clientHost) {
            if (parts.length < 3) return "ERR|Uso: CREATE|exportPath|path";

            String exportPath = parts[1];
            String path = parts[2];

            Export export = exports.get(exportPath);
            if (export == null) return "ERR|Export não encontrado";
            if (export.isReadOnly()) return "ERR|Export é read-only";

            String fullPath = export.getFsPath() + path;
            fs.createOrOverwriteFile(fullPath, new byte[0]);

            FileHandle handle = new FileHandle(fullPath, false);
            fileHandles.put(handle.getHandleId(), handle);

            return "OK|" + handle.getHandleId();
        }

        private String handleRemove(String[] parts, String clientHost) {
            if (parts.length < 2) return "ERR|Uso: REMOVE|handleId";

            String handleId = parts[1];
            FileHandle handle = fileHandles.remove(handleId);
            if (handle == null) return "ERR|Handle inválido";

            fs.deleteFile(handle.getPath());
            return "OK|Removido";
        }

        private String handleMkdir(String[] parts, String clientHost) {
            if (parts.length < 3) return "ERR|Uso: MKDIR|exportPath|path";

            String exportPath = parts[1];
            String path = parts[2];

            Export export = exports.get(exportPath);
            if (export == null) return "ERR|Export não encontrado";
            if (export.isReadOnly()) return "ERR|Export é read-only";

            String fullPath = export.getFsPath() + path;
            fs.createDirectory(fullPath);

            return "OK|Diretório criado";
        }

        private String handleRmdir(String[] parts, String clientHost) {
            if (parts.length < 3) return "ERR|Uso: RMDIR|exportPath|path";

            String exportPath = parts[1];
            String path = parts[2];

            Export export = exports.get(exportPath);
            if (export == null) return "ERR|Export não encontrado";
            if (export.isReadOnly()) return "ERR|Export é read-only";

            String fullPath = export.getFsPath() + path;
            fs.deleteDirectory(fullPath, false);

            return "OK|Diretório removido";
        }

        private String handleReaddir(String[] parts, String clientHost) {
            if (parts.length < 2) return "ERR|Uso: READDIR|handleId";

            String handleId = parts[1];
            FileHandle handle = fileHandles.get(handleId);
            if (handle == null) return "ERR|Handle inválido";
            if (!handle.isDirectory()) return "ERR|Não é um diretório";

            List<FileSystemSimulator.ListingEntry> entries = fs.list(handle.getPath());
            StringBuilder sb = new StringBuilder("OK");
            for (FileSystemSimulator.ListingEntry entry : entries) {
                sb.append("|").append(entry.directory() ? "D:" : "F:").append(entry.name());
            }
            return sb.toString();
        }

        private String handleGetattr(String[] parts, String clientHost) {
            if (parts.length < 2) return "ERR|Uso: GETATTR|handleId";

            String handleId = parts[1];
            FileHandle handle = fileHandles.get(handleId);
            if (handle == null) return "ERR|Handle inválido";

            String type = handle.isDirectory() ? "DIR" : "FILE";
            long size = 0;
            if (!handle.isDirectory() && fs.exists(handle.getPath())) {
                var opt = fs.readFile(handle.getPath());
                size = opt.isPresent() ? opt.get().length : 0;
            }

            return String.format("OK|type=%s|size=%d|path=%s", type, size, handle.getPath());
        }

        private String handleExports() {
            StringBuilder sb = new StringBuilder("OK");
            for (Export export : exports.values()) {
                sb.append("|").append(export.getPath());
            }
            return sb.toString();
        }

        private void updateMountStats(String clientHost, long bytesRead, long bytesWritten) {
            for (Mount mount : activeMounts.values()) {
                if (mount.getClientHost().equals(clientHost)) {
                    if (bytesRead > 0) mount.addRead(bytesRead);
                    if (bytesWritten > 0) mount.addWrite(bytesWritten);
                    break;
                }
            }
        }
    }

    public Collection<Mount> getActiveMounts() {
        return Collections.unmodifiableCollection(activeMounts.values());
    }

    public String getStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== NFS Server ===\n");
        sb.append("Port: ").append(port).append("\n");
        sb.append("Running: ").append(running).append("\n");
        sb.append("Total Operations: ").append(totalOperations.get()).append("\n");

        sb.append("\nExports:\n");
        for (Export export : exports.values()) {
            sb.append("  ").append(export).append("\n");
        }

        sb.append("\nActive Mounts:\n");
        if (activeMounts.isEmpty()) {
            sb.append("  (none)\n");
        } else {
            for (Mount mount : activeMounts.values()) {
                sb.append("  ").append(mount).append("\n");
            }
        }

        return sb.toString();
    }
}
