package com.example.filesystem.network;

import com.example.filesystem.FileSystemSimulator;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cloud Storage com Presigned URLs (similar ao S3).
 */
public class CloudStorage {

    public static class Bucket {
        private final String name;
        private final String region;
        private final Instant createdAt;
        private final Map<String, ObjectMetadata> objects;
        private final AccessControl acl;
        private boolean versioningEnabled;
        private boolean publicAccess;

        public Bucket(String name, String region) {
            this.name = name;
            this.region = region;
            this.createdAt = Instant.now();
            this.objects = new ConcurrentHashMap<>();
            this.acl = new AccessControl();
            this.versioningEnabled = false;
            this.publicAccess = false;
        }

        public String getName() { return name; }
        public String getRegion() { return region; }
        public Instant getCreatedAt() { return createdAt; }
        public boolean isVersioningEnabled() { return versioningEnabled; }
        public void setVersioningEnabled(boolean enabled) { this.versioningEnabled = enabled; }
        public boolean isPublicAccess() { return publicAccess; }
        public void setPublicAccess(boolean publicAccess) { this.publicAccess = publicAccess; }
        public AccessControl getAcl() { return acl; }

        public void putObject(String key, ObjectMetadata metadata) {
            objects.put(key, metadata);
        }

        public ObjectMetadata getObject(String key) {
            return objects.get(key);
        }

        public void deleteObject(String key) {
            objects.remove(key);
        }

        public Collection<ObjectMetadata> listObjects() {
            return Collections.unmodifiableCollection(objects.values());
        }

        public Collection<ObjectMetadata> listObjects(String prefix) {
            return objects.values().stream()
                .filter(o -> o.getKey().startsWith(prefix))
                .toList();
        }

        public int getObjectCount() {
            return objects.size();
        }

        public long getTotalSize() {
            return objects.values().stream().mapToLong(ObjectMetadata::getSize).sum();
        }

        @Override
        public String toString() {
            return String.format("Bucket[%s, region=%s, objects=%d, size=%s]",
                name, region, objects.size(), formatBytes(getTotalSize()));
        }

        private String formatBytes(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
            if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
            return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }

    public static class ObjectMetadata {
        private final String key;
        private final String bucket;
        private final long size;
        private final String contentType;
        private final String etag;
        private final Instant lastModified;
        private final Map<String, String> userMetadata;
        private final String storageClass;

        public ObjectMetadata(String key, String bucket, long size, String contentType) {
            this.key = key;
            this.bucket = bucket;
            this.size = size;
            this.contentType = contentType;
            this.etag = generateETag();
            this.lastModified = Instant.now();
            this.userMetadata = new HashMap<>();
            this.storageClass = "STANDARD";
        }

        private String generateETag() {
            return "\"" + UUID.randomUUID().toString().replace("-", "").substring(0, 32) + "\"";
        }

        public String getKey() { return key; }
        public String getBucket() { return bucket; }
        public long getSize() { return size; }
        public String getContentType() { return contentType; }
        public String getEtag() { return etag; }
        public Instant getLastModified() { return lastModified; }
        public String getStorageClass() { return storageClass; }

        public void setUserMetadata(String key, String value) { userMetadata.put(key, value); }
        public String getUserMetadata(String key) { return userMetadata.get(key); }
        public Map<String, String> getAllUserMetadata() { return Collections.unmodifiableMap(userMetadata); }

        @Override
        public String toString() {
            return String.format("Object[%s, size=%d, type=%s]", key, size, contentType);
        }
    }

    public static class AccessControl {
        private final Set<String> readAccess;
        private final Set<String> writeAccess;
        private final Set<String> fullAccess;

        public AccessControl() {
            this.readAccess = ConcurrentHashMap.newKeySet();
            this.writeAccess = ConcurrentHashMap.newKeySet();
            this.fullAccess = ConcurrentHashMap.newKeySet();
        }

