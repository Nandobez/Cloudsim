package com.example.filesystem.network;

import java.io.*;
import java.net.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Load Balancer para distribuir requisições entre múltiplos backends.
 */
public class LoadBalancer {

    public enum Algorithm {
        ROUND_ROBIN,
        LEAST_CONNECTIONS,
        IP_HASH,
        RANDOM,
        WEIGHTED_ROUND_ROBIN
    }

    public static class Backend {
        private final String name;
        private final String host;
        private final int port;
        private int weight;
        private volatile boolean healthy;
        private final AtomicInteger activeConnections;
        private final AtomicLong totalRequests;
        private final AtomicLong failedRequests;
        private Instant lastHealthCheck;

        public Backend(String name, String host, int port, int weight) {
            this.name = name;
            this.host = host;
            this.port = port;
            this.weight = weight;
            this.healthy = true;
            this.activeConnections = new AtomicInteger(0);
            this.totalRequests = new AtomicLong(0);
            this.failedRequests = new AtomicLong(0);
        }

        public Backend(String name, String host, int port) {
            this(name, host, port, 1);
        }

        public String getName() { return name; }
        public String getHost() { return host; }
        public int getPort() { return port; }
        public int getWeight() { return weight; }
        public void setWeight(int weight) { this.weight = weight; }
        public boolean isHealthy() { return healthy; }
        public void setHealthy(boolean healthy) { this.healthy = healthy; }
        public int getActiveConnections() { return activeConnections.get(); }
        public void incrementConnections() { activeConnections.incrementAndGet(); totalRequests.incrementAndGet(); }
        public void decrementConnections() { activeConnections.decrementAndGet(); }
        public void incrementFailed() { failedRequests.incrementAndGet(); }
        public long getTotalRequests() { return totalRequests.get(); }
        public long getFailedRequests() { return failedRequests.get(); }
        public Instant getLastHealthCheck() { return lastHealthCheck; }
        public void setLastHealthCheck(Instant time) { this.lastHealthCheck = time; }

        public String getAddress() {
            return host + ":" + port;
        }

        @Override
        public String toString() {
            return String.format("Backend[%s -> %s:%d, weight=%d, healthy=%s, connections=%d, requests=%d]",
                name, host, port, weight, healthy, activeConnections.get(), totalRequests.get());
        }
    }

    private final String name;
    private final int listenPort;
    private final List<Backend> backends;
    private Algorithm algorithm;
    private final AtomicInteger roundRobinIndex;
    private final AtomicLong totalRequests;
    private ServerSocket serverSocket;
    private ExecutorService executor;
    private ScheduledExecutorService healthChecker;
    private volatile boolean running;
    private final int healthCheckIntervalSeconds;
    private final int connectionTimeout;
    private Instant startTime;

    public LoadBalancer(String name, int listenPort, Algorithm algorithm) {
        this.name = name;
        this.listenPort = listenPort;
        this.algorithm = algorithm;
        this.backends = new CopyOnWriteArrayList<>();
        this.roundRobinIndex = new AtomicInteger(0);
        this.totalRequests = new AtomicLong(0);
        this.healthCheckIntervalSeconds = 10;
        this.connectionTimeout = 5000;
    }

    public void addBackend(Backend backend) {
        backends.add(backend);
    }

    public void addBackend(String name, String host, int port) {
        backends.add(new Backend(name, host, port));
    }

    public void addBackend(String name, String host, int port, int weight) {
        backends.add(new Backend(name, host, port, weight));
    }

    public void removeBackend(String name) {
        backends.removeIf(b -> b.getName().equals(name));
    }

    public List<Backend> getBackends() {
        return Collections.unmodifiableList(backends);
    }

    public Backend selectBackend(String clientIP) {
        List<Backend> healthyBackends = backends.stream()
            .filter(Backend::isHealthy)
            .toList();

        if (healthyBackends.isEmpty()) {
            return null;
        }

        return switch (algorithm) {
            case ROUND_ROBIN -> selectRoundRobin(healthyBackends);
            case LEAST_CONNECTIONS -> selectLeastConnections(healthyBackends);
            case IP_HASH -> selectIPHash(healthyBackends, clientIP);
            case RANDOM -> selectRandom(healthyBackends);
            case WEIGHTED_ROUND_ROBIN -> selectWeightedRoundRobin(healthyBackends);
        };
    }

