package com.example.filesystem.network;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sistema de Arquivos Distribuído (DFS) - simula HDFS/GFS.
 */
public class DistributedFileSystem {

    public static class DataNode {
        private final String id;
        private final String host;
        private final int port;
        private final long capacity;
        private long used;
        private final Map<String, byte[]> blocks;
        private boolean alive;
        private Instant lastHeartbeat;

        public DataNode(String host, int port, long capacity) {
            this.id = UUID.randomUUID().toString().substring(0, 8);
            this.host = host;
            this.port = port;
            this.capacity = capacity;
            this.used = 0;
            this.blocks = new ConcurrentHashMap<>();
            this.alive = true;
            this.lastHeartbeat = Instant.now();
        }

        public String getId() { return id; }
        public String getHost() { return host; }
        public int getPort() { return port; }
        public String getAddress() { return host + ":" + port; }
        public long getCapacity() { return capacity; }
        public long getUsed() { return used; }
        public long getFree() { return capacity - used; }
        public boolean isAlive() { return alive; }
        public void setAlive(boolean alive) { this.alive = alive; }
        public Instant getLastHeartbeat() { return lastHeartbeat; }
        public void heartbeat() { this.lastHeartbeat = Instant.now(); this.alive = true; }

        public boolean storeBlock(String blockId, byte[] data) {
            if (used + data.length > capacity) {
                return false;
            }
            blocks.put(blockId, data);
            used += data.length;
            return true;
        }

        public byte[] getBlock(String blockId) {
            return blocks.get(blockId);
        }

        public boolean deleteBlock(String blockId) {
            byte[] data = blocks.remove(blockId);
            if (data != null) {
                used -= data.length;
                return true;
            }
            return false;
        }

        public Set<String> getBlockIds() {
            return Collections.unmodifiableSet(blocks.keySet());
        }

        public int getBlockCount() {
            return blocks.size();
        }

        @Override
        public String toString() {
            return String.format("DataNode[%s @ %s:%d, used=%s/%s, blocks=%d, alive=%s]",
                id, host, port, formatBytes(used), formatBytes(capacity), blocks.size(), alive);
        }

        private String formatBytes(long bytes) {
            if (bytes < 1024) return bytes + "B";
            if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
            if (bytes < 1024 * 1024 * 1024) return String.format("%.1fMB", bytes / (1024.0 * 1024));
            return String.format("%.1fGB", bytes / (1024.0 * 1024 * 1024));
        }
    }

    public static class BlockInfo {
        private final String blockId;
        private final int size;
        private final String checksum;
        private final List<String> dataNodes; // IDs dos DataNodes que têm este bloco

        public BlockInfo(String blockId, int size, String checksum) {
            this.blockId = blockId;
            this.size = size;
            this.checksum = checksum;
            this.dataNodes = new CopyOnWriteArrayList<>();
        }

        public String getBlockId() { return blockId; }
        public int getSize() { return size; }
        public String getChecksum() { return checksum; }
        public List<String> getDataNodes() { return Collections.unmodifiableList(dataNodes); }
        public void addDataNode(String nodeId) { dataNodes.add(nodeId); }
        public void removeDataNode(String nodeId) { dataNodes.remove(nodeId); }
        public int getReplicationCount() { return dataNodes.size(); }
    }

    public static class DFSFile {
        private final String path;
        private final List<BlockInfo> blocks;
        private final long size;
        private final int replication;
        private final Instant createdAt;
        private Instant modifiedAt;
        private final String owner;

        public DFSFile(String path, List<BlockInfo> blocks, long size, int replication, String owner) {
            this.path = path;
            this.blocks = new ArrayList<>(blocks);
            this.size = size;
            this.replication = replication;
            this.createdAt = Instant.now();
            this.modifiedAt = Instant.now();
            this.owner = owner;
        }

        public String getPath() { return path; }
        public List<BlockInfo> getBlocks() { return Collections.unmodifiableList(blocks); }
        public long getSize() { return size; }
        public int getReplication() { return replication; }
        public Instant getCreatedAt() { return createdAt; }
        public Instant getModifiedAt() { return modifiedAt; }
        public void setModifiedAt(Instant time) { this.modifiedAt = time; }
        public String getOwner() { return owner; }

        @Override
        public String toString() {
            return String.format("DFSFile[%s, size=%d, blocks=%d, replication=%d]",
                path, size, blocks.size(), replication);
        }
    }

