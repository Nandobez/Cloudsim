package com.example.filesystem.network;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Service Discovery - registro e descoberta de serviços (similar ao Consul/etcd).
 */
public class ServiceDiscovery {

    public static class ServiceInstance {
        private final String id;
        private final String serviceName;
        private final String host;
        private final int port;
        private final Map<String, String> metadata;
        private final Set<String> tags;
        private volatile ServiceStatus status;
        private volatile Instant lastHeartbeat;
        private final Instant registeredAt;

        public ServiceInstance(String serviceName, String host, int port) {
            this.id = UUID.randomUUID().toString().substring(0, 8);
            this.serviceName = serviceName;
            this.host = host;
            this.port = port;
            this.metadata = new ConcurrentHashMap<>();
            this.tags = ConcurrentHashMap.newKeySet();
            this.status = ServiceStatus.PASSING;
            this.lastHeartbeat = Instant.now();
            this.registeredAt = Instant.now();
        }

        public String getId() { return id; }
        public String getServiceName() { return serviceName; }
        public String getHost() { return host; }
        public int getPort() { return port; }
        public String getAddress() { return host + ":" + port; }
        public ServiceStatus getStatus() { return status; }
        public void setStatus(ServiceStatus status) { this.status = status; }
        public Instant getLastHeartbeat() { return lastHeartbeat; }
        public void heartbeat() { this.lastHeartbeat = Instant.now(); this.status = ServiceStatus.PASSING; }
        public Instant getRegisteredAt() { return registeredAt; }

        public void setMetadata(String key, String value) { metadata.put(key, value); }
        public String getMetadata(String key) { return metadata.get(key); }
        public Map<String, String> getAllMetadata() { return Collections.unmodifiableMap(metadata); }

        public void addTag(String tag) { tags.add(tag); }
        public void removeTag(String tag) { tags.remove(tag); }
        public Set<String> getTags() { return Collections.unmodifiableSet(tags); }
        public boolean hasTag(String tag) { return tags.contains(tag); }

        @Override
        public String toString() {
            return String.format("Service[%s/%s @ %s:%d, status=%s, tags=%s]",
                serviceName, id, host, port, status, tags);
        }
    }

    public enum ServiceStatus {
        PASSING,    // Saudável
        WARNING,    // Com problemas mas funcionando
        CRITICAL,   // Falha
        MAINTENANCE // Em manutenção
    }

    private final Map<String, List<ServiceInstance>> services;
    private final Map<String, ServiceInstance> instancesById;
    private final ScheduledExecutorService healthChecker;
    private final int heartbeatTimeoutSeconds;
    private final List<ServiceEventListener> listeners;

    public ServiceDiscovery() {
        this.services = new ConcurrentHashMap<>();
        this.instancesById = new ConcurrentHashMap<>();
        this.heartbeatTimeoutSeconds = 30;
        this.listeners = new CopyOnWriteArrayList<>();

        // Health checker que marca serviços sem heartbeat como CRITICAL
        this.healthChecker = Executors.newSingleThreadScheduledExecutor();
        this.healthChecker.scheduleAtFixedRate(this::checkHeartbeats, 10, 10, TimeUnit.SECONDS);
    }

    public ServiceInstance register(String serviceName, String host, int port) {
        ServiceInstance instance = new ServiceInstance(serviceName, host, port);

        services.computeIfAbsent(serviceName, k -> new CopyOnWriteArrayList<>()).add(instance);
        instancesById.put(instance.getId(), instance);

        notifyListeners(ServiceEvent.REGISTERED, instance);
        return instance;
    }

    public ServiceInstance register(String serviceName, String host, int port, String... tags) {
        ServiceInstance instance = register(serviceName, host, port);
        for (String tag : tags) {
            instance.addTag(tag);
        }
        return instance;
    }

    public void deregister(String instanceId) {
        ServiceInstance instance = instancesById.remove(instanceId);
        if (instance != null) {
            List<ServiceInstance> list = services.get(instance.getServiceName());
            if (list != null) {
                list.remove(instance);
            }
            notifyListeners(ServiceEvent.DEREGISTERED, instance);
        }
    }

