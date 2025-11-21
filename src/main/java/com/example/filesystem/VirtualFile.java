package com.example.filesystem;

import java.util.Objects;

class VirtualFile extends FileSystemNode {
    private int firstCluster = FatDisk.NO_CLUSTER;
    private long size;

    VirtualFile(String name) {
        super(name);
    }

    VirtualFile(String name, int firstCluster, long size) {
        super(name);
        this.firstCluster = firstCluster;
        this.size = size;
    }

    void writeContent(FatDisk disk, byte[] content) {
        Objects.requireNonNull(disk, "Disco obrigatorio");
        disk.freeChain(firstCluster);
        if (content == null || content.length == 0) {
            firstCluster = FatDisk.NO_CLUSTER;
            size = 0;
            touch();
            return;
        }
        firstCluster = disk.allocateChainForSize(content.length);
        disk.writeChain(firstCluster, content);
        size = content.length;
        touch();
    }

    byte[] readContent(FatDisk disk) {
        Objects.requireNonNull(disk, "Disco obrigatorio");
        return disk.readChain(firstCluster, size);
    }

    void setFirstCluster(int firstCluster) {
        this.firstCluster = firstCluster;
    }

    void setSize(long size) {
        this.size = size;
    }

    long getSize() {
        return size;
    }

    int getFirstCluster() {
        return firstCluster;
    }
}