    // NameNode - metadados
    private final Map<String, DFSFile> files;
    private final Map<String, DataNode> dataNodes;
    private final int blockSize;
    private final int defaultReplication;
    private final AtomicLong blockIdCounter;
    private final ScheduledExecutorService heartbeatChecker;

    public DistributedFileSystem(int blockSize, int defaultReplication) {
        this.files = new ConcurrentHashMap<>();
        this.dataNodes = new ConcurrentHashMap<>();
        this.blockSize = blockSize;
        this.defaultReplication = defaultReplication;
        this.blockIdCounter = new AtomicLong(0);

        // Verificador de heartbeat
        this.heartbeatChecker = Executors.newSingleThreadScheduledExecutor();
        this.heartbeatChecker.scheduleAtFixedRate(this::checkHeartbeats, 10, 10, TimeUnit.SECONDS);
    }

    public DistributedFileSystem() {
        this(64 * 1024 * 1024, 3); // 64MB blocos, replicação 3
    }

    public DataNode addDataNode(String host, int port, long capacity) {
        DataNode node = new DataNode(host, port, capacity);
        dataNodes.put(node.getId(), node);
        return node;
    }

    public void removeDataNode(String nodeId) {
        DataNode node = dataNodes.remove(nodeId);
        if (node != null) {
            // Remover referências dos blocos
            for (DFSFile file : files.values()) {
                for (BlockInfo block : file.getBlocks()) {
                    block.removeDataNode(nodeId);
                }
            }
        }
    }

    public DataNode getDataNode(String nodeId) {
        return dataNodes.get(nodeId);
    }

    public Collection<DataNode> getDataNodes() {
        return Collections.unmodifiableCollection(dataNodes.values());
    }

    public List<DataNode> getAliveDataNodes() {
        return dataNodes.values().stream()
            .filter(DataNode::isAlive)
            .toList();
    }

    public void heartbeat(String nodeId) {
        DataNode node = dataNodes.get(nodeId);
        if (node != null) {
            node.heartbeat();
        }
    }

    private void checkHeartbeats() {
        Instant threshold = Instant.now().minusSeconds(30);
        for (DataNode node : dataNodes.values()) {
            if (node.getLastHeartbeat().isBefore(threshold)) {
                if (node.isAlive()) {
                    node.setAlive(false);
                    System.out.println("[DFS] DataNode " + node.getId() + " marked as dead");
                    // Trigger re-replication
                    scheduleReReplication(node.getId());
                }
            }
        }
    }

    private void scheduleReReplication(String deadNodeId) {
        // Encontrar blocos que estavam no nó morto e re-replicar
        for (DFSFile file : files.values()) {
            for (BlockInfo block : file.getBlocks()) {
                if (block.getDataNodes().contains(deadNodeId)) {
                    block.removeDataNode(deadNodeId);
                    // Re-replicar se abaixo do nível desejado
                    if (block.getReplicationCount() < file.getReplication()) {
                        replicateBlock(block, file.getReplication());
                    }
                }
            }
        }
    }

    public DFSFile writeFile(String path, byte[] data, String owner) {
        return writeFile(path, data, owner, defaultReplication);
    }

    public DFSFile writeFile(String path, byte[] data, String owner, int replication) {
        List<DataNode> aliveNodes = getAliveDataNodes();
        if (aliveNodes.size() < replication) {
            throw new RuntimeException("Não há DataNodes suficientes para replicação " + replication);
        }

        // Dividir em blocos
        List<BlockInfo> blocks = new ArrayList<>();
        int offset = 0;

        while (offset < data.length) {
            int blockLength = Math.min(blockSize, data.length - offset);
            byte[] blockData = Arrays.copyOfRange(data, offset, offset + blockLength);

            String blockId = "blk_" + blockIdCounter.incrementAndGet();
            String checksum = computeChecksum(blockData);
            BlockInfo blockInfo = new BlockInfo(blockId, blockLength, checksum);

            // Selecionar DataNodes para este bloco
            List<DataNode> selectedNodes = selectDataNodes(aliveNodes, replication, blockLength);

            // Armazenar em cada DataNode
            for (DataNode node : selectedNodes) {
                if (node.storeBlock(blockId, blockData)) {
                    blockInfo.addDataNode(node.getId());
                }
            }

            blocks.add(blockInfo);
            offset += blockLength;
        }

        DFSFile file = new DFSFile(path, blocks, data.length, replication, owner);
        files.put(path, file);

        return file;
    }

