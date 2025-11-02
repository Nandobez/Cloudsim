package com.example.filesystem;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

class FatDisk {
    static final int FREE = -1;
    static final int END_OF_CHAIN = -2;
    static final int NO_CLUSTER = -3;

    private static final int HEADER_SIZE = 512;
    private static final byte[] MAGIC = "FAT1".getBytes(StandardCharsets.US_ASCII);
    private static final int VERSION = 1;

    private final int clusterSize;
    private final int clusterCount;
    private final Path dataPath;
    private final int[] fat;

    private final long fatOffset;
    private final long dataOffset;
    private final long metadataOffset;

    FatDisk(int clusterCount, int clusterSize) {
        this(clusterCount, clusterSize, Path.of("filesystem_data.dat"));
    }

    FatDisk(int clusterCount, int clusterSize, Path dataPath) {
        if (clusterCount <= 0 || clusterSize <= 0) {
            throw new IllegalArgumentException("clusterCount e clusterSize devem ser positivos");
        }
        this.clusterCount = clusterCount;
        this.clusterSize = clusterSize;
        this.dataPath = dataPath;

        this.fatOffset = HEADER_SIZE;
        this.dataOffset = fatOffset + ((long) clusterCount * 4);
        this.metadataOffset = dataOffset + ((long) clusterCount * clusterSize);

        if (dataPath.getParent() != null) {
            try {
                Files.createDirectories(dataPath.getParent());
            } catch (IOException e) {
                throw new UncheckedIOException("Nao foi possivel criar diretorio para dados", e);
            }
        }

        this.fat = new int[clusterCount];

        if (Files.exists(dataPath)) {
            loadFromDisk();
        } else {
            initializeNewDisk();
        }
    }

    private void initializeNewDisk() {
        Arrays.fill(fat, FREE);

        try (RandomAccessFile file = new RandomAccessFile(dataPath.toFile(), "rw")) {
            writeHeader(file);
            writeFatTable(file);
            long totalSize = metadataOffset + 4096;
            file.setLength(totalSize);
        } catch (IOException e) {
            throw new UncheckedIOException("Nao foi possivel inicializar arquivo de dados", e);
        }
    }

    private void loadFromDisk() {
        try (RandomAccessFile file = new RandomAccessFile(dataPath.toFile(), "r")) {
            readAndValidateHeader(file);
            loadFatTable(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Nao foi possivel carregar arquivo de dados", e);
        }
    }

    private void writeHeader(RandomAccessFile file) throws IOException {
        file.seek(0);
        ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE);

        header.put(MAGIC);
        header.putInt(VERSION);
        header.putInt(clusterCount);
        header.putInt(clusterSize);
        header.putLong(fatOffset);
        header.putLong(dataOffset);
        header.putLong(metadataOffset);

        int checksum = calculateChecksum(header.array(), 0, 44);
        header.putInt(checksum);

        file.write(header.array());
    }

    private void readAndValidateHeader(RandomAccessFile file) throws IOException {
        file.seek(0);
        byte[] headerBytes = new byte[HEADER_SIZE];
        file.readFully(headerBytes);

        ByteBuffer header = ByteBuffer.wrap(headerBytes);

        byte[] magic = new byte[4];
        header.get(magic);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new IOException("Arquivo de dados invalido: magic number incorreto");
        }

        int version = header.getInt();
        if (version != VERSION) {
            throw new IOException("Versao incompativel: esperado " + VERSION + ", encontrado " + version);
        }

        int fileClusterCount = header.getInt();
        int fileClusterSize = header.getInt();

