package com.example.filesystem.network;

import com.example.filesystem.FileSystemSimulator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Servidor HTTP para API REST do FileSystem.
 */
public class HttpServer {
    private com.sun.net.httpserver.HttpServer server;
    private final int port;
    private final String bindAddress;
    private final FileSystemSimulator fs;
    private final AtomicLong requestCount;
    private final AtomicLong bytesServed;
    private boolean running;
    private Instant startTime;
    private final List<RequestLog> requestLogs;
    private final int maxLogs;

    public HttpServer(int port, String bindAddress, FileSystemSimulator fs) {
        this.port = port;
        this.bindAddress = bindAddress;
        this.fs = fs;
        this.requestCount = new AtomicLong(0);
        this.bytesServed = new AtomicLong(0);
        this.running = false;
        this.requestLogs = Collections.synchronizedList(new ArrayList<>());
        this.maxLogs = 100;
    }

    public void start() throws IOException {
        server = com.sun.net.httpserver.HttpServer.create(
            new InetSocketAddress(bindAddress, port), 0);

        // API Routes
        server.createContext("/", new RootHandler());
        server.createContext("/api/v1/files", new FilesHandler());
        server.createContext("/api/v1/dirs", new DirsHandler());
        server.createContext("/api/v1/info", new InfoHandler());
        server.createContext("/api/v1/health", new HealthHandler());
        server.createContext("/api/v1/tree", new TreeHandler());

        server.setExecutor(Executors.newFixedThreadPool(10));
        server.start();
        running = true;
        startTime = Instant.now();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            running = false;
        }
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        return port;
    }

    public String getBindAddress() {
        return bindAddress;
    }

    public long getRequestCount() {
        return requestCount.get();
    }

    public long getBytesServed() {
        return bytesServed.get();
    }

    public Instant getStartTime() {
        return startTime;
    }

    public List<RequestLog> getRecentLogs() {
        return new ArrayList<>(requestLogs);
    }

    private void logRequest(String method, String path, int status, long bytes) {
        requestCount.incrementAndGet();
        bytesServed.addAndGet(bytes);

        RequestLog log = new RequestLog(method, path, status, bytes);
        requestLogs.add(log);
        while (requestLogs.size() > maxLogs) {
            requestLogs.remove(0);
        }
    }

    private void sendResponse(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
        logRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), status, bytes.length);
    }

    private void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        sendResponse(exchange, status, "application/json", json);
    }

    private void sendError(HttpExchange exchange, int status, String message) throws IOException {
        String json = String.format("{\"error\": \"%s\", \"status\": %d}", escapeJson(message), status);
        sendJson(exchange, status, json);
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String getPathParam(String uri, String prefix) {
        String path = uri.substring(prefix.length());
        if (path.isEmpty() || path.equals("/")) {
            return "/";
        }
        try {
            return URLDecoder.decode(path, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return path;
        }
    }

    private String readBody(HttpExchange exchange) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    // ==================== Handlers ====================

    class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String json = """
                {
                    "service": "FileSystem API",
                    "version": "1.0",
                    "endpoints": [
                        {"method": "GET", "path": "/api/v1/files/{path}", "description": "Read file content"},
                        {"method": "POST", "path": "/api/v1/files/{path}", "description": "Create/update file"},
                        {"method": "DELETE", "path": "/api/v1/files/{path}", "description": "Delete file"},
                        {"method": "GET", "path": "/api/v1/dirs/{path}", "description": "List directory"},
                        {"method": "POST", "path": "/api/v1/dirs/{path}", "description": "Create directory"},
                        {"method": "DELETE", "path": "/api/v1/dirs/{path}", "description": "Delete directory"},
                        {"method": "GET", "path": "/api/v1/tree", "description": "Full filesystem tree"},
                        {"method": "GET", "path": "/api/v1/info", "description": "Disk info"},
                        {"method": "GET", "path": "/api/v1/health", "description": "Health check"}
                    ]
                }
                """;
            sendJson(exchange, 200, json);
        }
    }

    class FilesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = getPathParam(exchange.getRequestURI().getPath(), "/api/v1/files");

            try {
                switch (method) {
                    case "GET" -> handleGetFile(exchange, path);
                    case "POST", "PUT" -> handleCreateFile(exchange, path);
                    case "DELETE" -> handleDeleteFile(exchange, path);
                    case "OPTIONS" -> handleOptions(exchange);
                    default -> sendError(exchange, 405, "Método não permitido");
                }
            } catch (Exception e) {
                sendError(exchange, 500, e.getMessage());
            }
        }

        private void handleGetFile(HttpExchange exchange, String path) throws IOException {
            if (!fs.exists(path)) {
                sendError(exchange, 404, "Arquivo não encontrado: " + path);
                return;
            }
            if (!fs.isFile(path)) {
                sendError(exchange, 400, "Caminho não é um arquivo: " + path);
                return;
            }
            var optContent = fs.readFile(path);
            if (optContent.isEmpty()) {
                sendError(exchange, 404, "Não foi possível ler o arquivo: " + path);
                return;
            }
            String content = new String(optContent.get(), StandardCharsets.UTF_8);
            String json = String.format("{\"path\": \"%s\", \"content\": \"%s\", \"size\": %d}",
                escapeJson(path), escapeJson(content), content.length());
            sendJson(exchange, 200, json);
        }

        private void handleCreateFile(HttpExchange exchange, String path) throws IOException {
            String body = readBody(exchange);
            fs.createOrOverwriteFile(path, body.getBytes(StandardCharsets.UTF_8));
            String json = String.format("{\"message\": \"Arquivo criado\", \"path\": \"%s\", \"size\": %d}",
                escapeJson(path), body.length());
            sendJson(exchange, 201, json);
        }

        private void handleDeleteFile(HttpExchange exchange, String path) throws IOException {
            if (!fs.exists(path)) {
                sendError(exchange, 404, "Arquivo não encontrado: " + path);
                return;
            }
            fs.deleteFile(path);
            sendJson(exchange, 200, "{\"message\": \"Arquivo deletado\", \"path\": \"" + escapeJson(path) + "\"}");
        }

        private void handleOptions(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
            sendResponse(exchange, 204, "text/plain", "");
        }
    }

    class DirsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = getPathParam(exchange.getRequestURI().getPath(), "/api/v1/dirs");

            try {
                switch (method) {
                    case "GET" -> handleListDir(exchange, path);
                    case "POST" -> handleCreateDir(exchange, path);
                    case "DELETE" -> handleDeleteDir(exchange, path);
                    default -> sendError(exchange, 405, "Método não permitido");
                }
            } catch (Exception e) {
                sendError(exchange, 500, e.getMessage());
            }
        }

        private void handleListDir(HttpExchange exchange, String path) throws IOException {
            if (!fs.exists(path)) {
                sendError(exchange, 404, "Diretório não encontrado: " + path);
                return;
            }
            if (!fs.isDirectory(path)) {
                sendError(exchange, 400, "Caminho não é um diretório: " + path);
                return;
            }

            List<FileSystemSimulator.ListingEntry> entries = fs.list(path);
            StringBuilder sb = new StringBuilder();
            sb.append("{\"path\": \"").append(escapeJson(path)).append("\", \"entries\": [");

            for (int i = 0; i < entries.size(); i++) {
                FileSystemSimulator.ListingEntry entry = entries.get(i);
                if (i > 0) sb.append(", ");
                sb.append(String.format(
                    "{\"name\": \"%s\", \"type\": \"%s\", \"size\": %d}",
                    escapeJson(entry.name()),
                    entry.directory() ? "directory" : "file",
                    entry.size()
                ));
            }
            sb.append("]}");
            sendJson(exchange, 200, sb.toString());
        }

        private void handleCreateDir(HttpExchange exchange, String path) throws IOException {
            fs.createDirectory(path);
            sendJson(exchange, 201, "{\"message\": \"Diretório criado\", \"path\": \"" + escapeJson(path) + "\"}");
        }

        private void handleDeleteDir(HttpExchange exchange, String path) throws IOException {
            String query = exchange.getRequestURI().getQuery();
            boolean recursive = query != null && query.contains("recursive=true");

            if (!fs.exists(path)) {
                sendError(exchange, 404, "Diretório não encontrado: " + path);
                return;
            }
            fs.deleteDirectory(path, recursive);
            sendJson(exchange, 200, "{\"message\": \"Diretório deletado\", \"path\": \"" + escapeJson(path) + "\"}");
        }
    }

    class InfoHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            FileSystemSimulator.DiskInfo info = fs.diskInfo();
            String json = String.format("""
                {
                    "totalClusters": %d,
                    "clusterSize": %d,
                    "totalBytes": %d,
                    "usedBytes": %d,
                    "freeBytes": %d
                }
                """,
                info.clusterCount(),
                info.clusterSize(),
                info.totalBytes(),
                info.usedBytes(),
                info.freeBytes()
            );
            sendJson(exchange, 200, json);
        }
    }

    class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String json = String.format("""
                {
                    "status": "healthy",
                    "uptime": "%s",
                    "requests": %d,
                    "bytesServed": %d
                }
                """,
                startTime != null ? java.time.Duration.between(startTime, Instant.now()).toString() : "N/A",
                requestCount.get(),
                bytesServed.get()
            );
            sendJson(exchange, 200, json);
        }
    }

    class TreeHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String tree = fs.dumpTree();
            String json = "{\"tree\": \"" + escapeJson(tree) + "\"}";
            sendJson(exchange, 200, json);
        }
    }

    // ==================== Request Log ====================

    public static class RequestLog {
        private final Instant timestamp;
        private final String method;
        private final String path;
        private final int status;
        private final long bytes;

        public RequestLog(String method, String path, int status, long bytes) {
            this.timestamp = Instant.now();
            this.method = method;
            this.path = path;
            this.status = status;
            this.bytes = bytes;
        }

        @Override
        public String toString() {
            return String.format("[%s] %s %s -> %d (%d bytes)",
                timestamp, method, path, status, bytes);
        }
    }
}
