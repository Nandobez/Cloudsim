package com.example.filesystem.network;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Representa um endereço IPv4.
 */
public class IPAddress implements Comparable<IPAddress> {
    private static final Pattern IPV4_PATTERN = Pattern.compile(
        "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
    );

    private final int[] octets;
    private final long numericValue;

    public IPAddress(String ip) {
        if (!isValid(ip)) {
            throw new IllegalArgumentException("IP inválido: " + ip);
        }
        this.octets = parseOctets(ip);
        this.numericValue = toNumeric(octets);
    }

    public IPAddress(int o1, int o2, int o3, int o4) {
        this.octets = new int[]{o1, o2, o3, o4};
        this.numericValue = toNumeric(octets);
    }

    public IPAddress(long numeric) {
        this.numericValue = numeric;
        this.octets = fromNumeric(numeric);
    }

    public static boolean isValid(String ip) {
        return ip != null && IPV4_PATTERN.matcher(ip).matches();
    }

    private static int[] parseOctets(String ip) {
        String[] parts = ip.split("\\.");
        return new int[]{
            Integer.parseInt(parts[0]),
            Integer.parseInt(parts[1]),
            Integer.parseInt(parts[2]),
            Integer.parseInt(parts[3])
        };
    }

    private static long toNumeric(int[] octets) {
        return ((long) octets[0] << 24) | ((long) octets[1] << 16) |
               ((long) octets[2] << 8) | octets[3];
    }

    private static int[] fromNumeric(long numeric) {
        return new int[]{
            (int) ((numeric >> 24) & 0xFF),
            (int) ((numeric >> 16) & 0xFF),
            (int) ((numeric >> 8) & 0xFF),
            (int) (numeric & 0xFF)
        };
    }

    public int[] getOctets() {
        return octets.clone();
    }

    public long getNumericValue() {
        return numericValue;
    }

    public IPAddress next() {
        return new IPAddress(numericValue + 1);
    }

    public IPAddress previous() {
        return new IPAddress(numericValue - 1);
    }

    public boolean isPrivate() {
        // 10.0.0.0/8
        if (octets[0] == 10) return true;
        // 172.16.0.0/12
        if (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31) return true;
        // 192.168.0.0/16
        if (octets[0] == 192 && octets[1] == 168) return true;
        return false;
    }

    public boolean isLoopback() {
        return octets[0] == 127;
    }

    @Override
    public String toString() {
        return octets[0] + "." + octets[1] + "." + octets[2] + "." + octets[3];
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        IPAddress ipAddress = (IPAddress) o;
        return numericValue == ipAddress.numericValue;
    }

    @Override
    public int hashCode() {
        return Objects.hash(numericValue);
    }

    @Override
    public int compareTo(IPAddress other) {
        return Long.compare(this.numericValue, other.numericValue);
    }
}
