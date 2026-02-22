package com.example.filesystem.network;

import java.util.*;

/**
 * Virtual Private Cloud - rede isolada com suas próprias subnets.
 */
public class VPC {
    private final String name;
    private final String cidr;
    private final Subnet mainSubnet;
    private final Map<String, Subnet> subnets;
    private final Map<String, SecurityGroup> securityGroups;
    private final String region;

    public VPC(String name, String cidr, String region) {
        this.name = name;
        this.cidr = cidr;
        this.region = region;
        this.subnets = new LinkedHashMap<>();
        this.securityGroups = new LinkedHashMap<>();

        // Subnet principal da VPC
        this.mainSubnet = new Subnet(name + "-main", cidr, name, false);

        // Security group padrão
        SecurityGroup defaultSG = new SecurityGroup("default", "Grupo de segurança padrão");
        defaultSG.addInboundRule(new SecurityRule("tcp", 22, 22, "0.0.0.0/0", "SSH"));
        defaultSG.addInboundRule(new SecurityRule("tcp", 80, 80, "0.0.0.0/0", "HTTP"));
        defaultSG.addInboundRule(new SecurityRule("tcp", 443, 443, "0.0.0.0/0", "HTTPS"));
        defaultSG.addOutboundRule(new SecurityRule("all", 0, 65535, "0.0.0.0/0", "All traffic"));
        securityGroups.put("default", defaultSG);
    }

    public Subnet createSubnet(String name, String cidr, boolean isPublic) {
        if (subnets.containsKey(name)) {
            throw new IllegalArgumentException("Subnet já existe: " + name);
        }

        // Verificar se CIDR está dentro da VPC
        Subnet newSubnet = new Subnet(name, cidr, this.name, isPublic);
        IPAddress subnetNetwork = newSubnet.getNetworkAddress();

        if (!mainSubnet.contains(subnetNetwork)) {
            throw new IllegalArgumentException("CIDR " + cidr + " não está dentro da VPC " + this.cidr);
        }

        subnets.put(name, newSubnet);
        return newSubnet;
    }

    public void deleteSubnet(String name) {
        if (!subnets.containsKey(name)) {
            throw new IllegalArgumentException("Subnet não encontrada: " + name);
        }
        subnets.remove(name);
    }

    public Subnet getSubnet(String name) {
        return subnets.get(name);
    }

    public Collection<Subnet> getSubnets() {
        return Collections.unmodifiableCollection(subnets.values());
    }

    public SecurityGroup createSecurityGroup(String name, String description) {
        if (securityGroups.containsKey(name)) {
            throw new IllegalArgumentException("Security group já existe: " + name);
        }
        SecurityGroup sg = new SecurityGroup(name, description);
        securityGroups.put(name, sg);
        return sg;
    }

    public SecurityGroup getSecurityGroup(String name) {
        return securityGroups.get(name);
    }

    public Collection<SecurityGroup> getSecurityGroups() {
        return Collections.unmodifiableCollection(securityGroups.values());
    }

    public String getName() {
        return name;
    }

    public String getCidr() {
        return cidr;
    }

    public String getRegion() {
        return region;
    }

    public Subnet getMainSubnet() {
        return mainSubnet;
    }

    @Override
    public String toString() {
        return String.format("VPC[%s, %s, region=%s, subnets=%d]",
            name, cidr, region, subnets.size());
    }
}
