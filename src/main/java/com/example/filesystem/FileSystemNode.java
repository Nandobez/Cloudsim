package com.example.filesystem;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

abstract class FileSystemNode {
    private String name;
    private VirtualDirectory parent;
    private final Instant createdAt;
    private Instant updatedAt;

    protected FileSystemNode(String name) {
        this.name = validateName(name);
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    String getName() {
        return name.isEmpty() ? "/" : name;
    }

    void setName(String newName) {
        this.name = validateName(newName);
        touch();
    }

    VirtualDirectory getParent() {
        return parent;
    }

    void setParent(VirtualDirectory parent) {
        this.parent = parent;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    void touch() {
        this.updatedAt = Instant.now();
    }

    String getPath() {
        if (parent == null) {
            return "/";
        }
        Deque<String> segments = new ArrayDeque<>();
        FileSystemNode current = this;
        while (current != null && current.getParent() != null) {
            segments.push(current.getName());
            current = current.getParent();
        }
        return "/" + String.join("/", segments);
    }

    private static String validateName(String candidate) {
        if (candidate == null) {
            throw new IllegalArgumentException("Nome nulo nao e permitido");
        }
        String trimmed = candidate.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (trimmed.contains("/")) {
            throw new IllegalArgumentException("Nome nao deve conter '/'");
        }
        return trimmed;
    }
}