        public void grantRead(String userId) { readAccess.add(userId); }
        public void grantWrite(String userId) { writeAccess.add(userId); }
        public void grantFull(String userId) { fullAccess.add(userId); }

        public void revokeRead(String userId) { readAccess.remove(userId); }
        public void revokeWrite(String userId) { writeAccess.remove(userId); }
        public void revokeFull(String userId) { fullAccess.remove(userId); }

        public boolean canRead(String userId) {
            return fullAccess.contains(userId) || readAccess.contains(userId);
        }

        public boolean canWrite(String userId) {
            return fullAccess.contains(userId) || writeAccess.contains(userId);
        }
    }

    public static class PresignedUrl {
        private final String url;
        private final String bucket;
        private final String key;
        private final String method; // GET, PUT
        private final Instant createdAt;
        private final Instant expiresAt;
        private final String signature;
        private boolean used;

        public PresignedUrl(String url, String bucket, String key, String method,
                           Duration validFor, String signature) {
            this.url = url;
            this.bucket = bucket;
            this.key = key;
            this.method = method;
            this.createdAt = Instant.now();
            this.expiresAt = createdAt.plus(validFor);
            this.signature = signature;
            this.used = false;
        }

        public String getUrl() { return url; }
        public String getBucket() { return bucket; }
        public String getKey() { return key; }
        public String getMethod() { return method; }
        public Instant getCreatedAt() { return createdAt; }
        public Instant getExpiresAt() { return expiresAt; }
        public String getSignature() { return signature; }
        public boolean isUsed() { return used; }
        public void markUsed() { this.used = true; }

        public boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }

        public boolean isValid() {
            return !isExpired() && !used;
        }

        @Override
        public String toString() {
            return url;
        }
    }

    private final Map<String, Bucket> buckets;
    private final Map<String, PresignedUrl> presignedUrls;
    private final FileSystemSimulator fs;
    private final String secretKey;
    private final String baseUrl;
    private final String region;

    public CloudStorage(FileSystemSimulator fs, String baseUrl, String region) {
        this.buckets = new ConcurrentHashMap<>();
        this.presignedUrls = new ConcurrentHashMap<>();
        this.fs = fs;
        this.secretKey = generateSecretKey();
        this.baseUrl = baseUrl;
        this.region = region;
    }

    private String generateSecretKey() {
        SecureRandom random = new SecureRandom();
        byte[] key = new byte[32];
        random.nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    public Bucket createBucket(String name) {
        if (buckets.containsKey(name)) {
            throw new IllegalArgumentException("Bucket já existe: " + name);
        }
        if (!isValidBucketName(name)) {
            throw new IllegalArgumentException("Nome de bucket inválido: " + name);
        }
        Bucket bucket = new Bucket(name, region);
        buckets.put(name, bucket);
        return bucket;
    }

    private boolean isValidBucketName(String name) {
        return name != null && name.length() >= 3 && name.length() <= 63 &&
               name.matches("^[a-z0-9][a-z0-9.-]*[a-z0-9]$");
    }

    public void deleteBucket(String name) {
        Bucket bucket = buckets.get(name);
        if (bucket == null) {
            throw new IllegalArgumentException("Bucket não encontrado: " + name);
        }
        if (bucket.getObjectCount() > 0) {
            throw new IllegalArgumentException("Bucket não está vazio");
        }
        buckets.remove(name);
    }

    public Bucket getBucket(String name) {
        return buckets.get(name);
    }

    public Collection<Bucket> listBuckets() {
        return Collections.unmodifiableCollection(buckets.values());
    }

    public void putObject(String bucketName, String key, String content, String contentType) {
        Bucket bucket = getBucket(bucketName);
        if (bucket == null) {
            throw new IllegalArgumentException("Bucket não encontrado: " + bucketName);
        }

        // Salvar no filesystem virtual
        String path = "/s3/" + bucketName + "/" + key;
        ensureDirectoryExists(path);
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        fs.createOrOverwriteFile(path, contentBytes);

        ObjectMetadata metadata = new ObjectMetadata(key, bucketName, contentBytes.length, contentType);
        bucket.putObject(key, metadata);
    }

    public String getObject(String bucketName, String key) {
        Bucket bucket = getBucket(bucketName);
        if (bucket == null) {
            throw new IllegalArgumentException("Bucket não encontrado: " + bucketName);
        }

        ObjectMetadata metadata = bucket.getObject(key);
        if (metadata == null) {
            throw new IllegalArgumentException("Objeto não encontrado: " + key);
        }

        String path = "/s3/" + bucketName + "/" + key;
        var optContent = fs.readFile(path);
        if (optContent.isEmpty()) {
            throw new IllegalArgumentException("Não foi possível ler objeto: " + key);
        }
        return new String(optContent.get(), StandardCharsets.UTF_8);
    }

    public void deleteObject(String bucketName, String key) {
        Bucket bucket = getBucket(bucketName);
        if (bucket == null) {
            throw new IllegalArgumentException("Bucket não encontrado: " + bucketName);
        }

        bucket.deleteObject(key);

        String path = "/s3/" + bucketName + "/" + key;
        if (fs.exists(path)) {
            fs.deleteFile(path);
        }
    }

    private void ensureDirectoryExists(String path) {
        String[] parts = path.split("/");
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            if (parts[i].isEmpty()) continue;
            current.append("/").append(parts[i]);
            if (!fs.exists(current.toString())) {
                fs.createDirectory(current.toString());
            }
        }
    }

