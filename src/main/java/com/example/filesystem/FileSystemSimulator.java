package com.example.filesystem;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

public class FileSystemSimulator {

    public record ListingEntry(String name, boolean directory, long size, Instant createdAt, Instant updatedAt) {
    }

    public record DiskInfo(int clusterCount, int clusterSize, long freeBytes, long usedBytes, long totalBytes) {
    }

    private final VirtualDirectory root = new VirtualDirectory("");
    private final Journal journal;
    private final FatDisk disk;
    private boolean recovering = false;

    private static final int DEFAULT_CLUSTER_COUNT = 2048;
    private static final int DEFAULT_CLUSTER_SIZE = 4096;

    public FileSystemSimulator(Path journalPath) {
        this(journalPath, DEFAULT_CLUSTER_COUNT, DEFAULT_CLUSTER_SIZE);
    }

    public FileSystemSimulator(Path journalPath, int clusterCount, int clusterSize) {
        this(journalPath, clusterCount, clusterSize, Path.of("filesystem_data.dat"));
    }

    public FileSystemSimulator(Path journalPath, int clusterCount, int clusterSize, Path dataPath) {
        this(new Journal(journalPath), new FatDisk(clusterCount, clusterSize, dataPath));
    }

    public FileSystemSimulator(Journal journal) {
        this(journal, new FatDisk(DEFAULT_CLUSTER_COUNT, DEFAULT_CLUSTER_SIZE));
    }

    public FileSystemSimulator(Journal journal, FatDisk disk) {
        this.journal = Objects.requireNonNull(journal, "Journal obrigatorio");
        this.disk = Objects.requireNonNull(disk, "Disco FAT obrigatorio");
        recoverFromJournal();
    }

    public Path journalPath() {
        return journal.getLogPath();
    }

    public Path dataPath() {
        return disk.getDataPath();
    }

    public DiskInfo diskInfo() {
        return new DiskInfo(
            disk.getClusterCount(),
            disk.getClusterSize(),
            disk.bytesFree(),
            disk.bytesUsed(),
            disk.bytesTotal()
        );
    }

    public void createDirectory(String path) {
        String normalized = normalizePath(path);
        executeWithTransaction(() -> {
            journal.append(new JournalEntry(OperationType.CREATE_DIRECTORY, List.of(normalized)));
            createDirectoryInternal(normalized);
        });
    }

    public void deleteDirectory(String path, boolean recursive) {
        String normalized = normalizePath(path);
        executeWithTransaction(() -> {
            journal.append(new JournalEntry(OperationType.DELETE_DIRECTORY,
                List.of(normalized, Boolean.toString(recursive))));
            deleteDirectoryInternal(normalized, recursive);
        });
    }

    public void renameDirectory(String path, String newName) {
        String normalized = normalizePath(path);
        String sanitized = sanitizeName(newName);
        executeWithTransaction(() -> {
            journal.append(new JournalEntry(OperationType.RENAME_DIRECTORY,
                List.of(normalized, sanitized)));
            renameDirectoryInternal(normalized, sanitized);
        });
    }

    public void createOrOverwriteFile(String path, byte[] content) {
        String normalized = normalizePath(path);
        byte[] safeContent = content == null ? new byte[0] : content;

        executeWithTransaction(() -> {
            String base64Content = Base64.getEncoder().encodeToString(safeContent);
            journal.append(new JournalEntry(OperationType.CREATE_FILE_WITH_CONTENT,
                List.of(normalized, base64Content)));

            VirtualDirectory parent = resolveParentDirectory(parseSegments(normalized));
            String name = leafName(normalized);
            FileSystemNode existing = parent.getChild(name);

            if (existing instanceof VirtualDirectory) {
                throw new IllegalStateException("Ja existe um diretorio com esse nome: " + normalized);
            }

            VirtualFile file;
            if (existing instanceof VirtualFile existingFile) {
                file = existingFile;
            } else {
                file = new VirtualFile(name);
                parent.addChild(file);
            }

            file.writeContent(disk, safeContent);

            journal.append(new JournalEntry(OperationType.WRITE_FILE,
                List.of(normalized,
                    String.valueOf(file.getFirstCluster()),
                    String.valueOf(file.getSize()))));
        });
    }

