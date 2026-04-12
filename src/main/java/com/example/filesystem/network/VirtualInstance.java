package com.example.filesystem.network;

import java.time.Instant;
import java.util.*;

/**
 * Representa uma instância virtual (servidor) na cloud.
 */
public class VirtualInstance {

    public enum State {
        PENDING,
        RUNNING,
        STOPPING,
        STOPPED,
        TERMINATED
    }

    private final String instanceId;
    private final String name;
    private final IPAddress privateIP;
    private IPAddress publicIP;
    private final String vpcName;
    private final String subnetName;
    private State state;
    private final Instant launchTime;
    private final Set<String> securityGroups;
    private final Map<String, String> tags;
    private final Map<Integer, String> openPorts; // porta -> serviço

    public VirtualInstance(String instanceId, String name, IPAddress privateIP,
                          String vpcName, String subnetName) {
        this.instanceId = instanceId;
        this.name = name;
        this.privateIP = privateIP;
        this.vpcName = vpcName;
        this.subnetName = subnetName;
        this.state = State.RUNNING;
        this.launchTime = Instant.now();
        this.securityGroups = new HashSet<>();
        this.securityGroups.add("default");
        this.tags = new LinkedHashMap<>();
        this.openPorts = new LinkedHashMap<>();
    }

    public void openPort(int port, String service) {
        openPorts.put(port, service);
    }

    public void closePort(int port) {
        openPorts.remove(port);
    }

    public boolean isPortOpen(int port) {
        return openPorts.containsKey(port);
    }

    public Map<Integer, String> getOpenPorts() {
        return Collections.unmodifiableMap(openPorts);
    }

    public void addSecurityGroup(String sgName) {
        securityGroups.add(sgName);
    }

    public void removeSecurityGroup(String sgName) {
        if (!sgName.equals("default")) {
            securityGroups.remove(sgName);
        }
    }

    public Set<String> getSecurityGroups() {
        return Collections.unmodifiableSet(securityGroups);
    }

    public void setTag(String key, String value) {
        tags.put(key, value);
    }

    public String getTag(String key) {
        return tags.get(key);
    }

    public Map<String, String> getTags() {
        return Collections.unmodifiableMap(tags);
    }

    public String getInstanceId() {
        return instanceId;
    }

    public String getName() {
        return name;
    }

    public IPAddress getPrivateIP() {
        return privateIP;
    }

    public IPAddress getPublicIP() {
        return publicIP;
    }

    public void setPublicIP(IPAddress publicIP) {
        this.publicIP = publicIP;
    }

    public String getVpcName() {
        return vpcName;
    }

    public String getSubnetName() {
        return subnetName;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public Instant getLaunchTime() {
        return launchTime;
    }

    public void start() {
        if (state == State.STOPPED) {
            state = State.RUNNING;
        }
    }

    public void stop() {
        if (state == State.RUNNING) {
            state = State.STOPPED;
        }
    }

    public boolean isRunning() {
        return state == State.RUNNING;
    }

    @Override
    public String toString() {
        return String.format("Instance[%s (%s), %s, %s, vpc=%s, state=%s]",
            name, instanceId, privateIP,
            publicIP != null ? publicIP.toString() : "no public IP",
            vpcName, state);
    }

    public String getDetailedInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("Instance ID: ").append(instanceId).append("\n");
        sb.append("Name: ").append(name).append("\n");
        sb.append("State: ").append(state).append("\n");
        sb.append("Private IP: ").append(privateIP).append("\n");
        sb.append("Public IP: ").append(publicIP != null ? publicIP : "N/A").append("\n");
        sb.append("VPC: ").append(vpcName).append("\n");
        sb.append("Subnet: ").append(subnetName).append("\n");
        sb.append("Launch Time: ").append(launchTime).append("\n");
        sb.append("Security Groups: ").append(securityGroups).append("\n");
        sb.append("Open Ports: ").append(openPorts).append("\n");
        if (!tags.isEmpty()) {
            sb.append("Tags: ").append(tags).append("\n");
        }
        return sb.toString();
    }
}
