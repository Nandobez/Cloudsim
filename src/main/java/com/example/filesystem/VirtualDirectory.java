package com.example.filesystem;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

class VirtualDirectory extends FileSystemNode {
    private final Map<String, FileSystemNode> children = new LinkedHashMap<>();

    VirtualDirectory(String name) {
        super(name);
    }

    Collection<FileSystemNode> getChildren() {
        return children.values();
    }

    FileSystemNode getChild(String name) {
        return children.get(name);
    }

    void addChild(FileSystemNode node) {
        children.put(node.getName(), node);
        node.setParent(this);
        touch();
    }

    FileSystemNode removeChild(String name) {
        FileSystemNode removed = children.remove(name);
        if (removed != null) {
            removed.setParent(null);
            touch();
        }
        return removed;
    }

    boolean hasChild(String name) {
        return children.containsKey(name);
    }

    boolean isEmpty() {
        return children.isEmpty();
    }

    Map<String, FileSystemNode> snapshotChildren() {
        return new LinkedHashMap<>(children);
    }
}
