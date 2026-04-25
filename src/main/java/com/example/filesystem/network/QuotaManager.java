package com.example.filesystem.network;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gerenciador de cotas de disco por usuário/tenant.
 */
public class QuotaManager {

    public static class Quota {
        private final String userId;
        private long maxBytes;
        private long usedBytes;
        private long maxFiles;
        private long usedFiles;
        private boolean hardLimit; // Se true, bloqueia operações; se false, apenas avisa

        public Quota(String userId, long maxBytes, long maxFiles) {
            this.userId = userId;
            this.maxBytes = maxBytes;
            this.usedBytes = 0;
            this.maxFiles = maxFiles;
            this.usedFiles = 0;
            this.hardLimit = true;
        }

        public String getUserId() { return userId; }
        public long getMaxBytes() { return maxBytes; }
        public void setMaxBytes(long maxBytes) { this.maxBytes = maxBytes; }
        public long getUsedBytes() { return usedBytes; }
        public long getMaxFiles() { return maxFiles; }
        public void setMaxFiles(long maxFiles) { this.maxFiles = maxFiles; }
        public long getUsedFiles() { return usedFiles; }
        public boolean isHardLimit() { return hardLimit; }
        public void setHardLimit(boolean hardLimit) { this.hardLimit = hardLimit; }

        public long getAvailableBytes() {
            return Math.max(0, maxBytes - usedBytes);
        }

        public long getAvailableFiles() {
            return Math.max(0, maxFiles - usedFiles);
        }

        public double getUsagePercentage() {
            return maxBytes > 0 ? (double) usedBytes / maxBytes * 100 : 0;
        }

        public boolean canAllocate(long bytes) {
            return !hardLimit || (usedBytes + bytes <= maxBytes);
        }

        public boolean canCreateFile() {
            return !hardLimit || (usedFiles < maxFiles);
        }

        public synchronized void allocate(long bytes) {
            usedBytes += bytes;
            usedFiles++;
        }

        public synchronized void deallocate(long bytes) {
            usedBytes = Math.max(0, usedBytes - bytes);
            usedFiles = Math.max(0, usedFiles - 1);
        }

        public synchronized void updateUsage(long bytes) {
            usedBytes += bytes;
        }

        public synchronized void reset() {
            usedBytes = 0;
            usedFiles = 0;
        }

        public boolean isOverQuota() {
            return usedBytes > maxBytes || usedFiles > maxFiles;
        }

        public boolean isNearQuota(double threshold) {
            return getUsagePercentage() >= threshold;
        }

        @Override
        public String toString() {
            return String.format("Quota[%s: %s/%s (%.1f%%), files: %d/%d]",
                userId,
                formatBytes(usedBytes), formatBytes(maxBytes),
                getUsagePercentage(),
                usedFiles, maxFiles);
        }

        public String getDetailedInfo() {
            StringBuilder sb = new StringBuilder();
            sb.append("User: ").append(userId).append("\n");
            sb.append("Storage:\n");
            sb.append("  Used: ").append(formatBytes(usedBytes)).append("\n");
            sb.append("  Max: ").append(formatBytes(maxBytes)).append("\n");
            sb.append("  Available: ").append(formatBytes(getAvailableBytes())).append("\n");
            sb.append("  Usage: ").append(String.format("%.1f%%", getUsagePercentage())).append("\n");
            sb.append("Files:\n");
            sb.append("  Used: ").append(usedFiles).append("\n");
            sb.append("  Max: ").append(maxFiles).append("\n");
            sb.append("  Available: ").append(getAvailableFiles()).append("\n");
            sb.append("Hard Limit: ").append(hardLimit).append("\n");
            sb.append("Status: ").append(isOverQuota() ? "OVER QUOTA" :
                (isNearQuota(80) ? "WARNING" : "OK")).append("\n");
            return sb.toString();
        }

        private static String formatBytes(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
            if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
            return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }

    public static class QuotaExceededException extends RuntimeException {
        private final String userId;
        private final String resource;

        public QuotaExceededException(String userId, String resource) {
            super("Cota excedida para usuário " + userId + ": " + resource);
            this.userId = userId;
            this.resource = resource;
        }

        public String getUserId() { return userId; }
        public String getResource() { return resource; }
    }

    private final Map<String, Quota> quotas;
    private final Quota defaultQuota;
    private final List<QuotaEventListener> listeners;

    public QuotaManager(long defaultMaxBytes, long defaultMaxFiles) {
        this.quotas = new ConcurrentHashMap<>();
        this.defaultQuota = new Quota("default", defaultMaxBytes, defaultMaxFiles);
        this.listeners = new ArrayList<>();
    }

