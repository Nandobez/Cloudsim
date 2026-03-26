package com.example.filesystem.network;

import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CDN simplificado (Content Delivery Network).
 */
public class CDN {

    public static class EdgeNode {
        private final String id;
        private final String region;
        private final String location;
        private final Map<String, CachedContent> cache;
        private final int maxCacheSize;
        private final AtomicLong hits;
        private final AtomicLong misses;
        private final AtomicLong bytesServed;
        private boolean healthy;

        public EdgeNode(String region, String location, int maxCacheSize) {
            this.id = UUID.randomUUID().toString().substring(0, 8);
            this.region = region;
            this.location = location;
            this.cache = new ConcurrentHashMap<>();
            this.maxCacheSize = maxCacheSize;
            this.hits = new AtomicLong(0);
            this.misses = new AtomicLong(0);
            this.bytesServed = new AtomicLong(0);
            this.healthy = true;
        }

        public CachedContent get(String key) {
            CachedContent content = cache.get(key);
            if (content != null && !content.isExpired()) {
                hits.incrementAndGet();
                bytesServed.addAndGet(content.getSize());
                content.hit();
                return content;
            }
            if (content != null) {
                cache.remove(key); // Expirado
            }
            misses.incrementAndGet();
            return null;
        }

        public void put(String key, CachedContent content) {
            evictIfNeeded();
            cache.put(key, content);
        }

        public void invalidate(String key) {
            cache.remove(key);
        }

        public void invalidateAll() {
            cache.clear();
        }

        private void evictIfNeeded() {
            while (cache.size() >= maxCacheSize) {
                // LRU: remover o menos recentemente usado
                String lruKey = null;
                Instant oldest = Instant.now();
                for (Map.Entry<String, CachedContent> e : cache.entrySet()) {
                    if (e.getValue().getLastAccess().isBefore(oldest)) {
                        oldest = e.getValue().getLastAccess();
                        lruKey = e.getKey();
                    }
                }
                if (lruKey != null) {
                    cache.remove(lruKey);
                }
            }
        }

        public String getId() { return id; }
        public String getRegion() { return region; }
        public String getLocation() { return location; }
        public boolean isHealthy() { return healthy; }
        public void setHealthy(boolean healthy) { this.healthy = healthy; }
        public long getHits() { return hits.get(); }
        public long getMisses() { return misses.get(); }
        public long getBytesServed() { return bytesServed.get(); }
        public int getCacheSize() { return cache.size(); }
        public double getHitRate() {
            long total = hits.get() + misses.get();
            return total > 0 ? (double) hits.get() / total * 100 : 0;
        }

        @Override
        public String toString() {
            return String.format("EdgeNode[%s, %s/%s, cache=%d/%d, hitRate=%.1f%%]",
                id, region, location, cache.size(), maxCacheSize, getHitRate());
        }
    }

    public static class CachedContent {
        private final String key;
        private final byte[] data;
        private final String contentType;
        private final String etag;
        private final Instant cachedAt;
        private final Duration ttl;
        private Instant lastAccess;
        private int hitCount;

        public CachedContent(String key, byte[] data, String contentType, Duration ttl) {
            this.key = key;
            this.data = data;
            this.contentType = contentType;
            this.etag = computeETag(data);
            this.cachedAt = Instant.now();
            this.ttl = ttl;
            this.lastAccess = Instant.now();
            this.hitCount = 0;
        }

        private static String computeETag(byte[] data) {
            try {
                MessageDigest md = MessageDigest.getInstance("MD5");
                byte[] hash = md.digest(data);
                StringBuilder sb = new StringBuilder();
                for (byte b : hash) {
                    sb.append(String.format("%02x", b));
                }
                return "\"" + sb.toString().substring(0, 16) + "\"";
            } catch (Exception e) {
                return "\"" + System.currentTimeMillis() + "\"";
            }
        }

        public void hit() {
            lastAccess = Instant.now();
            hitCount++;
        }

        public boolean isExpired() {
            return Instant.now().isAfter(cachedAt.plus(ttl));
        }

        public String getKey() { return key; }
        public byte[] getData() { return data; }
        public String getContentType() { return contentType; }
        public String getEtag() { return etag; }
        public Instant getCachedAt() { return cachedAt; }
        public Instant getLastAccess() { return lastAccess; }
        public int getHitCount() { return hitCount; }
        public int getSize() { return data.length; }
    }

    public static class Origin {
        private final String name;
        private final String host;
        private final int port;
        private final String basePath;

        public Origin(String name, String host, int port, String basePath) {
            this.name = name;
            this.host = host;
            this.port = port;
            this.basePath = basePath;
        }

        public String getName() { return name; }
        public String getHost() { return host; }
        public int getPort() { return port; }
        public String getBasePath() { return basePath; }

        public String getUrl(String path) {
            return "http://" + host + ":" + port + basePath + path;
        }

