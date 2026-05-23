package com.example.filesystem.network;

import java.util.*;

/**
 * Representa uma subnet com notação CIDR (ex: 10.0.1.0/24).
 */
public class Subnet {
    private final String name;
    private final IPAddress networkAddress;
    private final int prefixLength;
    private final long networkMask;
    private final long wildcardMask;
    private final IPAddress broadcastAddress;
    private final IPAddress firstUsableIP;
    private final IPAddress lastUsableIP;
    private final Set<IPAddress> allocatedIPs;
    private final String vpcName;
    private final boolean isPublic;

    public Subnet(String name, String cidr, String vpcName, boolean isPublic) {
        this.name = name;
        this.vpcName = vpcName;
        this.isPublic = isPublic;
        this.allocatedIPs = new HashSet<>();

        String[] parts = cidr.split("/");
        this.networkAddress = new IPAddress(parts[0]);
        this.prefixLength = Integer.parseInt(parts[1]);

        if (prefixLength < 0 || prefixLength > 32) {
            throw new IllegalArgumentException("Prefix length inválido: " + prefixLength);
        }

        this.networkMask = prefixLength == 0 ? 0 : (0xFFFFFFFFL << (32 - prefixLength)) & 0xFFFFFFFFL;
        this.wildcardMask = ~networkMask & 0xFFFFFFFFL;

        long networkNum = networkAddress.getNumericValue() & networkMask;
        this.broadcastAddress = new IPAddress(networkNum | wildcardMask);
        this.firstUsableIP = new IPAddress(networkNum + 1);
        this.lastUsableIP = new IPAddress((networkNum | wildcardMask) - 1);

        // Reservar endereço de rede e broadcast
        allocatedIPs.add(networkAddress);
        allocatedIPs.add(broadcastAddress);
    }

    public boolean contains(IPAddress ip) {
        long ipNum = ip.getNumericValue();
        long networkNum = networkAddress.getNumericValue();
        return (ipNum & networkMask) == (networkNum & networkMask);
    }

    public synchronized IPAddress allocateIP() {
        IPAddress current = firstUsableIP;
        while (current.compareTo(lastUsableIP) <= 0) {
            if (!allocatedIPs.contains(current)) {
                allocatedIPs.add(current);
                return current;
            }
            current = current.next();
        }
        throw new RuntimeException("Sem IPs disponíveis na subnet " + name);
    }

    public synchronized IPAddress allocateSpecificIP(IPAddress ip) {
        if (!contains(ip)) {
            throw new IllegalArgumentException("IP " + ip + " não pertence à subnet " + getCIDR());
        }
        if (allocatedIPs.contains(ip)) {
            throw new IllegalArgumentException("IP " + ip + " já está alocado");
        }
        allocatedIPs.add(ip);
        return ip;
    }

    public synchronized void releaseIP(IPAddress ip) {
        if (ip.equals(networkAddress) || ip.equals(broadcastAddress)) {
            return; // Não liberar endereço de rede ou broadcast
        }
        allocatedIPs.remove(ip);
    }

    public String getName() {
        return name;
    }

    public String getCIDR() {
        return networkAddress.toString() + "/" + prefixLength;
    }

    public IPAddress getNetworkAddress() {
        return networkAddress;
    }

    public int getPrefixLength() {
        return prefixLength;
    }

    public IPAddress getBroadcastAddress() {
        return broadcastAddress;
    }

    public IPAddress getFirstUsableIP() {
        return firstUsableIP;
    }

    public IPAddress getLastUsableIP() {
        return lastUsableIP;
    }

    public String getVpcName() {
        return vpcName;
    }

    public boolean isPublic() {
        return isPublic;
    }

    public int getTotalHosts() {
        return (int) (wildcardMask - 1); // -2 para rede e broadcast, +1 para contar
    }

    public int getAvailableHosts() {
        return getTotalHosts() - allocatedIPs.size() + 2; // +2 porque rede e broadcast estão na lista
    }

    public Set<IPAddress> getAllocatedIPs() {
        return Collections.unmodifiableSet(allocatedIPs);
    }

    @Override
    public String toString() {
        return String.format("Subnet[%s, %s, vpc=%s, public=%s, available=%d/%d]",
            name, getCIDR(), vpcName, isPublic, getAvailableHosts(), getTotalHosts());
    }
}
