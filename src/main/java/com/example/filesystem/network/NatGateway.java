package com.example.filesystem.network;

import java.io.*;
import java.net.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * NAT Gateway simulado - permite que IPs privados acessem a "internet".
 */
public class NatGateway {

    public static class NatEntry {
        private final String privateIP;
        private final int privatePort;
        private final String publicIP;
        private final int publicPort;
        private final Protocol protocol;
        private final Instant createdAt;
        private Instant lastUsed;
        private final AtomicLong bytesIn;
        private final AtomicLong bytesOut;

        public NatEntry(String privateIP, int privatePort, String publicIP, int publicPort, Protocol protocol) {
            this.privateIP = privateIP;
            this.privatePort = privatePort;
            this.publicIP = publicIP;
            this.publicPort = publicPort;
            this.protocol = protocol;
            this.createdAt = Instant.now();
            this.lastUsed = Instant.now();
            this.bytesIn = new AtomicLong(0);
            this.bytesOut = new AtomicLong(0);
        }

        public String getPrivateIP() { return privateIP; }
        public int getPrivatePort() { return privatePort; }
        public String getPublicIP() { return publicIP; }
        public int getPublicPort() { return publicPort; }
        public Protocol getProtocol() { return protocol; }
        public Instant getCreatedAt() { return createdAt; }
        public Instant getLastUsed() { return lastUsed; }
        public void touch() { this.lastUsed = Instant.now(); }
        public void addBytesIn(long bytes) { bytesIn.addAndGet(bytes); }
        public void addBytesOut(long bytes) { bytesOut.addAndGet(bytes); }
        public long getBytesIn() { return bytesIn.get(); }
        public long getBytesOut() { return bytesOut.get(); }

        public String getPrivateEndpoint() { return privateIP + ":" + privatePort; }
        public String getPublicEndpoint() { return publicIP + ":" + publicPort; }

        @Override
        public String toString() {
            return String.format("%s %s:%d <-> %s:%d (in=%d, out=%d)",
                protocol, privateIP, privatePort, publicIP, publicPort,
                bytesIn.get(), bytesOut.get());
        }
    }

    public enum Protocol {
        TCP, UDP
    }

    public static class PortForwardRule {
        private final int externalPort;
        private final String internalIP;
        private final int internalPort;
        private final Protocol protocol;
        private final String description;

        public PortForwardRule(int externalPort, String internalIP, int internalPort,
                               Protocol protocol, String description) {
            this.externalPort = externalPort;
            this.internalIP = internalIP;
            this.internalPort = internalPort;
            this.protocol = protocol;
            this.description = description;
        }

        public int getExternalPort() { return externalPort; }
        public String getInternalIP() { return internalIP; }
        public int getInternalPort() { return internalPort; }
        public Protocol getProtocol() { return protocol; }
        public String getDescription() { return description; }

        @Override
        public String toString() {
            return String.format(":%d -> %s:%d (%s) \"%s\"",
                externalPort, internalIP, internalPort, protocol, description);
        }
    }

    private final String publicIP;
    private final String privateSubnet; // ex: 10.0.0.0/16
    private final Map<String, NatEntry> natTable; // privateEndpoint -> entry
    private final Map<Integer, NatEntry> reverseNatTable; // publicPort -> entry
    private final Map<Integer, PortForwardRule> portForwardRules;
    private final AtomicLong totalConnections;
    private final AtomicLong totalBytesIn;
    private final AtomicLong totalBytesOut;
    private int nextPublicPort;
    private final int portRangeStart;
    private final int portRangeEnd;
    private final int connectionTimeoutSeconds;
    private ScheduledExecutorService cleaner;
    private Instant startTime;
    private boolean running;

    public NatGateway(String publicIP, String privateSubnet) {
        this.publicIP = publicIP;
        this.privateSubnet = privateSubnet;
        this.natTable = new ConcurrentHashMap<>();
        this.reverseNatTable = new ConcurrentHashMap<>();
        this.portForwardRules = new ConcurrentHashMap<>();
        this.totalConnections = new AtomicLong(0);
        this.totalBytesIn = new AtomicLong(0);
        this.totalBytesOut = new AtomicLong(0);
        this.portRangeStart = 32768;
        this.portRangeEnd = 65535;
        this.nextPublicPort = portRangeStart;
        this.connectionTimeoutSeconds = 300; // 5 minutos
    }

    public void start() {
        running = true;
        startTime = Instant.now();

        // Limpar entradas antigas periodicamente
        cleaner = Executors.newSingleThreadScheduledExecutor();
        cleaner.scheduleAtFixedRate(this::cleanupStaleEntries, 60, 60, TimeUnit.SECONDS);
    }

    public void stop() {
        running = false;
        if (cleaner != null) {
            cleaner.shutdownNow();
        }
    }

    public boolean isRunning() { return running; }

