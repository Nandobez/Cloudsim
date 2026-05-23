package com.example.filesystem.network;

import java.util.*;

/**
 * Gerenciador central de rede - controla VPCs, DNS, instâncias e roteamento.
 */
public class NetworkManager {
    private final Map<String, VPC> vpcs;
    private final Map<String, VirtualInstance> instances;
    private final DNSServer dnsServer;
    private final Map<Integer, PortMapping> portMappings;
    private final String publicIP;
    private final String region;
    private int instanceCounter;

    public NetworkManager(String region) {
        this.region = region;
        this.vpcs = new LinkedHashMap<>();
        this.instances = new LinkedHashMap<>();
        this.portMappings = new LinkedHashMap<>();
        this.dnsServer = new DNSServer("internal");
        this.publicIP = generatePublicIP();
        this.instanceCounter = 0;

        // VPC padrão
        createVPC("default", "10.0.0.0/16");
    }

    private String generatePublicIP() {
        Random rand = new Random();
        return String.format("%d.%d.%d.%d",
            rand.nextInt(200) + 50,
            rand.nextInt(256),
            rand.nextInt(256),
            rand.nextInt(256));
    }

    // ==================== VPC Management ====================

    public VPC createVPC(String name, String cidr) {
        if (vpcs.containsKey(name)) {
            throw new IllegalArgumentException("VPC já existe: " + name);
        }
        VPC vpc = new VPC(name, cidr, region);
        vpcs.put(name, vpc);
        return vpc;
    }

    public void deleteVPC(String name) {
        if (name.equals("default")) {
            throw new IllegalArgumentException("Não é possível deletar a VPC padrão");
        }
        VPC vpc = vpcs.get(name);
        if (vpc == null) {
            throw new IllegalArgumentException("VPC não encontrada: " + name);
        }
        // Verificar se há instâncias
        for (VirtualInstance inst : instances.values()) {
            if (inst.getVpcName().equals(name)) {
                throw new IllegalArgumentException("VPC tem instâncias ativas. Remova-as primeiro.");
            }
        }
        vpcs.remove(name);
    }

    public VPC getVPC(String name) {
        return vpcs.get(name);
    }

    public Collection<VPC> getVPCs() {
        return Collections.unmodifiableCollection(vpcs.values());
    }

    // ==================== Subnet Management ====================

    public Subnet createSubnet(String vpcName, String subnetName, String cidr, boolean isPublic) {
        VPC vpc = getVPC(vpcName);
        if (vpc == null) {
            throw new IllegalArgumentException("VPC não encontrada: " + vpcName);
        }
        return vpc.createSubnet(subnetName, cidr, isPublic);
    }

    // ==================== Instance Management ====================

    public VirtualInstance createInstance(String name, String vpcName, String subnetName) {
        VPC vpc = getVPC(vpcName);
        if (vpc == null) {
            throw new IllegalArgumentException("VPC não encontrada: " + vpcName);
        }

        Subnet subnet = subnetName != null ? vpc.getSubnet(subnetName) : vpc.getMainSubnet();
        if (subnet == null) {
            throw new IllegalArgumentException("Subnet não encontrada: " + subnetName);
        }

        if (instances.containsKey(name)) {
            throw new IllegalArgumentException("Instância já existe: " + name);
        }

        String instanceId = "i-" + String.format("%08d", ++instanceCounter);
        IPAddress privateIP = subnet.allocateIP();

        VirtualInstance instance = new VirtualInstance(instanceId, name, privateIP, vpcName, subnet.getName());
        instances.put(name, instance);

        // Registrar no DNS
        dnsServer.addARecord(name + ".internal", privateIP.toString());
        dnsServer.addARecord(instanceId + ".internal", privateIP.toString());

        return instance;
    }