    private Backend selectRoundRobin(List<Backend> backends) {
        int index = roundRobinIndex.getAndIncrement() % backends.size();
        return backends.get(index);
    }

    private Backend selectLeastConnections(List<Backend> backends) {
        return backends.stream()
            .min(Comparator.comparingInt(Backend::getActiveConnections))
            .orElse(null);
    }

    private Backend selectIPHash(List<Backend> backends, String clientIP) {
        int hash = clientIP != null ? clientIP.hashCode() : 0;
        int index = Math.abs(hash) % backends.size();
        return backends.get(index);
    }

    private Backend selectRandom(List<Backend> backends) {
        return backends.get(ThreadLocalRandom.current().nextInt(backends.size()));
    }

    private Backend selectWeightedRoundRobin(List<Backend> backends) {
        int totalWeight = backends.stream().mapToInt(Backend::getWeight).sum();
        int random = ThreadLocalRandom.current().nextInt(totalWeight);

        int current = 0;
        for (Backend backend : backends) {
            current += backend.getWeight();
            if (random < current) {
                return backend;
            }
        }
        return backends.get(0);
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(listenPort);
        executor = Executors.newCachedThreadPool();
        running = true;
        startTime = Instant.now();

        // Health checker
        healthChecker = Executors.newSingleThreadScheduledExecutor();
        healthChecker.scheduleAtFixedRate(this::checkHealth, 0, healthCheckIntervalSeconds, TimeUnit.SECONDS);

        // Accept connections
        executor.submit(() -> {
            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    executor.submit(() -> handleConnection(clientSocket));
                } catch (IOException e) {
                    if (running) {
                        System.err.println("LB accept error: " + e.getMessage());
                    }
                }
            }
        });
    }

    public void stop() {
        running = false;
        if (healthChecker != null) healthChecker.shutdownNow();
        if (executor != null) executor.shutdownNow();
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {}
    }

    private void handleConnection(Socket clientSocket) {
        String clientIP = clientSocket.getInetAddress().getHostAddress();
        Backend backend = selectBackend(clientIP);

        if (backend == null) {
            try {
                PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true);
                out.println("ERR|No healthy backends available");
                clientSocket.close();
            } catch (IOException ignored) {}
            return;
        }

        backend.incrementConnections();
        totalRequests.incrementAndGet();

        try (Socket backendSocket = new Socket()) {
            backendSocket.connect(new InetSocketAddress(backend.getHost(), backend.getPort()), connectionTimeout);

            // Proxy bidirecional
            executor.submit(() -> proxy(clientSocket, backendSocket));
            proxy(backendSocket, clientSocket);

        } catch (IOException e) {
            backend.incrementFailed();
        } finally {
            backend.decrementConnections();
            try { clientSocket.close(); } catch (IOException ignored) {}
        }
    }

    private void proxy(Socket from, Socket to) {
        try (InputStream in = from.getInputStream();
             OutputStream out = to.getOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException ignored) {}
    }

    private void checkHealth() {
        for (Backend backend : backends) {
            boolean wasHealthy = backend.isHealthy();
            boolean isHealthy = checkBackendHealth(backend);
            backend.setHealthy(isHealthy);
            backend.setLastHealthCheck(Instant.now());

            if (wasHealthy && !isHealthy) {
                System.out.println("[LB] Backend " + backend.getName() + " is now UNHEALTHY");
            } else if (!wasHealthy && isHealthy) {
                System.out.println("[LB] Backend " + backend.getName() + " is now HEALTHY");
            }
        }
    }

    private boolean checkBackendHealth(Backend backend) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(backend.getHost(), backend.getPort()), connectionTimeout);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public boolean isRunning() { return running; }
    public String getName() { return name; }
    public int getListenPort() { return listenPort; }
    public Algorithm getAlgorithm() { return algorithm; }
    public void setAlgorithm(Algorithm algorithm) { this.algorithm = algorithm; }
    public long getTotalRequests() { return totalRequests.get(); }
    public Instant getStartTime() { return startTime; }

    public String getStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Load Balancer: ").append(name).append(" ===\n");
        sb.append("Listen Port: ").append(listenPort).append("\n");
        sb.append("Algorithm: ").append(algorithm).append("\n");
        sb.append("Total Requests: ").append(totalRequests.get()).append("\n");
        sb.append("Backends:\n");
        for (Backend b : backends) {
            sb.append("  ").append(b).append("\n");
        }
        return sb.toString();
    }
}
