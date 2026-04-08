package com.example.filesystem.network;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simulador de rede de containers (tipo Docker network).
 */
public class ContainerNetwork {

    public static class Container {
        private final String id;
        private final String name;
        private final String image;
        private final Map<String, IPAddress> networkIPs; // network -> IP
        private final Map<Integer, PortBinding> portBindings; // container port -> binding
        private final Map<String, String> env;
        private final List<String> volumes;
        private ContainerState state;
        private String hostname;

        public Container(String name, String image) {
            this.id = UUID.randomUUID().toString().substring(0, 12);
            this.name = name;
            this.image = image;
            this.networkIPs = new ConcurrentHashMap<>();
            this.portBindings = new ConcurrentHashMap<>();
            this.env = new ConcurrentHashMap<>();
            this.volumes = new ArrayList<>();
            this.state = ContainerState.CREATED;
            this.hostname = name;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public String getImage() { return image; }
        public ContainerState getState() { return state; }
        public void setState(ContainerState state) { this.state = state; }
        public String getHostname() { return hostname; }
        public void setHostname(String hostname) { this.hostname = hostname; }

        public void connectToNetwork(String networkName, IPAddress ip) {
            networkIPs.put(networkName, ip);
        }

        public void disconnectFromNetwork(String networkName) {
            networkIPs.remove(networkName);
        }

        public IPAddress getIP(String networkName) {
            return networkIPs.get(networkName);
        }

        public Map<String, IPAddress> getNetworks() {
            return Collections.unmodifiableMap(networkIPs);
        }

        public void addPortBinding(int containerPort, int hostPort, String hostIP) {
            portBindings.put(containerPort, new PortBinding(containerPort, hostPort, hostIP));
        }

        public void addPortBinding(int containerPort, int hostPort) {
            addPortBinding(containerPort, hostPort, "0.0.0.0");
        }

        public PortBinding getPortBinding(int containerPort) {
            return portBindings.get(containerPort);
        }

        public Collection<PortBinding> getPortBindings() {
            return Collections.unmodifiableCollection(portBindings.values());
        }

        public void setEnv(String key, String value) { env.put(key, value); }
        public String getEnv(String key) { return env.get(key); }
        public Map<String, String> getAllEnv() { return Collections.unmodifiableMap(env); }

        public void addVolume(String volume) { volumes.add(volume); }
        public List<String> getVolumes() { return Collections.unmodifiableList(volumes); }

        @Override
        public String toString() {
            return String.format("Container[%s (%s), image=%s, state=%s, networks=%s]",
                name, id, image, state, networkIPs.keySet());
        }

        public String getDetailedInfo() {
            StringBuilder sb = new StringBuilder();
            sb.append("Container ID: ").append(id).append("\n");
            sb.append("Name: ").append(name).append("\n");
            sb.append("Image: ").append(image).append("\n");
            sb.append("State: ").append(state).append("\n");
            sb.append("Hostname: ").append(hostname).append("\n");
            sb.append("Networks:\n");
            for (Map.Entry<String, IPAddress> e : networkIPs.entrySet()) {
                sb.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
            }
            sb.append("Port Bindings:\n");
            for (PortBinding pb : portBindings.values()) {
                sb.append("  ").append(pb).append("\n");
            }
            if (!env.isEmpty()) {
                sb.append("Environment:\n");
                for (Map.Entry<String, String> e : env.entrySet()) {
                    sb.append("  ").append(e.getKey()).append("=").append(e.getValue()).append("\n");
                }
            }
            if (!volumes.isEmpty()) {
                sb.append("Volumes: ").append(volumes).append("\n");
            }
            return sb.toString();
        }
    }

    public enum ContainerState {
        CREATED, RUNNING, PAUSED, STOPPED, DEAD
    }

    public static class PortBinding {
        private final int containerPort;
        private final int hostPort;
        private final String hostIP;

        public PortBinding(int containerPort, int hostPort, String hostIP) {
            this.containerPort = containerPort;
            this.hostPort = hostPort;
            this.hostIP = hostIP;
        }

        public int getContainerPort() { return containerPort; }
        public int getHostPort() { return hostPort; }
        public String getHostIP() { return hostIP; }

        @Override
        public String toString() {
            return String.format("%s:%d->%d/tcp", hostIP, hostPort, containerPort);
        }
    }

    public static class Network {
        private final String name;
        private final String driver; // bridge, host, none
        private final Subnet subnet;
        private final IPAddress gateway;
        private final Map<String, IPAddress> containers; // container name -> IP
        private final boolean internal; // sem acesso externo

        public Network(String name, String driver, String cidr, boolean internal) {
            this.name = name;
            this.driver = driver;
            this.subnet = new Subnet(name, cidr, "docker", false);
            this.gateway = subnet.allocateIP(); // primeiro IP é o gateway
            this.containers = new ConcurrentHashMap<>();
            this.internal = internal;
        }

        public Network(String name, String cidr) {
            this(name, "bridge", cidr, false);
        }

        public String getName() { return name; }
        public String getDriver() { return driver; }
        public Subnet getSubnet() { return subnet; }
        public IPAddress getGateway() { return gateway; }
        public boolean isInternal() { return internal; }
        public Map<String, IPAddress> getContainers() {
            return Collections.unmodifiableMap(containers);
        }

        public IPAddress connect(String containerName) {
            if (containers.containsKey(containerName)) {
                return containers.get(containerName);
            }
            IPAddress ip = subnet.allocateIP();
            containers.put(containerName, ip);
            return ip;
        }