    public void deregisterService(String serviceName) {
        List<ServiceInstance> instances = services.remove(serviceName);
        if (instances != null) {
            for (ServiceInstance instance : instances) {
                instancesById.remove(instance.getId());
                notifyListeners(ServiceEvent.DEREGISTERED, instance);
            }
        }
    }

    public void heartbeat(String instanceId) {
        ServiceInstance instance = instancesById.get(instanceId);
        if (instance != null) {
            ServiceStatus oldStatus = instance.getStatus();
            instance.heartbeat();
            if (oldStatus == ServiceStatus.CRITICAL) {
                notifyListeners(ServiceEvent.HEALTHY, instance);
            }
        }
    }

    public List<ServiceInstance> discover(String serviceName) {
        List<ServiceInstance> instances = services.get(serviceName);
        if (instances == null) {
            return Collections.emptyList();
        }
        return instances.stream()
            .filter(i -> i.getStatus() == ServiceStatus.PASSING)
            .toList();
    }

    public List<ServiceInstance> discoverAll(String serviceName) {
        List<ServiceInstance> instances = services.get(serviceName);
        return instances != null ? new ArrayList<>(instances) : Collections.emptyList();
    }

    public List<ServiceInstance> discoverByTag(String tag) {
        List<ServiceInstance> result = new ArrayList<>();
        for (List<ServiceInstance> instances : services.values()) {
            for (ServiceInstance instance : instances) {
                if (instance.hasTag(tag) && instance.getStatus() == ServiceStatus.PASSING) {
                    result.add(instance);
                }
            }
        }
        return result;
    }

    public ServiceInstance discoverOne(String serviceName) {
        List<ServiceInstance> instances = discover(serviceName);
        if (instances.isEmpty()) {
            return null;
        }
        // Round-robin simples
        return instances.get(ThreadLocalRandom.current().nextInt(instances.size()));
    }

    public ServiceInstance getInstance(String instanceId) {
        return instancesById.get(instanceId);
    }

    public Set<String> getServiceNames() {
        return Collections.unmodifiableSet(services.keySet());
    }

    public Map<String, Integer> getServiceCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map.Entry<String, List<ServiceInstance>> entry : services.entrySet()) {
            counts.put(entry.getKey(), entry.getValue().size());
        }
        return counts;
    }

    private void checkHeartbeats() {
        Instant threshold = Instant.now().minusSeconds(heartbeatTimeoutSeconds);
        for (ServiceInstance instance : instancesById.values()) {
            if (instance.getLastHeartbeat().isBefore(threshold) &&
                instance.getStatus() != ServiceStatus.CRITICAL) {
                instance.setStatus(ServiceStatus.CRITICAL);
                notifyListeners(ServiceEvent.UNHEALTHY, instance);
            }
        }
    }

    public void shutdown() {
        healthChecker.shutdownNow();
    }

    // ==================== Event System ====================

    public enum ServiceEvent {
        REGISTERED,
        DEREGISTERED,
        HEALTHY,
        UNHEALTHY
    }

    public interface ServiceEventListener {
        void onServiceEvent(ServiceEvent event, ServiceInstance instance);
    }

    public void addListener(ServiceEventListener listener) {
        listeners.add(listener);
    }

    public void removeListener(ServiceEventListener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners(ServiceEvent event, ServiceInstance instance) {
        for (ServiceEventListener listener : listeners) {
            try {
                listener.onServiceEvent(event, instance);
            } catch (Exception ignored) {}
        }
    }

    // ==================== Status ====================

    public String getStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Service Discovery ===\n");
        sb.append("Total Services: ").append(services.size()).append("\n");
        sb.append("Total Instances: ").append(instancesById.size()).append("\n\n");

        for (Map.Entry<String, List<ServiceInstance>> entry : services.entrySet()) {
            sb.append("Service: ").append(entry.getKey()).append("\n");
            for (ServiceInstance instance : entry.getValue()) {
                sb.append("  - ").append(instance).append("\n");
            }
        }
        return sb.toString();
    }
}
