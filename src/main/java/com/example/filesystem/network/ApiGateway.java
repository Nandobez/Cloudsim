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
import java.util.regex.Pattern;

/**
 * API Gateway com roteamento, rate limiting, autenticação e mais.
 */
public class ApiGateway {

    public static class Route {
        private final String pathPattern;
        private final Pattern regex;
        private final String targetHost;
        private final int targetPort;
        private final String targetPath;
        private final Set<String> allowedMethods;
        private final boolean requiresAuth;
        private final int rateLimit; // requests per minute, 0 = unlimited

        public Route(String pathPattern, String targetHost, int targetPort, String targetPath) {
            this.pathPattern = pathPattern;
            this.regex = Pattern.compile(pathPattern.replace("*", ".*"));
            this.targetHost = targetHost;
            this.targetPort = targetPort;
            this.targetPath = targetPath;
            this.allowedMethods = new HashSet<>(Arrays.asList("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
            this.requiresAuth = false;
            this.rateLimit = 0;
        }

        public Route(String pathPattern, String targetHost, int targetPort, String targetPath,
                     Set<String> allowedMethods, boolean requiresAuth, int rateLimit) {
            this.pathPattern = pathPattern;
            this.regex = Pattern.compile(pathPattern.replace("*", ".*"));
            this.targetHost = targetHost;
            this.targetPort = targetPort;
            this.targetPath = targetPath;
            this.allowedMethods = allowedMethods;
            this.requiresAuth = requiresAuth;
            this.rateLimit = rateLimit;
        }

        public boolean matches(String path) {
            return regex.matcher(path).matches();
        }

        public String getTargetUrl(String originalPath) {
            String suffix = "";
            if (pathPattern.endsWith("*")) {
                String prefix = pathPattern.substring(0, pathPattern.length() - 1);
                if (originalPath.startsWith(prefix)) {
                    suffix = originalPath.substring(prefix.length());
                }
            }
            return "http://" + targetHost + ":" + targetPort + targetPath + suffix;
        }

        public String getPathPattern() { return pathPattern; }
        public String getTargetHost() { return targetHost; }
        public int getTargetPort() { return targetPort; }
        public String getTargetPath() { return targetPath; }
        public Set<String> getAllowedMethods() { return allowedMethods; }
        public boolean requiresAuth() { return requiresAuth; }
        public int getRateLimit() { return rateLimit; }

        @Override
        public String toString() {
            return String.format("Route[%s -> %s:%d%s, methods=%s, auth=%s, rateLimit=%d]",
                pathPattern, targetHost, targetPort, targetPath, allowedMethods, requiresAuth, rateLimit);
        }
    }

    public static class ApiKey {
        private final String key;
        private final String name;
        private final Set<String> allowedRoutes;
        private final int rateLimit;
        private final Instant createdAt;
        private final Instant expiresAt;
        private boolean enabled;

        public ApiKey(String name, int rateLimit, Duration validFor) {
            this.key = UUID.randomUUID().toString().replace("-", "");
            this.name = name;
            this.allowedRoutes = new HashSet<>();
            this.rateLimit = rateLimit;
            this.createdAt = Instant.now();
            this.expiresAt = validFor != null ? createdAt.plus(validFor) : null;
            this.enabled = true;
        }

        public String getKey() { return key; }
        public String getName() { return name; }
        public int getRateLimit() { return rateLimit; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Instant getCreatedAt() { return createdAt; }
        public Instant getExpiresAt() { return expiresAt; }

        public void allowRoute(String routePattern) { allowedRoutes.add(routePattern); }
        public boolean canAccessRoute(String path) {
            if (allowedRoutes.isEmpty()) return true;
            for (String pattern : allowedRoutes) {
                if (path.startsWith(pattern.replace("*", ""))) return true;
            }
            return false;
        }

        public boolean isValid() {
            return enabled && (expiresAt == null || Instant.now().isBefore(expiresAt));
        }

        @Override
        public String toString() {
            return String.format("ApiKey[%s, name=%s, rateLimit=%d, enabled=%s]",
                key.substring(0, 8) + "...", name, rateLimit, enabled);
        }
    }

    private final int port;
    private final List<Route> routes;
    private final Map<String, ApiKey> apiKeys;
    private final Map<String, RateLimiter> rateLimiters;
    private com.sun.net.httpserver.HttpServer server;
    private final HttpClient httpClient;
    private volatile boolean running;
    private final AtomicLong totalRequests;
    private final AtomicLong blockedRequests;
    private Instant startTime;

    public ApiGateway(int port) {
        this.port = port;
        this.routes = new CopyOnWriteArrayList<>();
        this.apiKeys = new ConcurrentHashMap<>();
        this.rateLimiters = new ConcurrentHashMap<>();
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.totalRequests = new AtomicLong(0);
        this.blockedRequests = new AtomicLong(0);
    }

    public void addRoute(Route route) {
        routes.add(route);
    }

    public void addRoute(String pathPattern, String targetHost, int targetPort, String targetPath) {
        routes.add(new Route(pathPattern, targetHost, targetPort, targetPath));
    }

    public void removeRoute(String pathPattern) {
        routes.removeIf(r -> r.getPathPattern().equals(pathPattern));
    }

    public List<Route> getRoutes() {
        return Collections.unmodifiableList(routes);
    }

    public ApiKey createApiKey(String name, int rateLimit, Duration validFor) {
        ApiKey apiKey = new ApiKey(name, rateLimit, validFor);
        apiKeys.put(apiKey.getKey(), apiKey);
        return apiKey;
    }

    public ApiKey createApiKey(String name) {
        return createApiKey(name, 100, null);
    }

    public void revokeApiKey(String key) {
        ApiKey apiKey = apiKeys.get(key);
        if (apiKey != null) {
            apiKey.setEnabled(false);
        }
    }

    public ApiKey getApiKey(String key) {
        return apiKeys.get(key);
    }

    public Collection<ApiKey> getApiKeys() {
        return Collections.unmodifiableCollection(apiKeys.values());
    }

    public void start() throws IOException {
        server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", new GatewayHandler());
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
    public long getBlockedRequests() { return blockedRequests.get(); }
    public Instant getStartTime() { return startTime; }

    private class GatewayHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            totalRequests.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            String clientIP = exchange.getRemoteAddress().getAddress().getHostAddress();

            // Find matching route
            Route route = findRoute(path);
            if (route == null) {
                sendError(exchange, 404, "No route found for: " + path);
                return;
            }

            // Check method
            if (!route.getAllowedMethods().contains(method)) {
                sendError(exchange, 405, "Method not allowed: " + method);
                blockedRequests.incrementAndGet();
                return;
            }

            // Check API key if route requires auth
            String apiKeyHeader = exchange.getRequestHeaders().getFirst("X-API-Key");
            if (route.requiresAuth()) {
                if (apiKeyHeader == null) {
                    sendError(exchange, 401, "API key required");
                    blockedRequests.incrementAndGet();
                    return;
                }
                ApiKey apiKey = apiKeys.get(apiKeyHeader);
                if (apiKey == null || !apiKey.isValid()) {
                    sendError(exchange, 401, "Invalid or expired API key");
                    blockedRequests.incrementAndGet();
                    return;
                }
                if (!apiKey.canAccessRoute(path)) {
                    sendError(exchange, 403, "API key not authorized for this route");
                    blockedRequests.incrementAndGet();
                    return;
                }
            }

            // Rate limiting
            String rateLimitKey = apiKeyHeader != null ? apiKeyHeader : clientIP;
            int limit = route.getRateLimit();
            if (apiKeyHeader != null) {
                ApiKey apiKey = apiKeys.get(apiKeyHeader);
                if (apiKey != null) {
                    limit = Math.max(limit, apiKey.getRateLimit());
                }
            }

            if (limit > 0) {
                final int finalLimit = limit; // Cópia final para uso no lambda
                RateLimiter limiter = rateLimiters.computeIfAbsent(rateLimitKey,
                    k -> new RateLimiter(finalLimit, Duration.ofMinutes(1)));
                if (!limiter.tryAcquire()) {
                    sendError(exchange, 429, "Rate limit exceeded");
                    blockedRequests.incrementAndGet();
                    return;
                }
            }

            // Forward request
            try {
                forwardRequest(exchange, route, path);
            } catch (Exception e) {
                sendError(exchange, 502, "Backend error: " + e.getMessage());
            }
        }

        private void forwardRequest(HttpExchange exchange, Route route, String path) throws Exception {
            String targetUrl = route.getTargetUrl(path);
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

            // Add gateway headers
            requestBuilder.header("X-Forwarded-For", exchange.getRemoteAddress().getAddress().getHostAddress());
            requestBuilder.header("X-Forwarded-Host", exchange.getRequestHeaders().getFirst("Host"));

            // Read body if present
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

            // Forward response
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

    private Route findRoute(String path) {
        for (Route route : routes) {
            if (route.matches(path)) {
                return route;
            }
        }
        return null;
    }

    // Simple rate limiter
    private static class RateLimiter {
        private final int maxRequests;
        private final Duration window;
        private final Queue<Instant> requests;

        public RateLimiter(int maxRequests, Duration window) {
            this.maxRequests = maxRequests;
            this.window = window;
            this.requests = new ConcurrentLinkedQueue<>();
        }

        public synchronized boolean tryAcquire() {
            Instant now = Instant.now();
            Instant windowStart = now.minus(window);

            // Remove old requests
            while (!requests.isEmpty() && requests.peek().isBefore(windowStart)) {
                requests.poll();
            }

            if (requests.size() < maxRequests) {
                requests.add(now);
                return true;
            }
            return false;
        }
    }

    public String getStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== API Gateway ===\n");
        sb.append("Port: ").append(port).append("\n");
        sb.append("Running: ").append(running).append("\n");
        sb.append("Total Requests: ").append(totalRequests.get()).append("\n");
        sb.append("Blocked Requests: ").append(blockedRequests.get()).append("\n");
        sb.append("\nRoutes:\n");
        for (Route route : routes) {
            sb.append("  ").append(route).append("\n");
        }
        sb.append("\nAPI Keys:\n");
        for (ApiKey key : apiKeys.values()) {
            sb.append("  ").append(key).append("\n");
        }
        return sb.toString();
    }
}