    public void copyFile(String sourcePath, String destinationPath) {
        String source = normalizePath(sourcePath);
        String destination = normalizePath(destinationPath);

        executeWithTransaction(() -> {
            VirtualFile sourceFile = requireFile(source);
            byte[] content = sourceFile.readContent(disk);

            journal.append(new JournalEntry(OperationType.COPY_FILE,
                List.of(source, destination)));

            copyFileInternal(source, destination);
        });
    }

    public void deleteFile(String path) {
        String normalized = normalizePath(path);
        executeWithTransaction(() -> {
            journal.append(new JournalEntry(OperationType.DELETE_FILE, List.of(normalized)));
            deleteFileInternal(normalized);
        });
    }

    public void renameFile(String path, String newName) {
        String normalized = normalizePath(path);
        String sanitized = sanitizeName(newName);
        executeWithTransaction(() -> {
            journal.append(new JournalEntry(OperationType.RENAME_FILE,
                List.of(normalized, sanitized)));
            renameFileInternal(normalized, sanitized);
        });
    }

    public List<ListingEntry> list(String directoryPath) {
        String normalized = normalizePath(directoryPath);
        VirtualDirectory directory = resolveDirectory(normalized);
        return directory.getChildren().stream()
                .sorted(Comparator.comparing(FileSystemNode::getName))
                .map(node -> new ListingEntry(
                    node.getName(),
                    node instanceof VirtualDirectory,
                    node instanceof VirtualFile file ? file.getSize() : 0,
                    node.getCreatedAt(),
                    node.getUpdatedAt()))
                .collect(Collectors.toList());
    }

    public Optional<byte[]> readFile(String path) {
        FileSystemNode node = resolveNodeOrNull(normalizePath(path));
        if (node instanceof VirtualFile file) {
            return Optional.of(file.readContent(disk));
        }
        return Optional.empty();
    }

    public boolean exists(String path) {
        return resolveNodeOrNull(normalizePath(path)) != null;
    }

    public boolean isDirectory(String path) {
        FileSystemNode node = resolveNodeOrNull(normalizePath(path));
        return node instanceof VirtualDirectory;
    }

    public boolean isFile(String path) {
        FileSystemNode node = resolveNodeOrNull(normalizePath(path));
        return node instanceof VirtualFile;
    }

    public String dumpTree() {
        StringBuilder builder = new StringBuilder();
        DiskInfo info = diskInfo();
        builder.append(String.format("=== Sistema de Arquivos FAT ===%n"));
        builder.append(String.format("Arquivo de dados: %s%n", disk.getDataPath().toAbsolutePath()));
        builder.append(String.format("Journal: %s%n", journal.getLogPath().toAbsolutePath()));
        builder.append(String.format("Clusters: %d x %d bytes%n", info.clusterCount(), info.clusterSize()));
        builder.append(String.format("Espaco: total=%d, usado=%d, livre=%d bytes%n",
                info.totalBytes(), info.usedBytes(), info.freeBytes()));
        builder.append(String.format("================================%n"));
        recurseDump(builder, root, 0);
        return builder.toString();
    }

    public void checkpoint() {
        journal.checkpoint();
        disk.syncFat();
    }

    public void truncateJournal() {
        journal.truncateToLastCheckpoint();
    }

    private void executeWithTransaction(Runnable operation) {
        if (recovering) {
            operation.run();
            return;
        }

        journal.beginTransaction();
        try {
            operation.run();
            journal.commitTransaction();
        } catch (Exception e) {
            journal.abortTransaction();
            throw e;
        }
    }

    private void recoverFromJournal() {
        recovering = true;
        try {
            List<JournalEntry> entries = journal.readCommittedEntries();

            for (JournalEntry entry : entries) {
                try {
                    applyRecoveryEntry(entry);
                } catch (Exception e) {
                    System.err.println("Aviso: erro ao recuperar entrada do journal: " + e.getMessage());
                }
            }
        } finally {
            recovering = false;
        }
    }

