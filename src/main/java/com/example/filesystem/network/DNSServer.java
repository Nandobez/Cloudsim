package com.example.filesystem.network;

import java.util.*;
import java.time.Instant;

/**
 * Servidor DNS interno para resolução de nomes.
 */
public class DNSServer {

    public enum RecordType {
        A,      // IPv4 address
        CNAME,  // Canonical name (alias)
        TXT,    // Text record
        MX,     // Mail exchange
        NS      // Name server
    }

    public static class DNSRecord {
        private final String name;
        private final RecordType type;
        private final String value;
        private final int ttl; // Time to live em segundos
        private final Instant createdAt;

        public DNSRecord(String name, RecordType type, String value, int ttl) {
            this.name = name.toLowerCase();
            this.type = type;
            this.value = value;
            this.ttl = ttl;
            this.createdAt = Instant.now();
        }

        public String getName() {
            return name;
        }

        public RecordType getType() {
            return type;
        }

        public String getValue() {
            return value;
        }

        public int getTtl() {
            return ttl;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public boolean isExpired() {
            return Instant.now().isAfter(createdAt.plusSeconds(ttl));
        }

        @Override
        public String toString() {
            return String.format("%-30s %-6s %-8d %s", name, type, ttl, value);
        }
    }

    public static class DNSCache {
        private final Map<String, CachedRecord> cache;
        private final int maxSize;

        public DNSCache(int maxSize) {
            this.maxSize = maxSize;
            this.cache = new LinkedHashMap<>(maxSize, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedRecord> eldest) {
                    return size() > DNSCache.this.maxSize;
                }
            };
        }

        public synchronized void put(String key, String value, int ttl) {
            cache.put(key.toLowerCase(), new CachedRecord(value, ttl));
        }

        public synchronized String get(String key) {
            CachedRecord record = cache.get(key.toLowerCase());
            if (record == null || record.isExpired()) {
                cache.remove(key.toLowerCase());
                return null;
            }
            return record.value;
        }

        public synchronized void clear() {
            cache.clear();
        }

        public synchronized int size() {
            return cache.size();
        }

        private static class CachedRecord {
            String value;
            Instant expiry;

            CachedRecord(String value, int ttlSeconds) {
                this.value = value;
                this.expiry = Instant.now().plusSeconds(ttlSeconds);
            }

            boolean isExpired() {
                return Instant.now().isAfter(expiry);
            }
        }
    }

    private final Map<String, List<DNSRecord>> records;
    private final DNSCache cache;
    private final String domain;
    private final int defaultTTL;
    private int queryCount;
    private int cacheHits;

    public DNSServer(String domain) {
        this.domain = domain;
        this.records = new LinkedHashMap<>();
        this.cache = new DNSCache(1000);
        this.defaultTTL = 300; // 5 minutos
        this.queryCount = 0;
        this.cacheHits = 0;

        // Registros padrão
        addRecord("localhost", RecordType.A, "127.0.0.1", 86400);
        addRecord("dns." + domain, RecordType.A, "10.0.0.2", defaultTTL);
    }

    public void addRecord(String name, RecordType type, String value, int ttl) {
        String fullName = normalizeName(name);
        records.computeIfAbsent(fullName, k -> new ArrayList<>())
               .add(new DNSRecord(fullName, type, value, ttl));
    }

    public void addRecord(String name, RecordType type, String value) {
        addRecord(name, type, value, defaultTTL);
    }

    public void addARecord(String name, String ip) {
        addRecord(name, RecordType.A, ip, defaultTTL);
    }

    public void addCNAME(String alias, String canonical) {
        addRecord(alias, RecordType.CNAME, normalizeName(canonical), defaultTTL);
    }

    public void addTXTRecord(String name, String text) {
        addRecord(name, RecordType.TXT, text, defaultTTL);
    }

    public boolean removeRecord(String name, RecordType type) {
        String fullName = normalizeName(name);
        List<DNSRecord> recordList = records.get(fullName);
        if (recordList != null) {
            return recordList.removeIf(r -> r.getType() == type);
        }
        return false;
    }

    public boolean removeAllRecords(String name) {
        return records.remove(normalizeName(name)) != null;
    }

    public String resolve(String name) {
        queryCount++;
        String fullName = normalizeName(name);

        // Verificar cache primeiro
        String cached = cache.get(fullName);
        if (cached != null) {
            cacheHits++;
            return cached;
        }

        // Buscar registro A
        String result = resolveType(fullName, RecordType.A);

        // Se não encontrou, tentar CNAME
        if (result == null) {
            String cname = resolveType(fullName, RecordType.CNAME);
            if (cname != null) {
                result = resolve(cname); // Recursivo
            }
        }

        // Adicionar ao cache
        if (result != null) {
            cache.put(fullName, result, defaultTTL);
        }

        return result;
    }

    public String resolveType(String name, RecordType type) {
        String fullName = normalizeName(name);
        List<DNSRecord> recordList = records.get(fullName);
        if (recordList != null) {
            for (DNSRecord record : recordList) {
                if (record.getType() == type && !record.isExpired()) {
                    return record.getValue();
                }
            }
        }
        return null;
    }

    public List<DNSRecord> getRecords(String name) {
        String fullName = normalizeName(name);
        List<DNSRecord> result = records.get(fullName);
        return result != null ? new ArrayList<>(result) : Collections.emptyList();
    }

    public List<DNSRecord> getAllRecords() {
        List<DNSRecord> all = new ArrayList<>();
        for (List<DNSRecord> recordList : records.values()) {
            all.addAll(recordList);
        }
        return all;
    }

    public String reverseLookup(String ip) {
        for (List<DNSRecord> recordList : records.values()) {
            for (DNSRecord record : recordList) {
                if (record.getType() == RecordType.A && record.getValue().equals(ip)) {
                    return record.getName();
                }
            }
        }
        return null;
    }

    private String normalizeName(String name) {
        name = name.toLowerCase().trim();
        // Se não tiver domínio, adicionar o domínio padrão
        if (!name.contains(".") || name.equals("localhost")) {
            return name;
        }
        if (!name.endsWith("." + domain) && !name.equals(domain)) {
            // Já é um FQDN ou nome curto
        }
        return name;
    }

    public String getDomain() {
        return domain;
    }

    public int getQueryCount() {
        return queryCount;
    }

    public int getCacheHits() {
        return cacheHits;
    }

    public double getCacheHitRate() {
        return queryCount > 0 ? (double) cacheHits / queryCount * 100 : 0;
    }

    public void clearCache() {
        cache.clear();
    }

    public int getCacheSize() {
        return cache.size();
    }

    public void resetStats() {
        queryCount = 0;
        cacheHits = 0;
    }

    @Override
    public String toString() {
        return String.format("DNSServer[domain=%s, records=%d, queries=%d, cacheHitRate=%.1f%%]",
            domain, records.size(), queryCount, getCacheHitRate());
    }
}