    public QuotaManager() {
        // Padrão: 100 MB e 1000 arquivos
        this(100 * 1024 * 1024, 1000);
    }

    public Quota createQuota(String userId, long maxBytes, long maxFiles) {
        Quota quota = new Quota(userId, maxBytes, maxFiles);
        quotas.put(userId, quota);
        return quota;
    }

    public Quota createQuota(String userId, String maxBytesStr, long maxFiles) {
        long maxBytes = parseSize(maxBytesStr);
        return createQuota(userId, maxBytes, maxFiles);
    }

    public void deleteQuota(String userId) {
        quotas.remove(userId);
    }

    public Quota getQuota(String userId) {
        return quotas.getOrDefault(userId, defaultQuota);
    }

    public Quota getOrCreateQuota(String userId) {
        return quotas.computeIfAbsent(userId,
            k -> new Quota(k, defaultQuota.getMaxBytes(), defaultQuota.getMaxFiles()));
    }

    public Collection<Quota> getAllQuotas() {
        return Collections.unmodifiableCollection(quotas.values());
    }

    public boolean hasQuota(String userId) {
        return quotas.containsKey(userId);
    }

    // ==================== Operations ====================

    public void checkAndAllocate(String userId, long bytes) throws QuotaExceededException {
        Quota quota = getQuota(userId);

        if (!quota.canAllocate(bytes)) {
            notifyListeners(userId, "storage", quota);
            throw new QuotaExceededException(userId, "storage (need " +
                Quota.formatBytes(bytes) + ", available " + Quota.formatBytes(quota.getAvailableBytes()) + ")");
        }

        if (!quota.canCreateFile()) {
            notifyListeners(userId, "files", quota);
            throw new QuotaExceededException(userId, "files (max " + quota.getMaxFiles() + ")");
        }

        quota.allocate(bytes);

        // Verificar se passou de 80%
        if (quota.isNearQuota(80)) {
            notifyListeners(userId, "warning", quota);
        }
    }

    public void deallocate(String userId, long bytes) {
        Quota quota = getQuota(userId);
        quota.deallocate(bytes);
    }

    public void updateUsage(String userId, long bytesDelta) {
        Quota quota = getQuota(userId);
        quota.updateUsage(bytesDelta);
    }

    public boolean canAllocate(String userId, long bytes) {
        Quota quota = getQuota(userId);
        return quota.canAllocate(bytes) && quota.canCreateFile();
    }

    // ==================== Parsing ====================

    public static long parseSize(String sizeStr) {
        sizeStr = sizeStr.trim().toUpperCase();

        long multiplier = 1;
        String numStr = sizeStr;

        if (sizeStr.endsWith("KB") || sizeStr.endsWith("K")) {
            multiplier = 1024;
            numStr = sizeStr.replaceAll("[KBkb\\s]", "");
        } else if (sizeStr.endsWith("MB") || sizeStr.endsWith("M")) {
            multiplier = 1024 * 1024;
            numStr = sizeStr.replaceAll("[MBmb\\s]", "");
        } else if (sizeStr.endsWith("GB") || sizeStr.endsWith("G")) {
            multiplier = 1024L * 1024 * 1024;
            numStr = sizeStr.replaceAll("[GBgb\\s]", "");
        } else if (sizeStr.endsWith("B")) {
            numStr = sizeStr.replaceAll("[Bb\\s]", "");
        }

        return (long) (Double.parseDouble(numStr) * multiplier);
    }

    // ==================== Events ====================

    public interface QuotaEventListener {
        void onQuotaEvent(String userId, String eventType, Quota quota);
    }

    public void addListener(QuotaEventListener listener) {
        listeners.add(listener);
    }

    public void removeListener(QuotaEventListener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners(String userId, String eventType, Quota quota) {
        for (QuotaEventListener listener : listeners) {
            try {
                listener.onQuotaEvent(userId, eventType, quota);
            } catch (Exception ignored) {}
        }
    }

    // ==================== Status ====================

    public String getStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Quota Manager ===\n");
        sb.append("Default: ").append(Quota.formatBytes(defaultQuota.getMaxBytes()))
          .append(", ").append(defaultQuota.getMaxFiles()).append(" files\n\n");

        if (quotas.isEmpty()) {
            sb.append("No custom quotas configured.\n");
        } else {
            for (Quota quota : quotas.values()) {
                String status = quota.isOverQuota() ? "[OVER]" :
                    (quota.isNearQuota(80) ? "[WARN]" : "[OK]");
                sb.append(String.format("%-8s %s\n", status, quota));
            }
        }
        return sb.toString();
    }
}