    private void applyRecoveryEntry(JournalEntry entry) {
        List<String> args = entry.getArguments();
        switch (entry.getType()) {
            case CREATE_DIRECTORY -> createDirectoryInternal(args.get(0));
            case DELETE_DIRECTORY -> deleteDirectoryInternal(args.get(0), Boolean.parseBoolean(args.get(1)));
            case RENAME_DIRECTORY -> renameDirectoryInternal(args.get(0), args.get(1));
            case CREATE_FILE_WITH_CONTENT -> createFileWithContentInternal(args.get(0), args.get(1));
            case CREATE_FILE -> createFileInternal(args.get(0));
            case WRITE_FILE -> updateFileMetadata(args.get(0),
                Integer.parseInt(args.get(1)), Long.parseLong(args.get(2)));
            case COPY_FILE -> copyFileInternal(args.get(0), args.get(1));
            case DELETE_FILE -> deleteFileInternal(args.get(0));
            case RENAME_FILE -> renameFileInternal(args.get(0), args.get(1));
            case CHECKPOINT -> { }
            case BEGIN_TRANSACTION, COMMIT_TRANSACTION, ABORT_TRANSACTION -> { }
        }
    }

    private void createDirectoryInternal(String normalizedPath) {
        List<String> segments = parseSegments(normalizedPath);
        if (segments.isEmpty()) {
            return;
        }
        VirtualDirectory parent = resolveParentDirectory(segments);
        String name = segments.get(segments.size() - 1);
        FileSystemNode existing = parent.getChild(name);
        if (existing != null) {
            if (existing instanceof VirtualDirectory) {
                return;
            }
            throw new IllegalStateException("Ja existe um arquivo com esse nome: " + normalizedPath);
        }
        parent.addChild(new VirtualDirectory(name));
    }

