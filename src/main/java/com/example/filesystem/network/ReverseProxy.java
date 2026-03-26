package com.example.filesystem.network;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reverse Proxy (tipo Nginx) - roteia requisições baseado em host/path.
 */
public class ReverseProxy {

    public static class ProxyRule {
        private final String matchHost; // pode ser null para ignorar
        private final String matchPath; // prefix match
        private final String targetHost;
        private final int targetPort;
        private final String rewritePath; // pode ser null para manter o path original
        private final boolean stripPrefix;
        private final Map<String, String> addHeaders;
        private final int priority;

        public ProxyRule(String matchHost, String matchPath, String targetHost, int targetPort) {
            this(matchHost, matchPath, targetHost, targetPort, null, false, 0);
        }

        public ProxyRule(String matchHost, String matchPath, String targetHost, int targetPort,
                         String rewritePath, boolean stripPrefix, int priority) {
            this.matchHost = matchHost;
            this.matchPath = matchPath;
            this.targetHost = targetHost;
            this.targetPort = targetPort;
            this.rewritePath = rewritePath;
            this.stripPrefix = stripPrefix;
            this.addHeaders = new HashMap<>();
            this.priority = priority;
        }

        public boolean matches(String host, String path) {
            if (matchHost != null && !matchHost.equals(host) && !matchHost.equals("*")) {
                return false;
            }
            return path.startsWith(matchPath);
        }

        public String getTargetUrl(String originalPath) {
            String targetPath;
            if (rewritePath != null) {
                targetPath = rewritePath;
            } else if (stripPrefix) {
                targetPath = originalPath.substring(matchPath.length());
                if (!targetPath.startsWith("/")) targetPath = "/" + targetPath;
            } else {
                targetPath = originalPath;
            }
            return "http://" + targetHost + ":" + targetPort + targetPath;
        }

        public void addHeader(String name, String value) {
            addHeaders.put(name, value);
        }

        public Map<String, String> getAddHeaders() { return addHeaders; }
        public String getMatchHost() { return matchHost; }
        public String getMatchPath() { return matchPath; }
        public String getTargetHost() { return targetHost; }
        public int getTargetPort() { return targetPort; }
        public int getPriority() { return priority; }

        @Override
        public String toString() {
            return String.format("ProxyRule[%s%s -> %s:%d%s]",
                matchHost != null ? matchHost : "*",
                matchPath,
                targetHost, targetPort,
                rewritePath != null ? " (rewrite: " + rewritePath + ")" : "");
        }
    }

    public static class CacheEntry {
        private final String url;
        private final byte[] content;
        private final String contentType;
        private final int statusCode;
        private final Instant cachedAt;
        private final Duration ttl;
        private int hitCount;

        public CacheEntry(String url, byte[] content, String contentType, int statusCode, Duration ttl) {
            this.url = url;
            this.content = content;
            this.contentType = contentType;
            this.statusCode = statusCode;
            this.cachedAt = Instant.now();
            this.ttl = ttl;
            this.hitCount = 0;
        }

        public boolean isExpired() {
            return Instant.now().isAfter(cachedAt.plus(ttl));
        }

        public void hit() { hitCount++; }
        public byte[] getContent() { return content; }
        public String getContentType() { return contentType; }
        public int getStatusCode() { return statusCode; }
        public int getHitCount() { return hitCount; }
    }

    private final int port;
    private final List<ProxyRule> rules;
    private final Map<String, CacheEntry> cache;
    private final int maxCacheSize;
    private final Duration defaultCacheTtl;
    private final Set<String> cacheablePaths;
    private com.sun.net.httpserver.HttpServer server;
    private final HttpClient httpClient;
    private volatile boolean running;
    private final AtomicLong totalRequests;
    private final AtomicLong cacheHits;
    private Instant startTime;

    public ReverseProxy(int port) {
        this.port = port;
        this.rules = new CopyOnWriteArrayList<>();
        this.cache = new ConcurrentHashMap<>();
        this.maxCacheSize = 1000;
        this.defaultCacheTtl = Duration.ofMinutes(5);
        this.cacheablePaths = ConcurrentHashMap.newKeySet();
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.totalRequests = new AtomicLong(0);
        this.cacheHits = new AtomicLong(0);
    }

    public void addRule(ProxyRule rule) {
        rules.add(rule);
        rules.sort(Comparator.comparingInt(ProxyRule::getPriority).reversed());
    }

    public void addRule(String matchPath, String targetHost, int targetPort) {
        addRule(new ProxyRule(null, matchPath, targetHost, targetPort));
    }

    public void addRule(String matchHost, String matchPath, String targetHost, int targetPort) {
        addRule(new ProxyRule(matchHost, matchPath, targetHost, targetPort));
    }

    public void removeRule(String matchPath) {
        rules.removeIf(r -> r.getMatchPath().equals(matchPath));
    }

    public List<ProxyRule> getRules() {
        return Collections.unmodifiableList(rules);
    }

    public void enableCaching(String pathPrefix) {
        cacheablePaths.add(pathPrefix);
    }

    public void disableCaching(String pathPrefix) {
        cacheablePaths.remove(pathPrefix);
    }

    public void clearCache() {
        cache.clear();
    }

