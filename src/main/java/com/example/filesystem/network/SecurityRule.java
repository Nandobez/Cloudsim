package com.example.filesystem.network;

/**
 * Regra de segurança para Security Groups.
 */
public class SecurityRule {
    private final String protocol; // tcp, udp, icmp, all
    private final int fromPort;
    private final int toPort;
    private final String cidrBlock; // ex: 0.0.0.0/0, 10.0.1.0/24
    private final String description;

    public SecurityRule(String protocol, int fromPort, int toPort, String cidrBlock, String description) {
        this.protocol = protocol.toLowerCase();
        this.fromPort = fromPort;
        this.toPort = toPort;
        this.cidrBlock = cidrBlock;
        this.description = description;
    }

    public boolean matches(String proto, int port, IPAddress ip) {
        // Verificar protocolo
        if (!protocol.equals("all") && !protocol.equals(proto.toLowerCase())) {
            return false;
        }

        // Verificar porta
        if (port < fromPort || port > toPort) {
            return false;
        }

        // Verificar IP contra CIDR
        return matchesCIDR(ip);
    }

    private boolean matchesCIDR(IPAddress ip) {
        if (cidrBlock.equals("0.0.0.0/0")) {
            return true; // Qualquer IP
        }

        try {
            String[] parts = cidrBlock.split("/");
            IPAddress networkIP = new IPAddress(parts[0]);
            int prefix = Integer.parseInt(parts[1]);

            long mask = prefix == 0 ? 0 : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
            return (ip.getNumericValue() & mask) == (networkIP.getNumericValue() & mask);
        } catch (Exception e) {
            return false;
        }
    }

    public String getProtocol() {
        return protocol;
    }

    public int getFromPort() {
        return fromPort;
    }

    public int getToPort() {
        return toPort;
    }

    public String getCidrBlock() {
        return cidrBlock;
    }

    public String getDescription() {
        return description;
    }

    public String getPortRange() {
        if (fromPort == toPort) {
            return String.valueOf(fromPort);
        }
        return fromPort + "-" + toPort;
    }

    @Override
    public String toString() {
        return String.format("%s %s %s \"%s\"",
            protocol.toUpperCase(), getPortRange(), cidrBlock, description);
    }
}
