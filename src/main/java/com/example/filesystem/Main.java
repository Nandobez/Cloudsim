package com.example.filesystem;

import java.nio.file.Path;

/**
 * Ponto de entrada para o modo shell interativo.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        Path journalPath = args.length > 0 ? Path.of(args[0]) : Path.of("./journal.log");
        FileSystemSimulator simulator = new FileSystemSimulator(journalPath);
        new FilesystemShell(simulator).run();
    }
}