    public void terminateInstance(String name) {
        VirtualInstance instance = instances.get(name);
        if (instance == null) {
            throw new IllegalArgumentException("Instância não encontrada: " + name);
        }

        // Liberar IP
        VPC vpc = getVPC(instance.getVpcName());
        if (vpc != null) {
            Subnet subnet = vpc.getSubnet(instance.getSubnetName());
            if (subnet == null) {
                subnet = vpc.getMainSubnet();
            }
            subnet.releaseIP(instance.getPrivateIP());
        }

        // Remover do DNS
        dnsServer.removeAllRecords(name + ".internal");
        dnsServer.removeAllRecords(instance.getInstanceId() + ".internal");

        // Remover port mappings
        portMappings.entrySet().removeIf(e -> e.getValue().getInstanceName().equals(name));

        instances.remove(name);
        instance.setState(VirtualInstance.State.TERMINATED);
    }

    public VirtualInstance getInstance(String name) {
        return instances.get(name);
    }

    public VirtualInstance getInstanceByIP(String ip) {
        for (VirtualInstance instance : instances.values()) {
            if (instance.getPrivateIP().toString().equals(ip)) {
                return instance;
            }
        }
        return null;
    }

    public Collection<VirtualInstance> getInstances() {
        return Collections.unmodifiableCollection(instances.values());
    }

    // ==================== DNS Management ====================

    public DNSServer getDNSServer() {
        return dnsServer;
    }

    public String resolve(String hostname) {
        return dnsServer.resolve(hostname);
    }

    public void addDNSRecord(String name, String ip) {
        dnsServer.addARecord(name, ip);
    }

    // ==================== Port Forwarding / NAT ====================

    public void addPortMapping(int externalPort, String instanceName, int internalPort) {
        VirtualInstance instance = getInstance(instanceName);
        if (instance == null) {
            throw new IllegalArgumentException("Instância não encontrada: " + instanceName);
        }

        if (portMappings.containsKey(externalPort)) {
            throw new IllegalArgumentException("Porta externa já mapeada: " + externalPort);
        }

        PortMapping mapping = new PortMapping(externalPort, instance.getPrivateIP().toString(),
                                               internalPort, instanceName);
        portMappings.put(externalPort, mapping);
    }

    public void removePortMapping(int externalPort) {
        if (!portMappings.containsKey(externalPort)) {
            throw new IllegalArgumentException("Mapeamento não encontrado: " + externalPort);
        }
        portMappings.remove(externalPort);
    }

    public PortMapping getPortMapping(int externalPort) {
        return portMappings.get(externalPort);
    }

    public Collection<PortMapping> getPortMappings() {
        return Collections.unmodifiableCollection(portMappings.values());
    }

    public String resolveExternalPort(int externalPort) {
        PortMapping mapping = portMappings.get(externalPort);
        if (mapping != null) {
            return mapping.getInternalIP() + ":" + mapping.getInternalPort();
        }
        return null;
    }

    // ==================== Info ====================

    public String getPublicIP() {
        return publicIP;
    }

    public String getRegion() {
        return region;
    }

    public String getStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Network Manager Status ===\n");
        sb.append(String.format("Region: %s\n", region));
        sb.append(String.format("Public IP: %s\n", publicIP));
        sb.append(String.format("VPCs: %d\n", vpcs.size()));
        sb.append(String.format("Instances: %d\n", instances.size()));
        sb.append(String.format("Port Mappings: %d\n", portMappings.size()));
        sb.append(String.format("DNS Records: %d\n", dnsServer.getAllRecords().size()));
        return sb.toString();
    }

    // ==================== Port Mapping Class ====================

    public static class PortMapping {
        private final int externalPort;
        private final String internalIP;
        private final int internalPort;
        private final String instanceName;

        public PortMapping(int externalPort, String internalIP, int internalPort, String instanceName) {
            this.externalPort = externalPort;
            this.internalIP = internalIP;
            this.internalPort = internalPort;
            this.instanceName = instanceName;
        }

        public int getExternalPort() {
            return externalPort;
        }

        public String getInternalIP() {
            return internalIP;
        }

        public int getInternalPort() {
            return internalPort;
        }

        public String getInstanceName() {
            return instanceName;
        }

        @Override
        public String toString() {
            return String.format(":%d -> %s:%d (%s)", externalPort, internalIP, internalPort, instanceName);
        }
    }
}