    public byte[] readFile(String path) {
        DFSFile file = files.get(path);
        if (file == null) {
            throw new RuntimeException("Arquivo não encontrado: " + path);
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();

        for (BlockInfo block : file.getBlocks()) {
            byte[] blockData = readBlock(block);
            if (blockData == null) {
                throw new RuntimeException("Bloco não encontrado: " + block.getBlockId());
            }

            // Verificar checksum
            String checksum = computeChecksum(blockData);
            if (!checksum.equals(block.getChecksum())) {
                throw new RuntimeException("Checksum inválido para bloco: " + block.getBlockId());
            }

            try {
                output.write(blockData);
            } catch (IOException e) {
                throw new RuntimeException("Erro ao ler bloco: " + e.getMessage());
            }
        }

        return output.toByteArray();
    }

    private byte[] readBlock(BlockInfo block) {
        // Tentar ler de qualquer DataNode que tenha o bloco
        for (String nodeId : block.getDataNodes()) {
            DataNode node = dataNodes.get(nodeId);
            if (node != null && node.isAlive()) {
                byte[] data = node.getBlock(block.getBlockId());
                if (data != null) {
                    return data;
                }
            }
        }
        return null;
    }

    public boolean deleteFile(String path) {
        DFSFile file = files.remove(path);
        if (file == null) {
            return false;
        }

        // Deletar blocos de todos os DataNodes
        for (BlockInfo block : file.getBlocks()) {
            for (String nodeId : block.getDataNodes()) {
                DataNode node = dataNodes.get(nodeId);
                if (node != null) {
                    node.deleteBlock(block.getBlockId());
                }
            }
        }

        return true;
    }

    public boolean exists(String path) {
        return files.containsKey(path);
    }

    public DFSFile getFileInfo(String path) {
        return files.get(path);
    }

    public Collection<DFSFile> listFiles() {
        return Collections.unmodifiableCollection(files.values());
    }

    public Collection<DFSFile> listFiles(String prefix) {
        return files.values().stream()
            .filter(f -> f.getPath().startsWith(prefix))
            .toList();
    }

    private List<DataNode> selectDataNodes(List<DataNode> aliveNodes, int count, int dataSize) {
        // Selecionar nós com espaço suficiente, priorizando os com mais espaço livre
        return aliveNodes.stream()
            .filter(n -> n.getFree() >= dataSize)
            .sorted(Comparator.comparingLong(DataNode::getFree).reversed())
            .limit(count)
            .toList();
    }

    private void replicateBlock(BlockInfo block, int targetReplication) {
        if (block.getDataNodes().isEmpty()) {
            System.err.println("[DFS] Bloco perdido: " + block.getBlockId());
            return;
        }

        // Obter dados do bloco de um nó existente
        byte[] data = null;
        for (String nodeId : block.getDataNodes()) {
            DataNode node = dataNodes.get(nodeId);
            if (node != null && node.isAlive()) {
                data = node.getBlock(block.getBlockId());
                if (data != null) break;
            }
        }

        if (data == null) {
            System.err.println("[DFS] Não foi possível ler bloco para re-replicação: " + block.getBlockId());
            return;
        }

        // Encontrar novos nós
        final byte[] blockData = data; // Cópia final para uso no lambda
        Set<String> existingNodes = new HashSet<>(block.getDataNodes());
        List<DataNode> candidates = getAliveDataNodes().stream()
            .filter(n -> !existingNodes.contains(n.getId()))
            .filter(n -> n.getFree() >= blockData.length)
            .toList();

        int needed = targetReplication - block.getReplicationCount();
        for (int i = 0; i < needed && i < candidates.size(); i++) {
            DataNode node = candidates.get(i);
            if (node.storeBlock(block.getBlockId(), data)) {
                block.addDataNode(node.getId());
            }
        }
    }

    private String computeChecksum(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public void shutdown() {
        heartbeatChecker.shutdownNow();
    }

    public String getStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Distributed File System ===\n");
        sb.append("Block Size: ").append(formatBytes(blockSize)).append("\n");
        sb.append("Default Replication: ").append(defaultReplication).append("\n");
        sb.append("Total Files: ").append(files.size()).append("\n");

        long totalCapacity = 0, totalUsed = 0;
        for (DataNode node : dataNodes.values()) {
            totalCapacity += node.getCapacity();
            totalUsed += node.getUsed();
        }

        sb.append("Total Capacity: ").append(formatBytes(totalCapacity)).append("\n");
        sb.append("Total Used: ").append(formatBytes(totalUsed)).append("\n");

        sb.append("\nDataNodes:\n");
        for (DataNode node : dataNodes.values()) {
            sb.append("  ").append(node).append("\n");
        }

        return sb.toString();
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