        @Override
        public String toString() {
            return String.format("Origin[%s -> %s:%d%s]", name, host, port, basePath);
        }
    }

    private final Map<String, EdgeNode> edgeNodes;
    private final Map<String, Origin> origins;
    private final Map<String, String> regionMapping; // client region -> edge node id
    private final Duration defaultTTL;
    private final AtomicLong totalRequests;
    private final AtomicLong originRequests;

    public CDN() {
        this.edgeNodes = new ConcurrentHashMap<>();
        this.origins = new ConcurrentHashMap<>();
        this.regionMapping = new ConcurrentHashMap<>();
        this.defaultTTL = Duration.ofHours(1);
        this.totalRequests = new AtomicLong(0);
        this.originRequests = new AtomicLong(0);
    }

    public EdgeNode addEdgeNode(String region, String location, int maxCacheSize) {
        EdgeNode node = new EdgeNode(region, location, maxCacheSize);
        edgeNodes.put(node.getId(), node);
        regionMapping.putIfAbsent(region, node.getId());
        return node;
    }

    public void removeEdgeNode(String nodeId) {
        EdgeNode node = edgeNodes.remove(nodeId);
        if (node != null) {
            regionMapping.values().removeIf(id -> id.equals(nodeId));
        }
    }

    public EdgeNode getEdgeNode(String nodeId) {
        return edgeNodes.get(nodeId);
    }

    public Collection<EdgeNode> getEdgeNodes() {
        return Collections.unmodifiableCollection(edgeNodes.values());
    }

    public void addOrigin(String name, String host, int port, String basePath) {
        origins.put(name, new Origin(name, host, port, basePath));
    }

    public void removeOrigin(String name) {
        origins.remove(name);
    }

    public Origin getOrigin(String name) {
        return origins.get(name);
    }

    public Collection<Origin> getOrigins() {
        return Collections.unmodifiableCollection(origins.values());
    }

    public EdgeNode selectEdgeNode(String clientRegion) {
        // Primeiro tenta o mapeamento de região
        String nodeId = regionMapping.get(clientRegion);
        if (nodeId != null) {
            EdgeNode node = edgeNodes.get(nodeId);
            if (node != null && node.isHealthy()) {
                return node;
            }
        }

        // Senão, pega qualquer nó saudável
        for (EdgeNode node : edgeNodes.values()) {
            if (node.isHealthy()) {
                return node;
            }
        }
        return null;
    }

    public CachedContent fetch(String key, String clientRegion) {
        totalRequests.incrementAndGet();

        EdgeNode edge = selectEdgeNode(clientRegion);
        if (edge == null) {
            return null;
        }

        CachedContent content = edge.get(key);
        if (content != null) {
            return content;
        }

        // Cache miss - buscar da origem
        originRequests.incrementAndGet();
        return null; // Na implementação real, buscaria da origem
    }

    public void cache(String key, byte[] data, String contentType, String clientRegion) {
        cache(key, data, contentType, clientRegion, defaultTTL);
    }

    public void cache(String key, byte[] data, String contentType, String clientRegion, Duration ttl) {
        EdgeNode edge = selectEdgeNode(clientRegion);
        if (edge != null) {
            edge.put(key, new CachedContent(key, data, contentType, ttl));
        }
    }

    public void invalidate(String key) {
        for (EdgeNode node : edgeNodes.values()) {
            node.invalidate(key);
        }
    }

    public void invalidateAll() {
        for (EdgeNode node : edgeNodes.values()) {
            node.invalidateAll();
        }
    }

    public void purge(String pattern) {
        for (EdgeNode node : edgeNodes.values()) {
            // Simples pattern matching
            node.cache.keySet().removeIf(k -> k.contains(pattern.replace("*", "")));
        }
    }

    public long getTotalRequests() { return totalRequests.get(); }
    public long getOriginRequests() { return originRequests.get(); }

    public double getOverallHitRate() {
        long total = totalRequests.get();
        long origin = originRequests.get();
        return total > 0 ? (double) (total - origin) / total * 100 : 0;
    }

    public String getStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== CDN Status ===\n");
        sb.append("Total Requests: ").append(totalRequests.get()).append("\n");
        sb.append("Origin Requests: ").append(originRequests.get()).append("\n");
        sb.append("Overall Hit Rate: ").append(String.format("%.1f%%", getOverallHitRate())).append("\n");

        sb.append("\nOrigins:\n");
        for (Origin origin : origins.values()) {
            sb.append("  ").append(origin).append("\n");
        }

        sb.append("\nEdge Nodes:\n");
        for (EdgeNode node : edgeNodes.values()) {
            sb.append("  ").append(node).append("\n");
        }

        sb.append("\nRegion Mapping:\n");
        for (Map.Entry<String, String> e : regionMapping.entrySet()) {
            sb.append("  ").append(e.getKey()).append(" -> ").append(e.getValue()).append("\n");
        }

        return sb.toString();
    }
}