    /**
     * Traduz um pacote de saída (privado -> público).
     * Retorna o endpoint público a usar.
     */
    public synchronized NatEntry translateOutbound(String privateIP, int privatePort, Protocol protocol) {
        String key = privateIP + ":" + privatePort + ":" + protocol;

        NatEntry entry = natTable.get(key);
        if (entry != null) {
            entry.touch();
            return entry;
        }

        // Criar nova entrada
        int publicPort = allocatePublicPort();
        entry = new NatEntry(privateIP, privatePort, publicIP, publicPort, protocol);

        natTable.put(key, entry);
        reverseNatTable.put(publicPort, entry);
        totalConnections.incrementAndGet();

        return entry;
    }

    /**
     * Traduz um pacote de entrada (público -> privado).
     * Retorna o endpoint privado de destino.
     */
    public NatEntry translateInbound(int publicPort) {
        // Verificar port forwarding primeiro
        PortForwardRule rule = portForwardRules.get(publicPort);
        if (rule != null) {
            // Criar entrada temporária para o forward
            return new NatEntry(rule.getInternalIP(), rule.getInternalPort(),
                               publicIP, publicPort, rule.getProtocol());
        }

        // Buscar na tabela NAT
        NatEntry entry = reverseNatTable.get(publicPort);
        if (entry != null) {
            entry.touch();
        }
        return entry;
    }

    private synchronized int allocatePublicPort() {
        int startPort = nextPublicPort;
        while (reverseNatTable.containsKey(nextPublicPort) ||
               portForwardRules.containsKey(nextPublicPort)) {
            nextPublicPort++;
            if (nextPublicPort > portRangeEnd) {
                nextPublicPort = portRangeStart;
            }
            if (nextPublicPort == startPort) {
                throw new RuntimeException("No available NAT ports");
            }
        }
        return nextPublicPort++;
    }

    public void addPortForward(int externalPort, String internalIP, int internalPort,
                               Protocol protocol, String description) {
        if (portForwardRules.containsKey(externalPort)) {
            throw new IllegalArgumentException("Porta já em uso: " + externalPort);
        }
        portForwardRules.put(externalPort,
            new PortForwardRule(externalPort, internalIP, internalPort, protocol, description));
    }

    public void addPortForward(int externalPort, String internalIP, int internalPort) {
        addPortForward(externalPort, internalIP, internalPort, Protocol.TCP, "");
    }

    public void removePortForward(int externalPort) {
        portForwardRules.remove(externalPort);
    }

    public Collection<PortForwardRule> getPortForwardRules() {
        return Collections.unmodifiableCollection(portForwardRules.values());
    }

    public void recordTraffic(NatEntry entry, long bytesIn, long bytesOut) {
        entry.addBytesIn(bytesIn);
        entry.addBytesOut(bytesOut);
        totalBytesIn.addAndGet(bytesIn);
        totalBytesOut.addAndGet(bytesOut);
    }

    private void cleanupStaleEntries() {
        Instant threshold = Instant.now().minusSeconds(connectionTimeoutSeconds);
        List<String> toRemove = new ArrayList<>();

        for (Map.Entry<String, NatEntry> e : natTable.entrySet()) {
            if (e.getValue().getLastUsed().isBefore(threshold)) {
                toRemove.add(e.getKey());
            }
        }

        for (String key : toRemove) {
            NatEntry entry = natTable.remove(key);
            if (entry != null) {
                reverseNatTable.remove(entry.getPublicPort());
            }
        }
    }

    public Collection<NatEntry> getNatTable() {
        return Collections.unmodifiableCollection(natTable.values());
    }

    public String getPublicIP() { return publicIP; }
    public String getPrivateSubnet() { return privateSubnet; }
    public long getTotalConnections() { return totalConnections.get(); }
    public long getTotalBytesIn() { return totalBytesIn.get(); }
    public long getTotalBytesOut() { return totalBytesOut.get(); }
    public int getActiveConnections() { return natTable.size(); }
    public Instant getStartTime() { return startTime; }

    public String getStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== NAT Gateway ===\n");
        sb.append("Public IP: ").append(publicIP).append("\n");
        sb.append("Private Subnet: ").append(privateSubnet).append("\n");
        sb.append("Active Connections: ").append(natTable.size()).append("\n");
        sb.append("Total Connections: ").append(totalConnections.get()).append("\n");
        sb.append("Bytes In: ").append(formatBytes(totalBytesIn.get())).append("\n");
        sb.append("Bytes Out: ").append(formatBytes(totalBytesOut.get())).append("\n");

        sb.append("\nPort Forward Rules:\n");
        if (portForwardRules.isEmpty()) {
            sb.append("  (none)\n");
        } else {
            for (PortForwardRule rule : portForwardRules.values()) {
                sb.append("  ").append(rule).append("\n");
            }
        }

        sb.append("\nNAT Table (last 10):\n");
        int count = 0;
        for (NatEntry entry : natTable.values()) {
            sb.append("  ").append(entry).append("\n");
            if (++count >= 10) break;
        }

        return sb.toString();
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
