package com.example.filesystem;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

class Journal {
    private final Path logPath;
    private final AtomicLong transactionCounter = new AtomicLong(0);
    private String currentTransactionId = null;

    Journal(Path logPath) {
        this.logPath = logPath;
        try {
            if (logPath.getParent() != null) {
                Files.createDirectories(logPath.getParent());
            }
            if (!Files.exists(logPath)) {
                Files.createFile(logPath);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Nao foi possivel preparar o journal", e);
        }
    }

    String beginTransaction() {
        if (currentTransactionId != null) {
            throw new IllegalStateException("Transacao ja em andamento: " + currentTransactionId);
        }
        currentTransactionId = UUID.randomUUID().toString().substring(0, 8) + "-" + transactionCounter.incrementAndGet();
        append(new JournalEntry(OperationType.BEGIN_TRANSACTION, List.of(currentTransactionId)));
        return currentTransactionId;
    }

    void commitTransaction() {
        if (currentTransactionId == null) {
            throw new IllegalStateException("Nenhuma transacao em andamento");
        }
        append(new JournalEntry(OperationType.COMMIT_TRANSACTION, List.of(currentTransactionId)));
        currentTransactionId = null;
    }

    void abortTransaction() {
        if (currentTransactionId == null) {
            return;
        }
        append(new JournalEntry(OperationType.ABORT_TRANSACTION, List.of(currentTransactionId)));
        currentTransactionId = null;
    }

    boolean hasActiveTransaction() {
        return currentTransactionId != null;
    }

    String getCurrentTransactionId() {
        return currentTransactionId;
    }

    void append(JournalEntry entry) {
        try {
            String line = entry.serialize() + System.lineSeparator();
            try (RandomAccessFile file = new RandomAccessFile(logPath.toFile(), "rw")) {
                file.seek(file.length());
                file.write(line.getBytes(StandardCharsets.UTF_8));
                file.getFD().sync();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao gravar no journal", e);
        }
    }

    List<JournalEntry> readEntries() {
        List<JournalEntry> entries = new ArrayList<>();
        if (!Files.exists(logPath)) {
            return entries;
        }
        try {
            List<String> lines = Files.readAllLines(logPath);
            for (String line : lines) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                try {
                    entries.add(JournalEntry.deserialize(line));
                } catch (Exception e) {
                    System.err.println("Aviso: entrada corrompida no journal, ignorando resto: " + e.getMessage());
                    break;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao ler o journal", e);
        }
        return entries;
    }

    List<JournalEntry> readCommittedEntries() {
        List<JournalEntry> allEntries = readEntries();

        Set<String> committedTransactions = new HashSet<>();
        Set<String> abortedTransactions = new HashSet<>();

        for (JournalEntry entry : allEntries) {
            if (entry.getType() == OperationType.COMMIT_TRANSACTION) {
                committedTransactions.add(entry.getArguments().get(0));
            } else if (entry.getType() == OperationType.ABORT_TRANSACTION) {
                abortedTransactions.add(entry.getArguments().get(0));
            }
        }

        List<JournalEntry> committed = new ArrayList<>();
        String currentTxId = null;

        for (JournalEntry entry : allEntries) {
            switch (entry.getType()) {
                case BEGIN_TRANSACTION -> currentTxId = entry.getArguments().get(0);
                case COMMIT_TRANSACTION, ABORT_TRANSACTION -> currentTxId = null;
                case CHECKPOINT -> {
                    committed.add(entry);
                }
                default -> {
                    if (currentTxId == null) {
                        committed.add(entry);
                    } else if (committedTransactions.contains(currentTxId)) {
                        committed.add(entry);
                    }
                }
            }
        }

        return committed;
    }

    void checkpoint() {
        String timestamp = Instant.now().toString();
        append(new JournalEntry(OperationType.CHECKPOINT, List.of(timestamp)));
    }

    void truncateToLastCheckpoint() {
        List<JournalEntry> entries = readEntries();

        int lastCheckpointIndex = -1;
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).getType() == OperationType.CHECKPOINT) {
                lastCheckpointIndex = i;
                break;
            }
        }

        if (lastCheckpointIndex <= 0) {
            return;
        }

        List<JournalEntry> newEntries = entries.subList(lastCheckpointIndex, entries.size());

        try {
            StringBuilder sb = new StringBuilder();
            for (JournalEntry entry : newEntries) {
                sb.append(entry.serialize()).append(System.lineSeparator());
            }
            Files.writeString(logPath, sb.toString(),
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao truncar journal", e);
        }
    }

    void clear() {
        try {
            Files.writeString(logPath, "", StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao limpar journal", e);
        }
    }

    Path getLogPath() {
        return logPath;
    }

    int size() {
        return readEntries().size();
    }
}
