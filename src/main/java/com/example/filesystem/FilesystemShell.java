package com.example.filesystem;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Shell interativo para o simulador de sistema de arquivos.
 * Fornece uma interface de linha de comando similar ao Unix.
 */
public class FilesystemShell implements Runnable {

    private final FileSystemSimulator simulator;
    private String currentDirectory = "/";

    public FilesystemShell(FileSystemSimulator simulator) {
        this.simulator = simulator;
    }

    @Override
    public void run() {
        printWelcome();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while (true) {
                System.out.print(currentDirectory + " $ ");
                line = reader.readLine();
                if (line == null) {
                    break;
                }
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (line.equalsIgnoreCase("exit") || line.equalsIgnoreCase("quit")) {
                    System.out.println("Encerrando simulador...");
                    break;
                }
                handleCommand(line);
            }
        } catch (IOException e) {
            System.out.println("Erro de leitura: " + e.getMessage());
        }
    }

    private void printWelcome() {
        System.out.println("SIMULADOR DE SISTEMA DE ARQUIVOS COM JOURNALING");
        System.out.println("Arquitetura FAT + Write-Ahead Logging");
        System.out.println();
        System.out.println("Arquivo de dados: " + simulator.dataPath().toAbsolutePath());
        System.out.println("Journal (WAL):    " + simulator.journalPath().toAbsolutePath());
        FileSystemSimulator.DiskInfo info = simulator.diskInfo();
        System.out.printf("Disco FAT: %d clusters x %d bytes = %d bytes total%n",
                info.clusterCount(), info.clusterSize(), info.totalBytes());
        System.out.printf("Espaço: usado=%d bytes, livre=%d bytes%n",
                info.usedBytes(), info.freeBytes());
        System.out.println();
        System.out.println("Digite 'help' para listar os comandos disponíveis.");
        System.out.println();
    }

    private void handleCommand(String line) {
        List<String> tokens = tokenize(line);
        if (tokens.isEmpty()) {
            return;
        }
        String command = tokens.get(0).toLowerCase();
        List<String> args = tokens.subList(1, tokens.size());
        try {
            switch (command) {
                case "help" -> printHelp();
                case "pwd" -> System.out.println(currentDirectory);
                case "ls" -> list(args);
                case "mkdir" -> requireArgs(command, args, 1, () -> simulator.createDirectory(resolvePath(args.get(0))));
                case "rmdir" -> requireArgs(command, args, 1, () -> removeDirectory(args));
                case "rename-file" -> requireArgs(command, args, 2, () -> simulator.renameFile(resolvePath(args.get(0)), args.get(1)));
                case "rename-dir" -> requireArgs(command, args, 2, () -> simulator.renameDirectory(resolvePath(args.get(0)), args.get(1)));
                case "rm" -> requireArgs(command, args, 1, () -> simulator.deleteFile(resolvePath(args.get(0))));
                case "cp" -> requireArgs(command, args, 2, () -> simulator.copyFile(resolvePath(args.get(0)), resolvePath(args.get(1))));
                case "touch" -> requireArgs(command, args, 1, () -> touch(args));
                case "cat" -> requireArgs(command, args, 1, () -> cat(args.get(0)));
                case "cd" -> changeDirectory(args);
                case "tree" -> System.out.println(simulator.dumpTree());
                case "journal" -> showJournalInfo();
                case "disk" -> printDiskInfo();
                case "checkpoint" -> doCheckpoint();
                default -> System.out.println("Comando desconhecido: " + command + ". Digite 'help' para ajuda.");
            }
        } catch (Exception ex) {
            System.out.println("Erro: " + ex.getMessage());
        }
    }

    private void changeDirectory(List<String> args) {
        String target = args.isEmpty() ? "/" : resolvePath(args.get(0));
        if (!simulator.isDirectory(target)) {
            System.out.println("Erro: Caminho não é um diretório: " + target);
            return;
        }
        currentDirectory = normalize(target);
    }

    private void list(List<String> args) {
        String target = args.isEmpty() ? currentDirectory : resolvePath(args.get(0));
        List<FileSystemSimulator.ListingEntry> entries = simulator.list(target);
        if (entries.isEmpty()) {
            System.out.println("(diretório vazio)");
            return;
        }
        System.out.printf("%-6s  %-30s  %12s  %s%n", "TIPO", "NOME", "TAMANHO", "ATUALIZADO");
        System.out.println("-".repeat(80));
        entries.forEach(entry -> {
            String type = entry.directory() ? "<DIR>" : "FILE";
            String size = entry.directory() ? "-" : formatSize(entry.size());
            String updated = entry.updatedAt().toString().substring(0, 19);
            System.out.printf("%-6s  %-30s  %12s  %s%n", type, entry.name(), size, updated);
        });
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private void touch(List<String> args) {
        String absolutePath = resolvePath(args.get(0));
        if (args.size() == 1) {
            simulator.createOrOverwriteFile(absolutePath, new byte[0]);
            System.out.println("Arquivo criado: " + absolutePath);
            return;
        }
        String content = String.join(" ", args.subList(1, args.size()));
        simulator.createOrOverwriteFile(absolutePath, content.getBytes(StandardCharsets.UTF_8));
        System.out.println("Arquivo criado com " + content.length() + " bytes: " + absolutePath);
    }

    private void cat(String rawPath) {
        String absolutePath = resolvePath(rawPath);
        simulator.readFile(absolutePath)
                .ifPresentOrElse(
                    bytes -> {
                        System.out.println("--- Conteúdo de " + absolutePath + " ---");
                        System.out.println(new String(bytes, StandardCharsets.UTF_8));
                        System.out.println("--- Fim (" + bytes.length + " bytes) ---");
                    },
                    () -> System.out.println("Arquivo não encontrado ou é um diretório: " + absolutePath));
    }

    private void removeDirectory(List<String> args) {
        boolean recursive = false;
        String path = null;
        for (String arg : args) {
            if ("-r".equals(arg) || "--recursive".equals(arg)) {
                recursive = true;
            } else if (path == null) {
                path = arg;
            }
        }
        if (path == null) {
            System.out.println("Uso: rmdir <caminho> [-r]");
            return;
        }
        simulator.deleteDirectory(resolvePath(path), recursive);
        System.out.println("Diretório removido: " + path);
    }

    private void showJournalInfo() {
        System.out.println("=== Informações do Journal ===");
        System.out.println("Arquivo: " + simulator.journalPath().toAbsolutePath());
        System.out.println("Tipo: Write-Ahead Logging (WAL)");
        System.out.println();
        System.out.println("O journal registra todas as operações ANTES de serem aplicadas.");
        System.out.println("Em caso de falha, apenas transações confirmadas são recuperadas.");
    }

    private void doCheckpoint() {
        simulator.checkpoint();
        System.out.println("Checkpoint criado com sucesso.");
        System.out.println("O estado atual do sistema foi salvo.");
    }

    private void requireArgs(String command, List<String> args, int min, Runnable action) {
        if (args.size() < min) {
            System.out.printf("Uso incorreto de '%s'. Digite 'help' para mais informações.%n", command);
            return;
        }
        action.run();
    }

    private List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\"') {
                quoted = !quoted;
                continue;
            }
            if (Character.isWhitespace(c) && !quoted) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }
        if (quoted) {
            throw new IllegalArgumentException("Aspas não foram fechadas");
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private String resolvePath(String input) {
        if (input == null || input.isBlank()) {
            return currentDirectory;
        }
        if (input.startsWith("/")) {
            return normalize(input);
        }
        if ("/".equals(currentDirectory)) {
            return normalize("/" + input);
        }
        return normalize(currentDirectory + "/" + input);
    }

    private String normalize(String path) {
        String sanitized = path.trim();
        if (!sanitized.startsWith("/")) {
            sanitized = "/" + sanitized;
        }
        String[] parts = sanitized.split("/");
        List<String> stack = new ArrayList<>();
        for (String part : parts) {
            if (part.isBlank() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (!stack.isEmpty()) {
                    stack.remove(stack.size() - 1);
                }
                continue;
            }
            stack.add(part);
        }
        return stack.isEmpty() ? "/" : "/" + String.join("/", stack);
    }

    private void printHelp() {
        System.out.println();
        System.out.println("COMANDOS DISPONIVEIS");
        System.out.println();
        System.out.println("NAVEGACAO:");
        System.out.println("  pwd                        - Mostra diretorio atual");
        System.out.println("  cd <caminho>               - Muda para diretorio");
        System.out.println("  ls [caminho]               - Lista conteudo do diretorio");
        System.out.println("  tree                       - Mostra arvore completa do sistema");
        System.out.println();
        System.out.println("DIRETORIOS:");
        System.out.println("  mkdir <caminho>            - Cria diretorio");
        System.out.println("  rmdir <caminho> [-r]       - Remove diretorio (-r para recursivo)");
        System.out.println("  rename-dir <caminho> <novo> - Renomeia diretorio");
        System.out.println();
        System.out.println("ARQUIVOS:");
        System.out.println("  touch <arquivo> [conteudo] - Cria/atualiza arquivo");
        System.out.println("  cat <arquivo>              - Exibe conteudo do arquivo");
        System.out.println("  cp <origem> <destino>      - Copia arquivo");
        System.out.println("  rm <arquivo>               - Remove arquivo");
        System.out.println("  rename-file <arq> <novo>   - Renomeia arquivo");
        System.out.println();
        System.out.println("SISTEMA:");
        System.out.println("  disk                       - Mostra informacoes do disco FAT");
        System.out.println("  journal                    - Mostra informacoes do journal");
        System.out.println("  checkpoint                 - Cria checkpoint de consistencia");
        System.out.println();
        System.out.println("OUTROS:");
        System.out.println("  help                       - Mostra esta ajuda");
        System.out.println("  exit / quit                - Encerra o simulador");
        System.out.println();
    }

    private void printDiskInfo() {
        FileSystemSimulator.DiskInfo info = simulator.diskInfo();
        System.out.println();
        System.out.println("INFORMACOES DO DISCO FAT");
        System.out.println();
        System.out.printf("Arquivo de dados: %s%n", simulator.dataPath().toAbsolutePath());
        System.out.printf("Numero de clusters: %d%n", info.clusterCount());
        System.out.printf("Tamanho do cluster: %d bytes%n", info.clusterSize());
        System.out.println();
        System.out.printf("Capacidade total:  %s (%d bytes)%n", formatSize(info.totalBytes()), info.totalBytes());
        System.out.printf("Espaco usado:      %s (%d bytes)%n", formatSize(info.usedBytes()), info.usedBytes());
        System.out.printf("Espaco livre:      %s (%d bytes)%n", formatSize(info.freeBytes()), info.freeBytes());
        System.out.println();
        double usagePercent = (double) info.usedBytes() / info.totalBytes() * 100;
        System.out.printf("Uso: %.1f%%%n", usagePercent);
        System.out.println();
    }
}
