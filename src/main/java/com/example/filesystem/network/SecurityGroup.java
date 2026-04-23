package com.example.filesystem.network;

import java.util.*;

/**
 * Security Group - firewall virtual para controlar tráfego de rede.
 */
public class SecurityGroup {
    private final String name;
    private final String description;
    private final List<SecurityRule> inboundRules;
    private final List<SecurityRule> outboundRules;

    public SecurityGroup(String name, String description) {
        this.name = name;
        this.description = description;
        this.inboundRules = new ArrayList<>();
        this.outboundRules = new ArrayList<>();
    }

    public void addInboundRule(SecurityRule rule) {
        inboundRules.add(rule);
    }

    public void addOutboundRule(SecurityRule rule) {
        outboundRules.add(rule);
    }

    public void removeInboundRule(int index) {
        if (index >= 0 && index < inboundRules.size()) {
            inboundRules.remove(index);
        }
    }

    public void removeOutboundRule(int index) {
        if (index >= 0 && index < outboundRules.size()) {
            outboundRules.remove(index);
        }
    }

    public boolean allowsInbound(String protocol, int port, IPAddress sourceIP) {
        for (SecurityRule rule : inboundRules) {
            if (rule.matches(protocol, port, sourceIP)) {
                return true;
            }
        }
        return false;
    }

    public boolean allowsOutbound(String protocol, int port, IPAddress destIP) {
        for (SecurityRule rule : outboundRules) {
            if (rule.matches(protocol, port, destIP)) {
                return true;
            }
        }
        return false;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public List<SecurityRule> getInboundRules() {
        return Collections.unmodifiableList(inboundRules);
    }

    public List<SecurityRule> getOutboundRules() {
        return Collections.unmodifiableList(outboundRules);
    }

    @Override
    public String toString() {
        return String.format("SecurityGroup[%s: %s, inbound=%d, outbound=%d]",
            name, description, inboundRules.size(), outboundRules.size());
    }
}