    // ==================== Presigned URLs ====================

    public PresignedUrl generatePresignedGetUrl(String bucketName, String key, Duration validFor) {
        return generatePresignedUrl(bucketName, key, "GET", validFor);
    }

    public PresignedUrl generatePresignedPutUrl(String bucketName, String key, Duration validFor) {
        return generatePresignedUrl(bucketName, key, "PUT", validFor);
    }

    private PresignedUrl generatePresignedUrl(String bucketName, String key, String method, Duration validFor) {
        Bucket bucket = getBucket(bucketName);
        if (bucket == null) {
            throw new IllegalArgumentException("Bucket não encontrado: " + bucketName);
        }

        long expires = Instant.now().plus(validFor).getEpochSecond();
        String stringToSign = method + "\n" + bucketName + "\n" + key + "\n" + expires;
        String signature = sign(stringToSign);

        String url = String.format("%s/%s/%s?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Expires=%d&X-Amz-Signature=%s",
            baseUrl, bucketName, key, validFor.getSeconds(), signature);

        PresignedUrl presigned = new PresignedUrl(url, bucketName, key, method, validFor, signature);
        presignedUrls.put(signature, presigned);

        return presigned;
    }

    public boolean validatePresignedUrl(String signature, String method) {
        PresignedUrl presigned = presignedUrls.get(signature);
        if (presigned == null) {
            return false;
        }
        if (!presigned.isValid()) {
            presignedUrls.remove(signature);
            return false;
        }
        if (!presigned.getMethod().equals(method)) {
            return false;
        }
        return true;
    }

    public PresignedUrl getPresignedUrl(String signature) {
        return presignedUrls.get(signature);
    }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("Erro ao assinar: " + e.getMessage());
        }
    }

    // Limpar URLs expiradas
    public void cleanupExpiredUrls() {
        presignedUrls.entrySet().removeIf(e -> !e.getValue().isValid());
    }

    public String getStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Cloud Storage ===\n");
        sb.append("Base URL: ").append(baseUrl).append("\n");
        sb.append("Region: ").append(region).append("\n");
        sb.append("Total Buckets: ").append(buckets.size()).append("\n");
        sb.append("Active Presigned URLs: ").append(presignedUrls.size()).append("\n");

        sb.append("\nBuckets:\n");
        for (Bucket bucket : buckets.values()) {
            sb.append("  ").append(bucket).append("\n");
        }

        return sb.toString();
    }
}