        public void disconnect(String containerName) {
            IPAddress ip = containers.remove(containerName);
            if (ip != null) {
                subnet.releaseIP(ip);
            }
        }

        @Override
        public String toString() {
            return String.format("Network[%s, driver=%s, subnet=%s, gateway=%s, containers=%d]",
                name, driver, subnet.getCIDR(), gateway, containers.size());
        }
    }

    private final Map<String, Container> containers;
    private final Map<String, Network> networks;
    private final DNSServer dns;
    private final AtomicInteger containerCounter;

    public ContainerNetwork() {
        this.containers = new ConcurrentHashMap<>();
        this.networks = new ConcurrentHashMap<>();
        this.dns = new DNSServer("docker.internal");
        this.containerCounter = new AtomicInteger(0);

        // Criar rede bridge padrão
        createNetwork("bridge", "172.17.0.0/16");
    }

    public Network createNetwork(String name, String cidr) {
        if (networks.containsKey(name)) {
            throw new IllegalArgumentException("Rede já existe: " + name);
        }
        Network network = new Network(name, cidr);
        networks.put(name, network);
        return network;
    }

    public Network createNetwork(String name, String driver, String cidr, boolean internal) {
        if (networks.containsKey(name)) {
            throw new IllegalArgumentException("Rede já existe: " + name);
        }
        Network network = new Network(name, driver, cidr, internal);
        networks.put(name, network);
        return network;
    }

    public void removeNetwork(String name) {
        if (name.equals("bridge")) {
            throw new IllegalArgumentException("Não é possível remover a rede bridge");
        }
        Network network = networks.get(name);
        if (network != null && !network.getContainers().isEmpty()) {
            throw new IllegalArgumentException("Rede tem containers conectados");
        }
        networks.remove(name);
    }

    public Network getNetwork(String name) {
        return networks.get(name);
    }

    public Collection<Network> getNetworks() {
        return Collections.unmodifiableCollection(networks.values());
    }

    public Container createContainer(String name, String image) {
        if (containers.containsKey(name)) {
            throw new IllegalArgumentException("Container já existe: " + name);
        }
        Container container = new Container(name, image);
        containers.put(name, container);

        // Conectar à rede bridge por padrão
        connectToNetwork(name, "bridge");

        return container;
    }

    public void removeContainer(String name) {
        Container container = containers.get(name);
        if (container == null) {
            throw new IllegalArgumentException("Container não encontrado: " + name);
        }
        if (container.getState() == ContainerState.RUNNING) {
            throw new IllegalArgumentException("Pare o container antes de remover");
        }

        // Desconectar de todas as redes
        for (String networkName : new ArrayList<>(container.getNetworks().keySet())) {
            disconnectFromNetwork(name, networkName);
        }

        containers.remove(name);
    }

    public Container getContainer(String name) {
        return containers.get(name);
    }

    public Container getContainerById(String id) {
        for (Container c : containers.values()) {
            if (c.getId().equals(id) || c.getId().startsWith(id)) {
                return c;
            }
        }
        return null;
    }

    public Collection<Container> getContainers() {
        return Collections.unmodifiableCollection(containers.values());
    }

    public void startContainer(String name) {
        Container container = getContainer(name);
        if (container == null) {
            throw new IllegalArgumentException("Container não encontrado: " + name);
        }
        container.setState(ContainerState.RUNNING);
    }

    public void stopContainer(String name) {
        Container container = getContainer(name);
        if (container == null) {
            throw new IllegalArgumentException("Container não encontrado: " + name);
        }
        container.setState(ContainerState.STOPPED);
    }

    public void connectToNetwork(String containerName, String networkName) {
        Container container = getContainer(containerName);
        Network network = getNetwork(networkName);

        if (container == null) {
            throw new IllegalArgumentException("Container não encontrado: " + containerName);
        }
        if (network == null) {
            throw new IllegalArgumentException("Rede não encontrada: " + networkName);
        }

        IPAddress ip = network.connect(containerName);
        container.connectToNetwork(networkName, ip);

        // Registrar no DNS
        dns.addARecord(containerName, ip.toString());
        dns.addARecord(container.getHostname(), ip.toString());
    }

    public void disconnectFromNetwork(String containerName, String networkName) {
        Container container = getContainer(containerName);
        Network network = getNetwork(networkName);

        if (container != null && network != null) {
            network.disconnect(containerName);
            container.disconnectFromNetwork(networkName);
            dns.removeAllRecords(containerName);
        }
    }

    public String resolve(String hostname) {
        return dns.resolve(hostname);
    }

    public DNSServer getDns() {
        return dns;
    }

    // Simulação de comunicação entre containers
    public boolean canCommunicate(String fromContainer, String toContainer) {
        Container from = getContainer(fromContainer);
        Container to = getContainer(toContainer);

        if (from == null || to == null) return false;
        if (from.getState() != ContainerState.RUNNING || to.getState() != ContainerState.RUNNING) {
            return false;
        }

        // Verificar se estão na mesma rede
        for (String networkName : from.getNetworks().keySet()) {
            if (to.getNetworks().containsKey(networkName)) {
                return true;
            }
        }
        return false;
    }

    public String getStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Container Network ===\n\n");

        sb.append("Networks:\n");
        for (Network network : networks.values()) {
            sb.append("  ").append(network).append("\n");
        }

        sb.append("\nContainers:\n");
        for (Container container : containers.values()) {
            sb.append("  ").append(container).append("\n");
        }

        return sb.toString();
    }
}