    private void deleteDirectoryInternal(String normalizedPath, boolean recursive) {
        List<String> segments = parseSegments(normalizedPath);
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("Nao e possivel remover a raiz");
        }
        VirtualDirectory target = resolveDirectory(normalizedPath);
        if (!recursive && !target.isEmpty()) {
            throw new IllegalStateException("Diretorio nao esta vazio: " + normalizedPath);
        }
        if (recursive) {
            clearDirectory(target);
        }
        VirtualDirectory parent = target.getParent();
        if (parent == null) {
            throw new IllegalStateException("Diretorio sem pai: " + normalizedPath);
        }
        parent.removeChild(target.getName());
    }

    private void renameDirectoryInternal(String normalizedPath, String newName) {
        if (parseSegments(normalizedPath).isEmpty()) {
            throw new IllegalArgumentException("Nao e possivel renomear a raiz");
        }
        FileSystemNode node = resolveNode(normalizedPath);
        if (!(node instanceof VirtualDirectory directory)) {
            throw new IllegalArgumentException("Caminho nao e diretorio: " + normalizedPath);
        }
        renameNode(directory, newName);
    }

    private void createFileWithContentInternal(String normalizedPath, String base64Content) {
        VirtualDirectory parent = resolveParentDirectory(parseSegments(normalizedPath));
        String name = leafName(normalizedPath);
        byte[] content = Base64.getDecoder().decode(base64Content);
        FileSystemNode existing = parent.getChild(name);
        if (existing instanceof VirtualDirectory) {
            throw new IllegalStateException("Ja existe um diretorio com esse nome: " + normalizedPath);
        }
        if (existing instanceof VirtualFile file) {
            file.writeContent(disk, content);
            return;
        }
        VirtualFile file = new VirtualFile(name);
        file.writeContent(disk, content);
        parent.addChild(file);
    }

    private void createFileInternal(String normalizedPath) {
        List<String> segments = parseSegments(normalizedPath);
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("Caminho invalido para arquivo: " + normalizedPath);
        }
        VirtualDirectory parent = resolveParentDirectory(segments);
        String name = segments.get(segments.size() - 1);
        FileSystemNode existing = parent.getChild(name);
        if (existing instanceof VirtualDirectory) {
            throw new IllegalStateException("Ja existe um diretorio com esse nome: " + normalizedPath);
        }
        if (existing == null) {
            VirtualFile file = new VirtualFile(name);
            parent.addChild(file);
        }
    }

    private void updateFileMetadata(String normalizedPath, int firstCluster, long size) {
        FileSystemNode node = resolveNodeOrNull(normalizedPath);
        if (node instanceof VirtualFile file) {
            file.setFirstCluster(firstCluster);
            file.setSize(size);
        }
    }

    private void copyFileInternal(String sourcePath, String destinationPath) {
        VirtualFile source = requireFile(sourcePath);
        byte[] content = source.readContent(disk);
        VirtualDirectory parent = resolveParentDirectory(parseSegments(destinationPath));
        String name = leafName(destinationPath);
        FileSystemNode existing = parent.getChild(name);
        if (existing instanceof VirtualDirectory) {
            throw new IllegalStateException("Destino aponta para diretorio existente: " + destinationPath);
        }
        if (existing instanceof VirtualFile file) {
            file.writeContent(disk, content);
            return;
        }
        VirtualFile copy = new VirtualFile(name);
        copy.writeContent(disk, content);
        parent.addChild(copy);
    }

    private void deleteFileInternal(String normalizedPath) {
        List<String> segments = parseSegments(normalizedPath);
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("Caminho invalido para arquivo: " + normalizedPath);
        }
        VirtualDirectory parent = resolveParentDirectory(segments);
        String name = segments.get(segments.size() - 1);
        FileSystemNode target = parent.getChild(name);
        if (target == null) {
            return;
        }
        if (!(target instanceof VirtualFile file)) {
            throw new IllegalStateException("Caminho e diretorio, use deleteDirectory: " + normalizedPath);
        }
        disk.freeChain(file.getFirstCluster());
        parent.removeChild(name);
    }

    private void renameFileInternal(String normalizedPath, String newName) {
        FileSystemNode node = resolveNode(normalizedPath);
        if (!(node instanceof VirtualFile file)) {
            throw new IllegalArgumentException("Caminho nao e arquivo: " + normalizedPath);
        }
        renameNode(file, newName);
    }

    private void renameNode(FileSystemNode node, String newName) {
        VirtualDirectory parent = node.getParent();
        if (parent == null) {
            throw new IllegalStateException("Nodo sem pai nao pode ser renomeado");
        }
        if (parent.hasChild(newName)) {
            throw new IllegalStateException("Ja existe um item com esse nome: " + newName);
        }
        parent.removeChild(node.getName());
        node.setName(newName);
        parent.addChild(node);
    }

    private VirtualFile requireFile(String normalizedPath) {
        FileSystemNode node = resolveNode(normalizedPath);
        if (node instanceof VirtualFile file) {
            return file;
        }
        if (node == null) {
            throw new IllegalArgumentException("Arquivo nao encontrado: " + normalizedPath);
        }
        throw new IllegalArgumentException("Caminho nao e arquivo: " + normalizedPath);
    }

    private void recurseDump(StringBuilder builder, VirtualDirectory directory, int depth) {
        if (depth == 0) {
            builder.append("/\n");
        }
        String indent = "  ".repeat(depth + 1);
        for (FileSystemNode node : directory.getChildren()) {
            builder.append(indent).append(node.getName());
            if (node instanceof VirtualDirectory dir) {
                builder.append("/\n");
                recurseDump(builder, dir, depth + 1);
            } else if (node instanceof VirtualFile file) {
                builder.append(" (")
                        .append(file.getSize()).append(" bytes, cluster=")
                        .append(file.getFirstCluster()).append(")\n");
            }
        }
    }

    private void clearDirectory(VirtualDirectory directory) {
        Map<String, FileSystemNode> snapshot = directory.snapshotChildren();
        for (FileSystemNode node : snapshot.values()) {
            if (node instanceof VirtualDirectory dir) {
                clearDirectory(dir);
                directory.removeChild(dir.getName());
            } else if (node instanceof VirtualFile file) {
                disk.freeChain(file.getFirstCluster());
                directory.removeChild(file.getName());
            }
        }
    }

    private VirtualDirectory resolveDirectory(String normalizedPath) {
        FileSystemNode node = resolveNode(normalizedPath);
        if (node == null) {
            throw new IllegalArgumentException("Caminho nao encontrado: " + normalizedPath);
        }
        if (node instanceof VirtualDirectory directory) {
            return directory;
        }
        throw new IllegalArgumentException("Caminho nao e diretorio: " + normalizedPath);
    }

    private FileSystemNode resolveNode(String normalizedPath) {
        FileSystemNode node = resolveNodeOrNull(normalizedPath);
        if (node == null) {
            throw new IllegalArgumentException("Caminho nao encontrado: " + normalizedPath);
        }
        return node;
    }

    private FileSystemNode resolveNodeOrNull(String normalizedPath) {
        List<String> segments = parseSegments(normalizedPath);
        VirtualDirectory current = root;
        if (segments.isEmpty()) {
            return root;
        }
        for (int i = 0; i < segments.size(); i++) {
            String segment = segments.get(i);
            FileSystemNode child = current.getChild(segment);
            if (child == null) {
                return null;
            }
            if (i == segments.size() - 1) {
                return child;
            }
            if (child instanceof VirtualDirectory directory) {
                current = directory;
            } else {
                throw new IllegalArgumentException("Segmento intermediario nao e diretorio: " + segment);
            }
        }
        return current;
    }

    private VirtualDirectory resolveParentDirectory(List<String> segments) {
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("Operacao exige caminho abaixo da raiz");
        }
        if (segments.size() == 1) {
            return root;
        }
        List<String> parentSegments = segments.subList(0, segments.size() - 1);
        String parentPath = "/" + String.join("/", parentSegments);
        return resolveDirectory(parentPath);
    }

    private String leafName(String normalizedPath) {
        List<String> segments = parseSegments(normalizedPath);
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("Operacao nao aceita a raiz como alvo");
        }
        return segments.get(segments.size() - 1);
    }

    private String normalizePath(String rawPath) {
        List<String> segments = parseSegments(rawPath);
        return segments.isEmpty() ? "/" : "/" + String.join("/", segments);
    }

    private List<String> parseSegments(String rawPath) {
        String sanitized = Objects.requireNonNull(rawPath, "Caminho obrigatorio").trim();
        if (sanitized.isEmpty()) {
            throw new IllegalArgumentException("Caminho nao pode ser vazio");
        }
        if (!sanitized.startsWith("/")) {
            sanitized = "/" + sanitized;
        }
        String[] parts = sanitized.split("/");
        Deque<String> stack = new ArrayDeque<>();
        for (String part : parts) {
            if (part.isBlank() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (!stack.isEmpty()) {
                    stack.removeLast();
                }
                continue;
            }
            stack.add(part);
        }
        return new ArrayList<>(stack);
    }

    private String sanitizeName(String newName) {
        if (newName == null || newName.isBlank()) {
            throw new IllegalArgumentException("Nome nao pode ser vazio");
        }
        if (newName.contains("/")) {
            throw new IllegalArgumentException("Nome nao deve conter '/'");
        }
        return newName.trim();
    }

    public static void main(String[] args) {
        Path journalPath = Path.of("./journal.log");
        Path dataPath = Path.of("./filesystem_data.dat");

        FileSystemSimulator simulator = new FileSystemSimulator(journalPath, 2048, 4096, dataPath);

        simulator.createDirectory("/docs");
        simulator.createOrOverwriteFile("/docs/readme.txt",
            "Bem-vindo ao Simulador de Sistema de Arquivos!".getBytes(StandardCharsets.UTF_8));

        System.out.println(simulator.dumpTree());

        simulator.readFile("/docs/readme.txt").ifPresent(content -> {
            System.out.println("\nConteudo de /docs/readme.txt:");
            System.out.println(new String(content, StandardCharsets.UTF_8));
        });
    }
}