    public void start() throws IOException {
        server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", new ProxyHandler());
        server.setExecutor(Executors.newFixedThreadPool(20));
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

    public boolean isRunning() { return running; }
    public int getPort() { return port; }
    public long getTotalRequests() { return totalRequests.get(); }
    public long getCacheHits() { return cacheHits.get(); }
    public int getCacheSize() { return cache.size(); }
    public Instant getStartTime() { return startTime; }

    private class ProxyHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            totalRequests.incrementAndGet();
            String host = exchange.getRequestHeaders().getFirst("Host");
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();

            // Find matching rule
            ProxyRule rule = findRule(host, path);
            if (rule == null) {
                sendError(exchange, 404, "No proxy rule for: " + path);
                return;
            }

            // Check cache for GET requests
            String cacheKey = method + ":" + host + path;
            if (method.equals("GET") && isCacheable(path)) {
                CacheEntry cached = cache.get(cacheKey);
                if (cached != null && !cached.isExpired()) {
                    cached.hit();
                    cacheHits.incrementAndGet();
                    sendCachedResponse(exchange, cached);
                    return;
                }
            }

            // Forward request
            try {
                byte[] response = forwardRequest(exchange, rule, path);

                // Cache if applicable
                if (method.equals("GET") && isCacheable(path) && response != null) {
                    if (cache.size() < maxCacheSize) {
                        String contentType = exchange.getResponseHeaders().getFirst("Content-Type");
                        cache.put(cacheKey, new CacheEntry(cacheKey, response,
                            contentType != null ? contentType : "application/octet-stream",
                            200, defaultCacheTtl));
                    }
                }
            } catch (Exception e) {
                sendError(exchange, 502, "Backend error: " + e.getMessage());
            }
        }

        private byte[] forwardRequest(HttpExchange exchange, ProxyRule rule, String path) throws Exception {
            String targetUrl = rule.getTargetUrl(path);
            String query = exchange.getRequestURI().getQuery();
            if (query != null) {
                targetUrl += "?" + query;
            }

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(targetUrl))
                .timeout(Duration.ofSeconds(30));

            // Copy headers
            exchange.getRequestHeaders().forEach((key, values) -> {
                if (!key.equalsIgnoreCase("Host") && !key.equalsIgnoreCase("Content-Length")) {
                    values.forEach(v -> requestBuilder.header(key, v));
                }
            });

            // Add custom headers
            for (Map.Entry<String, String> header : rule.getAddHeaders().entrySet()) {
                requestBuilder.header(header.getKey(), header.getValue());
            }

            // Add proxy headers
            requestBuilder.header("X-Forwarded-For", exchange.getRemoteAddress().getAddress().getHostAddress());
            requestBuilder.header("X-Real-IP", exchange.getRemoteAddress().getAddress().getHostAddress());

            byte[] body = exchange.getRequestBody().readAllBytes();

            String method = exchange.getRequestMethod();
            switch (method) {
                case "GET" -> requestBuilder.GET();
                case "DELETE" -> requestBuilder.DELETE();
                case "POST" -> requestBuilder.POST(HttpRequest.BodyPublishers.ofByteArray(body));
                case "PUT" -> requestBuilder.PUT(HttpRequest.BodyPublishers.ofByteArray(body));
                default -> requestBuilder.method(method, HttpRequest.BodyPublishers.ofByteArray(body));
            }

            HttpResponse<byte[]> response = httpClient.send(requestBuilder.build(),
                HttpResponse.BodyHandlers.ofByteArray());

            // Forward response headers
            response.headers().map().forEach((key, values) -> {
                if (!key.equalsIgnoreCase("Transfer-Encoding")) {
                    values.forEach(v -> exchange.getResponseHeaders().add(key, v));
                }
            });

            byte[] responseBody = response.body();
            exchange.sendResponseHeaders(response.statusCode(), responseBody.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBody);
            }

            return responseBody;
        }

        private void sendCachedResponse(HttpExchange exchange, CacheEntry cached) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", cached.getContentType());
            exchange.getResponseHeaders().set("X-Cache", "HIT");
            exchange.sendResponseHeaders(cached.getStatusCode(), cached.getContent().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(cached.getContent());
            }
        }

        private void sendError(HttpExchange exchange, int status, String message) throws IOException {
            String json = String.format("{\"error\": \"%s\", \"status\": %d}", message, status);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private ProxyRule findRule(String host, String path) {
        for (ProxyRule rule : rules) {
            if (rule.matches(host, path)) {
                return rule;
            }
        }
        return null;
    }

    private boolean isCacheable(String path) {
        for (String prefix : cacheablePaths) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public String getStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Reverse Proxy ===\n");
        sb.append("Port: ").append(port).append("\n");
        sb.append("Running: ").append(running).append("\n");
        sb.append("Total Requests: ").append(totalRequests.get()).append("\n");
        sb.append("Cache Hits: ").append(cacheHits.get()).append("\n");
        sb.append("Cache Size: ").append(cache.size()).append("/").append(maxCacheSize).append("\n");
        sb.append("\nRules:\n");
        for (ProxyRule rule : rules) {
            sb.append("  ").append(rule).append("\n");
        }
        sb.append("\nCacheable Paths: ").append(cacheablePaths).append("\n");
        return sb.toString();
    }
}