        if (fileClusterCount != clusterCount || fileClusterSize != clusterSize) {
            throw new IOException(String.format(
                "Parametros incompativeis: esperado clusters=%d, tamanho=%d; encontrado clusters=%d, tamanho=%d",
                clusterCount, clusterSize, fileClusterCount, fileClusterSize));
        }
    }

    private void writeFatTable(RandomAccessFile file) throws IOException {
        file.seek(fatOffset);
        ByteBuffer buffer = ByteBuffer.allocate(clusterCount * 4);
        for (int entry : fat) {
            buffer.putInt(entry);
        }
        file.write(buffer.array());
    }

    private void loadFatTable(RandomAccessFile file) throws IOException {
        file.seek(fatOffset);
        byte[] fatBytes = new byte[clusterCount * 4];
        file.readFully(fatBytes);

        ByteBuffer buffer = ByteBuffer.wrap(fatBytes);
        for (int i = 0; i < clusterCount; i++) {
            fat[i] = buffer.getInt();
        }
    }

    private void persistFatEntry(int clusterIndex) {
        try (RandomAccessFile file = new RandomAccessFile(dataPath.toFile(), "rw")) {
            long position = fatOffset + ((long) clusterIndex * 4);
            file.seek(position);
            ByteBuffer buffer = ByteBuffer.allocate(4);
            buffer.putInt(fat[clusterIndex]);
            file.write(buffer.array());
        } catch (IOException e) {
            throw new UncheckedIOException("Erro ao persistir entrada FAT", e);
        }
    }

    private void persistFatEntries(List<Integer> indices) {
        if (indices.isEmpty()) return;

        try (RandomAccessFile file = new RandomAccessFile(dataPath.toFile(), "rw")) {
            for (int index : indices) {
                long position = fatOffset + ((long) index * 4);
                file.seek(position);
                ByteBuffer buffer = ByteBuffer.allocate(4);
                buffer.putInt(fat[index]);
                file.write(buffer.array());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Erro ao persistir entradas FAT", e);
        }
    }

    private int calculateChecksum(byte[] data, int offset, int length) {
        int checksum = 0;
        for (int i = offset; i < offset + length; i++) {
            checksum = (checksum + (data[i] & 0xFF)) & 0xFFFFFFFF;
        }
        return checksum;
    }

    private byte[] readCluster(int clusterIndex) {
        try (RandomAccessFile file = new RandomAccessFile(dataPath.toFile(), "r")) {
            long position = dataOffset + ((long) clusterIndex * clusterSize);
            file.seek(position);
            byte[] cluster = new byte[clusterSize];
            int bytesRead = file.read(cluster);
            if (bytesRead < 0) {
                Arrays.fill(cluster, (byte) 0);
            } else if (bytesRead < clusterSize) {
                Arrays.fill(cluster, bytesRead, clusterSize, (byte) 0);
            }
            return cluster;
        } catch (IOException e) {
            throw new UncheckedIOException("Erro ao ler cluster do disco", e);
        }
    }

    private void writeCluster(int clusterIndex, byte[] data) {
        try (RandomAccessFile file = new RandomAccessFile(dataPath.toFile(), "rw")) {
            long position = dataOffset + ((long) clusterIndex * clusterSize);
            file.seek(position);
            file.write(data, 0, Math.min(data.length, clusterSize));

            if (data.length < clusterSize) {
                byte[] padding = new byte[clusterSize - data.length];
                file.write(padding);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Erro ao escrever cluster no disco", e);
        }
    }

    int allocateChainForSize(long sizeInBytes) {
        if (sizeInBytes <= 0) {
            return NO_CLUSTER;
        }
        int needed = (int) Math.ceil((double) sizeInBytes / clusterSize);
        List<Integer> free = new ArrayList<>(needed);
        for (int i = 0; i < clusterCount && free.size() < needed; i++) {
            if (fat[i] == FREE) {
                free.add(i);
            }
        }
        if (free.size() < needed) {
            throw new IllegalStateException("Espaco insuficiente: precisa de " + needed + " clusters, disponiveis: " + free.size());
        }

        List<Integer> modifiedEntries = new ArrayList<>();
        for (int i = 0; i < free.size(); i++) {
            int cluster = free.get(i);
            int next = (i == free.size() - 1) ? END_OF_CHAIN : free.get(i + 1);
            fat[cluster] = next;
            modifiedEntries.add(cluster);

            byte[] zeros = new byte[clusterSize];
            writeCluster(cluster, zeros);
        }

        persistFatEntries(modifiedEntries);

        return free.get(0);
    }

    void freeChain(int startCluster) {
        if (startCluster == NO_CLUSTER || startCluster == FREE) {
            return;
        }

        List<Integer> modifiedEntries = new ArrayList<>();
        int current = startCluster;

        while (current != END_OF_CHAIN && current >= 0 && current < clusterCount) {
            int next = fat[current];

            byte[] zeros = new byte[clusterSize];
            writeCluster(current, zeros);

            fat[current] = FREE;
            modifiedEntries.add(current);
            current = next;
        }

        persistFatEntries(modifiedEntries);
    }

    void writeChain(int startCluster, byte[] content) {
        if (content == null || content.length == 0) {
            if (startCluster != NO_CLUSTER) {
                zeroChain(startCluster);
            }
            return;
        }
        if (startCluster == NO_CLUSTER) {
            throw new IllegalStateException("Arquivo sem cluster nao pode receber conteudo");
        }

        int remaining = content.length;
        int offset = 0;
        int current = startCluster;

        while (current != END_OF_CHAIN && remaining > 0) {
            int toWrite = Math.min(clusterSize, remaining);
            byte[] clusterData = new byte[clusterSize];
            System.arraycopy(content, offset, clusterData, 0, toWrite);
            writeCluster(current, clusterData);

            offset += toWrite;
            remaining -= toWrite;
            current = fat[current];
        }

        if (remaining > 0 && current == END_OF_CHAIN) {
            throw new IllegalStateException("Cadeia menor do que o conteudo");
        }

        while (current != END_OF_CHAIN && current >= 0 && current < clusterCount) {
            zeroCluster(current);
            current = fat[current];
        }
    }

    byte[] readChain(int startCluster, long sizeInBytes) {
        if (sizeInBytes <= 0 || startCluster == NO_CLUSTER) {
            return new byte[0];
        }

        byte[] buffer = new byte[(int) sizeInBytes];
        int remaining = buffer.length;
        int offset = 0;
        int current = startCluster;

        while (current != END_OF_CHAIN && remaining > 0 && current >= 0 && current < clusterCount) {
            int toRead = Math.min(clusterSize, remaining);
            byte[] clusterData = readCluster(current);
            System.arraycopy(clusterData, 0, buffer, offset, toRead);

            remaining -= toRead;
            offset += toRead;
            current = fat[current];
        }

        if (remaining > 0) {
            throw new IllegalStateException("Cadeia terminou antes do final do arquivo");
        }

        return buffer;
    }

    void saveMetadata(byte[] metadata) {
        try (RandomAccessFile file = new RandomAccessFile(dataPath.toFile(), "rw")) {
            file.seek(metadataOffset);

            ByteBuffer sizeBuffer = ByteBuffer.allocate(4);
            sizeBuffer.putInt(metadata.length);
            file.write(sizeBuffer.array());

            file.write(metadata);

        } catch (IOException e) {
            throw new UncheckedIOException("Erro ao salvar metadados", e);
        }
    }

    byte[] loadMetadata() {
        try (RandomAccessFile file = new RandomAccessFile(dataPath.toFile(), "r")) {
            if (file.length() <= metadataOffset + 4) {
                return new byte[0];
            }

            file.seek(metadataOffset);

            byte[] sizeBytes = new byte[4];
            file.readFully(sizeBytes);
            int size = ByteBuffer.wrap(sizeBytes).getInt();

            if (size <= 0 || size > file.length() - metadataOffset - 4) {
                return new byte[0];
            }

            byte[] metadata = new byte[size];
            file.readFully(metadata);
            return metadata;

        } catch (IOException e) {
            throw new UncheckedIOException("Erro ao carregar metadados", e);
        }
    }

    int getClusterSize() {
        return clusterSize;
    }

    int getClusterCount() {
        return clusterCount;
    }

    Path getDataPath() {
        return dataPath;
    }

    long bytesFree() {
        long freeClusters = 0;
        for (int entry : fat) {
            if (entry == FREE) {
                freeClusters++;
            }
        }
        return freeClusters * (long) clusterSize;
    }

    long bytesTotal() {
        return (long) clusterCount * clusterSize;
    }

    long bytesUsed() {
        return bytesTotal() - bytesFree();
    }

    int[] snapshotFat() {
        return Arrays.copyOf(fat, fat.length);
    }

    List<Integer> getChainClusters(int startCluster) {
        List<Integer> chain = new ArrayList<>();
        if (startCluster == NO_CLUSTER) {
            return chain;
        }

        int current = startCluster;
        while (current != END_OF_CHAIN && current >= 0 && current < clusterCount) {
            chain.add(current);
            current = fat[current];
        }
        return chain;
    }

    private void zeroCluster(int clusterIndex) {
        byte[] zeros = new byte[clusterSize];
        writeCluster(clusterIndex, zeros);
    }

    private void zeroChain(int startCluster) {
        int current = startCluster;
        while (current != END_OF_CHAIN && current >= 0 && current < clusterCount) {
            zeroCluster(current);
            current = fat[current];
        }
    }

    void syncFat() {
        try (RandomAccessFile file = new RandomAccessFile(dataPath.toFile(), "rw")) {
            writeFatTable(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Erro ao sincronizar FAT", e);
        }
    }
}
