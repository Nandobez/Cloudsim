package com.example.filesystem;

import com.example.filesystem.network.*;

import org.jline.reader.EndOfFileException;
import org.jline.reader.History;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Shell interativo para o simulador de sistema de arquivos.
 * Fornece uma interface de linha de comando similar ao Unix.
 * Inclui comandos de cloud e networking.
 * Suporta histórico de comandos com setas do teclado (↑/↓).
 */
public class FilesystemShell implements Runnable {

    private final FileSystemSimulator simulator;
    private final CloudManager cloud;
    private String currentDirectory = "/";

    public FilesystemShell(FileSystemSimulator simulator) {
        this.simulator = simulator;
        this.cloud = new CloudManager(simulator, "sa-east-1");
    }

    @Override
    public void run() {
        printWelcome();

        try {
            // Configurar terminal com suporte a teclas especiais
            Terminal terminal = TerminalBuilder.builder()
                    .system(true)
                    .build();

            // Configurar histórico de comandos (salvo em arquivo)
            Path historyFile = Paths.get(System.getProperty("user.home"), ".fs_simulator_history");

            // Criar LineReader com histórico
            LineReader reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .variable(LineReader.HISTORY_FILE, historyFile)
                    .option(LineReader.Option.HISTORY_IGNORE_DUPS, true)
                    .option(LineReader.Option.HISTORY_IGNORE_SPACE, true)
                    .build();

            String line;
            while (true) {
                try {
                    // Prompt com diretório atual
                    line = reader.readLine(currentDirectory + " $ ");

                    if (line == null) {
                        break;
                    }
                    line = line.trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    if (line.equalsIgnoreCase("exit") || line.equalsIgnoreCase("quit")) {
                        System.out.println("Encerrando simulador...");
                        cloud.shutdown();
                        break;
                    }
                    if (line.equalsIgnoreCase("history")) {
                        showHistory(reader);
                        continue;
                    }
                    handleCommand(line);
                } catch (UserInterruptException e) {
                    // Ctrl+C - apenas nova linha
                    System.out.println();
                } catch (EndOfFileException e) {
                    // Ctrl+D - sair
                    System.out.println("Encerrando simulador...");
                    cloud.shutdown();
                    break;
                }
            }
        } catch (IOException e) {
            System.out.println("Erro ao inicializar terminal: " + e.getMessage());
            // Fallback para modo simples se JLine falhar
            runSimpleMode();
        }
    }

    /**
     * Modo de fallback caso JLine não funcione
     */
    private void runSimpleMode() {
        System.out.println("Executando em modo simples (sem histórico de comandos)");
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(System.in))) {
            String line;
            while (true) {
                System.out.print(currentDirectory + " $ ");
                line = reader.readLine();
                if (line == null) break;
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.equalsIgnoreCase("exit") || line.equalsIgnoreCase("quit")) {
                    System.out.println("Encerrando simulador...");
                    cloud.shutdown();
                    break;
                }
                handleCommand(line);
            }
        } catch (IOException e) {
            System.out.println("Erro de leitura: " + e.getMessage());
        }
    }

    /**
     * Mostra o histórico de comandos
     */
    private void showHistory(LineReader reader) {
        History history = reader.getHistory();
        int index = 1;
        for (History.Entry entry : history) {
            System.out.printf("%5d  %s%n", index++, entry.line());
        }
    }

    /**
     * Limpa a tela do terminal
     */
    private void clearScreen() {
        // ANSI escape code para limpar tela e mover cursor para o topo
        System.out.print("\033[H\033[2J");
        System.out.flush();
    }

    private void printWelcome() {
        System.out.println("╔═══════════════════════════════════════════════════════════════╗");
        System.out.println("║     SIMULADOR DE SISTEMA DE ARQUIVOS COM CLOUD FEATURES       ║");
        System.out.println("║         Arquitetura FAT + Write-Ahead Logging + Cloud         ║");
        System.out.println("╚═══════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Arquivo de dados: " + simulator.dataPath().toAbsolutePath());
        System.out.println("Journal (WAL):    " + simulator.journalPath().toAbsolutePath());
        FileSystemSimulator.DiskInfo info = simulator.diskInfo();
        System.out.printf("Disco FAT: %d clusters x %d bytes = %d bytes total%n",
                info.clusterCount(), info.clusterSize(), info.totalBytes());
        System.out.println();
        System.out.println("Digite 'help' para listar os comandos disponíveis.");
        System.out.println("Digite 'cloud' para ver comandos de cloud/rede.");
        System.out.println();
        System.out.println("Dica: Use ↑/↓ para navegar no histórico de comandos.");
        System.out.println("      Digite 'history' para ver o histórico completo.");
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
                // Filesystem commands
                case "help" -> printHelp();
                case "clear", "cls" -> clearScreen();
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

                // Cloud commands
                case "cloud" -> printCloudHelp();
                case "cloud-status" -> System.out.println(cloud.getStatus());

                // Server commands
                case "server" -> handleServer(args);

                // VPC/Network commands
                case "vpc" -> handleVpc(args);
                case "subnet" -> handleSubnet(args);
                case "instance" -> handleInstance(args);

                // DNS commands
                case "dns" -> handleDns(args);

                // Service Discovery
                case "service" -> handleService(args);

                // Quota commands
                case "quota" -> handleQuota(args);

                // Container commands
                case "docker" -> handleDocker(args);

                // S3/Cloud Storage commands
                case "s3" -> handleS3(args);

                // HTTP client (curl)
                case "curl" -> handleCurl(args);

                // Load Balancer
                case "lb" -> handleLoadBalancer(args);

                // Port forwarding
                case "port" -> handlePort(args);

                // DFS commands
                case "dfs" -> handleDfs(args);

                // NFS commands
                case "nfs" -> handleNfs(args);

                // CDN commands
                case "cdn" -> handleCdn(args);

                default -> System.out.println("Comando desconhecido: " + command + ". Digite 'help' para ajuda.");
            }
        } catch (Exception ex) {
            System.out.println("Erro: " + ex.getMessage());
        }
    }

    // ==================== Server Commands ====================

    private void handleServer(List<String> args) throws IOException {
        if (args.isEmpty()) {
            System.out.println("Uso: server <start|stop|status> <http|tcp|nfs|gateway|proxy> [port]");
            return;
        }

        String action = args.get(0).toLowerCase();
        String type = args.size() > 1 ? args.get(1).toLowerCase() : "";
        int port = args.size() > 2 ? Integer.parseInt(args.get(2)) : 0;

        switch (action) {
            case "start" -> {
                switch (type) {
                    case "http" -> cloud.startHttpServer(port > 0 ? port : 8080);
                    case "tcp" -> cloud.startTcpServer(port > 0 ? port : 9000);
                    case "nfs" -> cloud.startNfsServer(port > 0 ? port : 2049);
                    case "gateway" -> cloud.startApiGateway(port > 0 ? port : 8000);
                    case "proxy" -> cloud.startReverseProxy(port > 0 ? port : 80);
                    case "nat" -> cloud.startNatGateway();
                    default -> System.out.println("Tipo de servidor desconhecido: " + type);
                }
            }
            case "stop" -> {
                switch (type) {
                    case "http" -> cloud.stopHttpServer();
                    case "tcp" -> cloud.stopTcpServer();
                    case "nfs" -> cloud.stopNfsServer();
                    case "gateway" -> cloud.stopApiGateway();
                    case "proxy" -> cloud.stopReverseProxy();
                    case "nat" -> cloud.stopNatGateway();
                    case "all" -> {
                        cloud.stopHttpServer();
                        cloud.stopTcpServer();
                        cloud.stopNfsServer();
                        cloud.stopApiGateway();
                        cloud.stopReverseProxy();
                        cloud.stopNatGateway();
                        System.out.println("Todos os servidores parados");
                    }
                    default -> System.out.println("Tipo de servidor desconhecido: " + type);
                }
            }
            case "status" -> System.out.println(cloud.getStatus());
            default -> System.out.println("Ação desconhecida: " + action);
        }
    }

    // ==================== VPC Commands ====================

    private void handleVpc(List<String> args) {
        if (args.isEmpty()) {
            System.out.println("Uso: vpc <create|delete|list|info> [name] [cidr]");
            return;
        }

        String action = args.get(0).toLowerCase();
        NetworkManager nm = cloud.getNetworkManager();

        switch (action) {
            case "create" -> {
                if (args.size() < 3) {
                    System.out.println("Uso: vpc create <name> <cidr>");
                    return;
                }
                VPC vpc = nm.createVPC(args.get(1), args.get(2));
                System.out.println("VPC criada: " + vpc);
            }
            case "delete" -> {
                if (args.size() < 2) {
                    System.out.println("Uso: vpc delete <name>");
                    return;
                }
                nm.deleteVPC(args.get(1));
                System.out.println("VPC deletada: " + args.get(1));
            }
            case "list" -> {
                System.out.println("=== VPCs ===");
                for (VPC vpc : nm.getVPCs()) {
                    System.out.println("  " + vpc);
                }
            }
            case "info" -> {
                if (args.size() < 2) {
                    System.out.println("Uso: vpc info <name>");
                    return;
                }
                VPC vpc = nm.getVPC(args.get(1));
                if (vpc == null) {
                    System.out.println("VPC não encontrada: " + args.get(1));
                    return;
                }
                System.out.println("VPC: " + vpc.getName());
                System.out.println("CIDR: " + vpc.getCidr());
                System.out.println("Region: " + vpc.getRegion());
                System.out.println("Subnets:");
                for (Subnet s : vpc.getSubnets()) {
                    System.out.println("  " + s);
                }
            }
            default -> System.out.println("Ação desconhecida: " + action);
        }
    }

    // ==================== Subnet Commands ====================

    private void handleSubnet(List<String> args) {
        if (args.isEmpty()) {
            System.out.println("Uso: subnet <create|delete|list> [vpc] [name] [cidr] [--public]");
            return;
        }

        String action = args.get(0).toLowerCase();
        NetworkManager nm = cloud.getNetworkManager();

        switch (action) {
            case "create" -> {
                if (args.size() < 4) {
                    System.out.println("Uso: subnet create <vpc> <name> <cidr> [--public]");
                    return;
                }
                boolean isPublic = args.contains("--public");
                Subnet subnet = nm.createSubnet(args.get(1), args.get(2), args.get(3), isPublic);
                System.out.println("Subnet criada: " + subnet);
            }
            case "list" -> {
                if (args.size() < 2) {
                    System.out.println("Uso: subnet list <vpc>");
                    return;
                }
                VPC vpc = nm.getVPC(args.get(1));
                if (vpc == null) {
                    System.out.println("VPC não encontrada: " + args.get(1));
                    return;
                }
                System.out.println("=== Subnets de " + vpc.getName() + " ===");
                for (Subnet s : vpc.getSubnets()) {
                    System.out.println("  " + s);
                }
            }
            default -> System.out.println("Ação desconhecida: " + action);
        }
    }

    // ==================== Instance Commands ====================

    private void handleInstance(List<String> args) {
        if (args.isEmpty()) {
            System.out.println("Uso: instance <create|terminate|list|info|start|stop> [name] [vpc] [subnet]");
            return;
        }

        String action = args.get(0).toLowerCase();
        NetworkManager nm = cloud.getNetworkManager();

        switch (action) {
            case "create" -> {
                if (args.size() < 2) {
                    System.out.println("Uso: instance create <name> [vpc] [subnet]");
                    return;
                }
                String name = args.get(1);
                String vpc = args.size() > 2 ? args.get(2) : "default";
                String subnet = args.size() > 3 ? args.get(3) : null;
                VirtualInstance inst = nm.createInstance(name, vpc, subnet);
                System.out.println("Instância criada: " + inst);
            }
            case "terminate" -> {
                if (args.size() < 2) {
                    System.out.println("Uso: instance terminate <name>");
                    return;
                }
                nm.terminateInstance(args.get(1));
                System.out.println("Instância terminada: " + args.get(1));
            }
            case "list" -> {
                System.out.println("=== Instâncias ===");
                for (VirtualInstance inst : nm.getInstances()) {
                    System.out.println("  " + inst);
                }
            }
            case "info" -> {
                if (args.size() < 2) {
                    System.out.println("Uso: instance info <name>");
                    return;
                }
                VirtualInstance inst = nm.getInstance(args.get(1));
                if (inst == null) {
                    System.out.println("Instância não encontrada: " + args.get(1));
                    return;
                }
                System.out.println(inst.getDetailedInfo());
            }
            case "start" -> {
                if (args.size() < 2) return;
                VirtualInstance inst = nm.getInstance(args.get(1));
                if (inst != null) {
                    inst.start();
                    System.out.println("Instância iniciada: " + args.get(1));
                }
            }
            case "stop" -> {
                if (args.size() < 2) return;
                VirtualInstance inst = nm.getInstance(args.get(1));
                if (inst != null) {
                    inst.stop();
                    System.out.println("Instância parada: " + args.get(1));
                }
            }
            default -> System.out.println("Ação desconhecida: " + action);
        }
    }

    // ==================== DNS Commands ====================

    private void handleDns(List<String> args) {
        if (args.isEmpty()) {
            System.out.println("Uso: dns <add|remove|resolve|list> [name] [ip]");
            return;
        }

        String action = args.get(0).toLowerCase();
        DNSServer dns = cloud.getDns();

        switch (action) {
            case "add" -> {
                if (args.size() < 3) {
                    System.out.println("Uso: dns add <name> <ip>");
                    return;
                }
                dns.addARecord(args.get(1), args.get(2));
                System.out.println("Registro DNS adicionado: " + args.get(1) + " -> " + args.get(2));
            }
            case "remove" -> {
                if (args.size() < 2) {
                    System.out.println("Uso: dns remove <name>");
                    return;
                }
                dns.removeAllRecords(args.get(1));
                System.out.println("Registro DNS removido: " + args.get(1));
            }
            case "resolve" -> {
                if (args.size() < 2) {
                    System.out.println("Uso: dns resolve <name>");
                    return;
                }
                String ip = dns.resolve(args.get(1));
                if (ip != null) {
                    System.out.println(args.get(1) + " -> " + ip);
                } else {
                    System.out.println("Não encontrado: " + args.get(1));
                }
            }
            case "list" -> {
                System.out.println("=== Registros DNS ===");
                for (DNSServer.DNSRecord record : dns.getAllRecords()) {
                    System.out.println("  " + record);
                }
            }
            default -> System.out.println("Ação desconhecida: " + action);
        }
    }

    // ==================== Service Discovery Commands ====================

    private void handleService(List<String> args) {
        if (args.isEmpty()) {
            System.out.println("Uso: service <register|deregister|discover|list|status> [name] [host] [port]");
            return;
        }

        String action = args.get(0).toLowerCase();
        ServiceDiscovery sd = cloud.getServiceDiscovery();

        switch (action) {
            case "register" -> {
                if (args.size() < 4) {
                    System.out.println("Uso: service register <name> <host> <port> [tags...]");
                    return;
                }
                String[] tags = args.size() > 4 ?
                    args.subList(4, args.size()).toArray(new String[0]) : new String[0];
                ServiceDiscovery.ServiceInstance inst = sd.register(
                    args.get(1), args.get(2), Integer.parseInt(args.get(3)), tags);
                System.out.println("Serviço registrado: " + inst);
            }
            case "deregister" -> {
                if (args.size() < 2) {
                    System.out.println("Uso: service deregister <service-name>");
                    return;
                }
                sd.deregisterService(args.get(1));
                System.out.println("Serviço desregistrado: " + args.get(1));
            }
            case "discover" -> {
                if (args.size() < 2) {
                    System.out.println("Uso: service discover <name>");
                    return;
                }
                var instances = sd.discover(args.get(1));
                if (instances.isEmpty()) {
                    System.out.println("Nenhuma instância encontrada para: " + args.get(1));
                } else {
                    System.out.println("Instâncias de " + args.get(1) + ":");
                    for (var inst : instances) {
                        System.out.println("  " + inst);
                    }
                }
            }
            case "list" -> {
                System.out.println("=== Serviços Registrados ===");
                for (String name : sd.getServiceNames()) {
                    System.out.println("  " + name + ": " + sd.discoverAll(name).size() + " instância(s)");
                }
            }
            case "status" -> System.out.println(sd.getStatus());
            default -> System.out.println("Ação desconhecida: " + action);
        }
    }

    // ==================== Quota Commands ====================

    private void handleQuota(List<String> args) {
        if (args.isEmpty()) {
            System.out.println("Uso: quota <set|show|list|delete> [user] [size] [max-files]");
            return;
        }

        String action = args.get(0).toLowerCase();
        QuotaManager qm = cloud.getQuotaManager();

        switch (action) {
            case "set" -> {
                if (args.size() < 3) {
                    System.out.println("Uso: quota set <user> <max-size> [max-files]");
                    return;
                }
                long maxFiles = args.size() > 3 ? Long.parseLong(args.get(3)) : 1000;
                QuotaManager.Quota quota = qm.createQuota(args.get(1), args.get(2), maxFiles);
                System.out.println("Cota definida: " + quota);
            }
            case "show" -> {
                if (args.size() < 2) {
                    System.out.println("Uso: quota show <user>");
                    return;
                }
                QuotaManager.Quota quota = qm.getQuota(args.get(1));
                System.out.println(quota.getDetailedInfo());
            }
            case "list" -> System.out.println(qm.getStatus());
            case "delete" -> {
                if (args.size() < 2) {
                    System.out.println("Uso: quota delete <user>");
                    return;
                }
                qm.deleteQuota(args.get(1));
                System.out.println("Cota removida: " + args.get(1));
            }
            default -> System.out.println("Ação desconhecida: " + action);
        }
    }

    // ==================== Docker/Container Commands ====================

    private void handleDocker(List<String> args) {
        if (args.isEmpty()) {
            System.out.println("Uso: docker <run|stop|rm|ps|network|inspect> [...]");
            return;
        }

        String action = args.get(0).toLowerCase();
        ContainerNetwork cn = cloud.getContainerNetwork();

        switch (action) {
            case "run" -> {
                if (args.size() < 3) {
                    System.out.println("Uso: docker run <name> <image>");
                    return;
                }
                ContainerNetwork.Container c = cn.createContainer(args.get(1), args.get(2));
                cn.startContainer(args.get(1));
                System.out.println("Container criado e iniciado: " + c.getId());
            }
            case "stop" -> {
                if (args.size() < 2) return;
                cn.stopContainer(args.get(1));
                System.out.println("Container parado: " + args.get(1));
            }
            case "start" -> {
                if (args.size() < 2) return;
                cn.startContainer(args.get(1));
                System.out.println("Container iniciado: " + args.get(1));
            }
            case "rm" -> {
                if (args.size() < 2) return;
                cn.removeContainer(args.get(1));
                System.out.println("Container removido: " + args.get(1));
            }
            case "ps" -> {
                System.out.println("=== Containers ===");
                System.out.printf("%-12s %-15s %-20s %-10s %s%n", "ID", "NAME", "IMAGE", "STATUS", "IP");
                for (ContainerNetwork.Container c : cn.getContainers()) {
                    String ip = c.getIP("bridge") != null ? c.getIP("bridge").toString() : "-";
                    System.out.printf("%-12s %-15s %-20s %-10s %s%n",
                        c.getId(), c.getName(), c.getImage(), c.getState(), ip);
                }
            }
            case "network" -> handleDockerNetwork(args.subList(1, args.size()));
            case "inspect" -> {
                if (args.size() < 2) return;
                ContainerNetwork.Container c = cn.getContainer(args.get(1));
                if (c == null) c = cn.getContainerById(args.get(1));
                if (c != null) {
                    System.out.println(c.getDetailedInfo());
                } else {
                    System.out.println("Container não encontrado: " + args.get(1));
                }
            }
            default -> System.out.println("Ação desconhecida: " + action);
        }
    }

    private void handleDockerNetwork(List<String> args) {
        if (args.isEmpty()) {
            System.out.println("Uso: docker network <create|ls|connect|disconnect> [...]");
            return;
        }

        String action = args.get(0).toLowerCase();
        ContainerNetwork cn = cloud.getContainerNetwork();

        switch (action) {
            case "create" -> {
                if (args.size() < 3) {
                    System.out.println("Uso: docker network create <name> <cidr>");
                    return;
                }
                cn.createNetwork(args.get(1), args.get(2));
                System.out.println("Rede criada: " + args.get(1));
            }
            case "ls" -> {
                System.out.println("=== Redes ===");
                for (ContainerNetwork.Network n : cn.getNetworks()) {
                    System.out.println("  " + n);
                }
            }
            case "connect" -> {
                if (args.size() < 3) {
                    System.out.println("Uso: docker network connect <network> <container>");
                    return;
                }
                cn.connectToNetwork(args.get(2), args.get(1));
                System.out.println("Container conectado à rede");
            }
            case "disconnect" -> {
                if (args.size() < 3) {
                    System.out.println("Uso: docker network disconnect <network> <container>");
                    return;
                }
                cn.disconnectFromNetwork(args.get(2), args.get(1));
                System.out.println("Container desconectado da rede");
            }
            default -> System.out.println("Ação desconhecida: " + action);
        }
    }

    // ==================== S3/Cloud Storage Commands ====================

    private void handleS3(List<String> args) {
        if (args.isEmpty()) {
            System.out.println("Uso: s3 <mb|rb|ls|cp|rm|presign> [bucket] [key] [...]");
            return;
        }

        String action = args.get(0).toLowerCase();
        CloudStorage cs = cloud.getCloudStorage();

        switch (action) {
            case "mb" -> { // make bucket
                if (args.size() < 2) {
                    System.out.println("Uso: s3 mb <bucket-name>");
                    return;
                }
                cs.createBucket(args.get(1));
                System.out.println("Bucket criado: " + args.get(1));
            }
            case "rb" -> { // remove bucket
                if (args.size() < 2) {
                    System.out.println("Uso: s3 rb <bucket-name>");
                    return;
                }
                cs.deleteBucket(args.get(1));
                System.out.println("Bucket removido: " + args.get(1));
            }
            case "ls" -> {
                if (args.size() < 2) {
                    System.out.println("=== Buckets ===");
                    for (CloudStorage.Bucket b : cs.listBuckets()) {
                        System.out.println("  " + b);
                    }
                } else {
                    CloudStorage.Bucket bucket = cs.getBucket(args.get(1));
                    if (bucket == null) {
                        System.out.println("Bucket não encontrado: " + args.get(1));
                        return;
                    }
                    String prefix = args.size() > 2 ? args.get(2) : "";
                    System.out.println("=== Objetos em " + args.get(1) + " ===");
                    for (CloudStorage.ObjectMetadata obj : bucket.listObjects(prefix)) {
                        System.out.printf("  %s  %d bytes  %s%n",
                            obj.getKey(), obj.getSize(), obj.getContentType());
                    }
                }
            }
            case "cp" -> {
                if (args.size() < 4) {
                    System.out.println("Uso: s3 cp <bucket> <key> <content>");
                    return;
                }
                String content = String.join(" ", args.subList(3, args.size()));
                cs.putObject(args.get(1), args.get(2), content, "text/plain");
                System.out.println("Objeto criado: s3://" + args.get(1) + "/" + args.get(2));
            }
            case "cat" -> {
                if (args.size() < 3) {
                    System.out.println("Uso: s3 cat <bucket> <key>");
                    return;
                }
                String content = cs.getObject(args.get(1), args.get(2));
                System.out.println(content);
            }
            case "rm" -> {
                if (args.size() < 3) {
                    System.out.println("Uso: s3 rm <bucket> <key>");
                    return;
                }
                cs.deleteObject(args.get(1), args.get(2));
                System.out.println("Objeto removido: s3://" + args.get(1) + "/" + args.get(2));
            }
            case "presign" -> {
                if (args.size() < 4) {
                    System.out.println("Uso: s3 presign <get|put> <bucket> <key> [expires-seconds]");
                    return;
                }
                String method = args.get(1).toUpperCase();
                String bucket = args.get(2);
                String key = args.get(3);
                int expires = args.size() > 4 ? Integer.parseInt(args.get(4)) : 3600;

                CloudStorage.PresignedUrl url = method.equals("PUT") ?
                    cs.generatePresignedPutUrl(bucket, key, Duration.ofSeconds(expires)) :
                    cs.generatePresignedGetUrl(bucket, key, Duration.ofSeconds(expires));

                System.out.println("Presigned URL (" + method + ", expira em " + expires + "s):");
                System.out.println(url.getUrl());
            }
            case "status" -> System.out.println(cs.getStatus());
            default -> System.out.println("Ação desconhecida: " + action);
        }
    }

    // ==================== Curl Commands ====================

    private void handleCurl(List<String> args) {
        if (args.isEmpty()) {
            System.out.println("Uso: curl [options] <url>");
            System.out.println("Opções: -X METHOD, -d DATA, -H HEADER, -v verbose");
            return;
        }

        String method = "GET";
        String url = null;
        String data = null;
        List<String[]> headers = new ArrayList<>();
        boolean verbose = false;

        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            switch (arg) {
                case "-X" -> { if (i + 1 < args.size()) method = args.get(++i).toUpperCase(); }
                case "-d", "--data" -> { if (i + 1 < args.size()) data = args.get(++i); }
                case "-H", "--header" -> {
                    if (i + 1 < args.size()) {
                        String h = args.get(++i);
                        String[] parts = h.split(":", 2);
                        if (parts.length == 2) {
                            headers.add(new String[]{parts[0].trim(), parts[1].trim()});
                        }
                    }
                }
                case "-v", "--verbose" -> verbose = true;
                default -> { if (!arg.startsWith("-")) url = arg; }
            }
        }

        if (url == null) {
            System.out.println("URL é obrigatória");
            return;
        }

        try {
            HttpClient.RequestBuilder request = HttpClient.request(url)
                .method(method)
                .withDNS(cloud.getDns());

            for (String[] header : headers) {
                request.header(header[0], header[1]);
            }

            if (data != null) {
                request.body(data);
            }

            HttpClient.HttpResponse response = request.execute();
            System.out.println(response.format(verbose));

        } catch (IOException e) {
            System.out.println("Erro: " + e.getMessage());
        }
    }

    // ==================== Load Balancer Commands ====================

    private void handleLoadBalancer(List<String> args) {
        if (args.isEmpty()) {
            System.out.println("Uso: lb <start|stop|add|remove|status> [...]");
            return;
        }

        String action = args.get(0).toLowerCase();

        switch (action) {
            case "start" -> {
                if (args.size() < 3) {
                    System.out.println("Uso: lb start <name> <port> [algorithm]");
                    return;
                }
                String alg = args.size() > 3 ? args.get(3).toUpperCase() : "ROUND_ROBIN";
                try {
                    cloud.startLoadBalancer(args.get(1), Integer.parseInt(args.get(2)),
                        LoadBalancer.Algorithm.valueOf(alg));
                } catch (IOException e) {
                    System.out.println("Erro ao iniciar LB: " + e.getMessage());
                }
            }
            case "stop" -> cloud.stopLoadBalancer();
            case "add" -> {
                if (args.size() < 4) {
                    System.out.println("Uso: lb add <backend-name> <host> <port>");
                    return;
                }
                LoadBalancer lb = cloud.getLoadBalancer();
                if (lb == null) {
                    System.out.println("Load Balancer não está rodando");
                    return;
                }
                lb.addBackend(args.get(1), args.get(2), Integer.parseInt(args.get(3)));
                System.out.println("Backend adicionado: " + args.get(1));
            }
            case "remove" -> {
                if (args.size() < 2) return;
                LoadBalancer lb = cloud.getLoadBalancer();
                if (lb != null) {
                    lb.removeBackend(args.get(1));
                    System.out.println("Backend removido: " + args.get(1));
                }
            }
            case "status" -> {
                LoadBalancer lb = cloud.getLoadBalancer();
                if (lb != null) {
                    System.out.println(lb.getStatus());
                } else {
                    System.out.println("Load Balancer não está rodando");
                }
            }
            default -> System.out.println("Ação desconhecida: " + action);
        }
    }

    // ==================== Port Forwarding Commands ====================

    private void handlePort(List<String> args) {
        if (args.isEmpty()) {
            System.out.println("Uso: port <forward|remove|list> [external-port] [instance] [internal-port]");
            return;
        }

        String action = args.get(0).toLowerCase();
        NetworkManager nm = cloud.getNetworkManager();

        switch (action) {
            case "forward" -> {
                if (args.size() < 4) {
                    System.out.println("Uso: port forward <external-port> <instance> <internal-port>");
                    return;
                }
                nm.addPortMapping(Integer.parseInt(args.get(1)), args.get(2), Integer.parseInt(args.get(3)));
                System.out.println("Port forwarding criado: " + args.get(1) + " -> " + args.get(2) + ":" + args.get(3));
            }
            case "remove" -> {
                if (args.size() < 2) return;
                nm.removePortMapping(Integer.parseInt(args.get(1)));
                System.out.println("Port forwarding removido: " + args.get(1));
            }
            case "list" -> {
                System.out.println("=== Port Mappings ===");
                for (NetworkManager.PortMapping pm : nm.getPortMappings()) {
                    System.out.println("  " + pm);
                }
            }
            default -> System.out.println("Ação desconhecida: " + action);
        }
    }

    // ==================== DFS Commands ====================

    private void handleDfs(List<String> args) {
        if (args.isEmpty()) {
            System.out.println("Uso: dfs <put|get|ls|rm|status|nodes> [...]");
            return;
        }

        String action = args.get(0).toLowerCase();
        DistributedFileSystem dfs = cloud.getDfs();

        switch (action) {
            case "put" -> {
                if (args.size() < 3) {
                    System.out.println("Uso: dfs put <path> <content>");
                    return;
                }
                String content = String.join(" ", args.subList(2, args.size()));
                dfs.writeFile(args.get(1), content.getBytes(StandardCharsets.UTF_8), "user");
                System.out.println("Arquivo escrito no DFS: " + args.get(1));
            }
            case "get" -> {
                if (args.size() < 2) {
                    System.out.println("Uso: dfs get <path>");
                    return;
                }
                byte[] data = dfs.readFile(args.get(1));
                System.out.println(new String(data, StandardCharsets.UTF_8));
            }
            case "ls" -> {
                String prefix = args.size() > 1 ? args.get(1) : "";
                System.out.println("=== Arquivos DFS ===");
                for (DistributedFileSystem.DFSFile file : dfs.listFiles(prefix)) {
                    System.out.printf("  %s  %d bytes  %d blocks  replication=%d%n",
                        file.getPath(), file.getSize(), file.getBlocks().size(), file.getReplication());
                }
            }
            case "rm" -> {
                if (args.size() < 2) return;
                dfs.deleteFile(args.get(1));
                System.out.println("Arquivo removido do DFS: " + args.get(1));
            }
            case "status" -> System.out.println(dfs.getStatus());
            case "nodes" -> {
                System.out.println("=== DataNodes ===");
                for (DistributedFileSystem.DataNode node : dfs.getDataNodes()) {
                    System.out.println("  " + node);
                }
            }
            default -> System.out.println("Ação desconhecida: " + action);
        }
    }

    // ==================== NFS Commands ====================

    private void handleNfs(List<String> args) {
        if (args.isEmpty()) {
            System.out.println("Uso: nfs <export|unexport|exports|mounts|status> [...]");
            return;
        }

        String action = args.get(0).toLowerCase();
        NFSServer nfs = cloud.getNfsServer();

        if (nfs == null && !action.equals("status")) {
            System.out.println("NFS Server não está rodando. Use: server start nfs");
            return;
        }

        switch (action) {
            case "export" -> {
                if (args.size() < 3) {
                    System.out.println("Uso: nfs export <export-path> <fs-path> [--ro]");
                    return;
                }
                boolean readOnly = args.contains("--ro");
                nfs.addExport(args.get(1), args.get(2), readOnly);
                System.out.println("Export adicionado: " + args.get(1) + " -> " + args.get(2));
            }
            case "unexport" -> {
                if (args.size() < 2) return;
                nfs.removeExport(args.get(1));
                System.out.println("Export removido: " + args.get(1));
            }
            case "exports" -> {
                System.out.println("=== NFS Exports ===");
                for (NFSServer.Export export : nfs.getExports()) {
                    System.out.println("  " + export);
                }
            }
            case "mounts" -> {
                System.out.println("=== Active Mounts ===");
                for (NFSServer.Mount mount : nfs.getActiveMounts()) {
                    System.out.println("  " + mount);
                }
            }
            case "status" -> {
                if (nfs != null) {
                    System.out.println(nfs.getStatus());
                } else {
                    System.out.println("NFS Server não está rodando");
                }
            }
            default -> System.out.println("Ação desconhecida: " + action);
        }
    }

    // ==================== CDN Commands ====================

    private void handleCdn(List<String> args) {
        if (args.isEmpty()) {
            System.out.println("Uso: cdn <origin|edge|cache|invalidate|status> [...]");
            return;
        }

        String action = args.get(0).toLowerCase();
        CDN cdn = cloud.getCdn();

        switch (action) {
            case "origin" -> {
                if (args.size() < 5) {
                    System.out.println("Uso: cdn origin <name> <host> <port> <basePath>");
                    return;
                }
                cdn.addOrigin(args.get(1), args.get(2), Integer.parseInt(args.get(3)), args.get(4));
                System.out.println("Origin adicionada: " + args.get(1));
            }
            case "edge" -> {
                if (args.size() < 3) {
                    System.out.println("Uso: cdn edge <region> <location> [cache-size]");
                    return;
                }
                int cacheSize = args.size() > 3 ? Integer.parseInt(args.get(3)) : 100;
                CDN.EdgeNode node = cdn.addEdgeNode(args.get(1), args.get(2), cacheSize);
                System.out.println("Edge node adicionado: " + node);
            }
            case "invalidate" -> {
                if (args.size() < 2) {
                    System.out.println("Uso: cdn invalidate <key-or-pattern>");
                    return;
                }
                if (args.get(1).equals("*")) {
                    cdn.invalidateAll();
                    System.out.println("Cache invalidado completamente");
                } else {
                    cdn.invalidate(args.get(1));
                    System.out.println("Cache invalidado para: " + args.get(1));
                }
            }
            case "status" -> System.out.println(cdn.getStatus());
            default -> System.out.println("Ação desconhecida: " + action);
        }
    }

    // ==================== Original Methods ====================

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
        System.out.println("COMANDOS DO SISTEMA DE ARQUIVOS");
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println();
        System.out.println("NAVEGAÇÃO:");
        System.out.println("  pwd                        - Mostra diretório atual");
        System.out.println("  cd <caminho>               - Muda para diretório");
        System.out.println("  ls [caminho]               - Lista conteúdo do diretório");
        System.out.println("  tree                       - Mostra árvore completa");
        System.out.println();
        System.out.println("DIRETÓRIOS:");
        System.out.println("  mkdir <caminho>            - Cria diretório");
        System.out.println("  rmdir <caminho> [-r]       - Remove diretório");
        System.out.println("  rename-dir <path> <new>    - Renomeia diretório");
        System.out.println();
        System.out.println("ARQUIVOS:");
        System.out.println("  touch <arquivo> [conteudo] - Cria/atualiza arquivo");
        System.out.println("  cat <arquivo>              - Exibe conteúdo");
        System.out.println("  cp <origem> <destino>      - Copia arquivo");
        System.out.println("  rm <arquivo>               - Remove arquivo");
        System.out.println("  rename-file <arq> <novo>   - Renomeia arquivo");
        System.out.println();
        System.out.println("SISTEMA:");
        System.out.println("  disk                       - Info do disco FAT");
        System.out.println("  journal                    - Info do journal");
        System.out.println("  checkpoint                 - Cria checkpoint");
        System.out.println("  clear / cls                - Limpa a tela");
        System.out.println("  history                    - Mostra histórico de comandos");
        System.out.println();
        System.out.println("Digite 'cloud' para ver comandos de cloud/rede.");
        System.out.println();
    }

    private void printCloudHelp() {
        System.out.println();
        System.out.println("COMANDOS DE CLOUD E REDE");
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println();
        System.out.println("STATUS GERAL:");
        System.out.println("  cloud-status               - Status de todos os serviços cloud");
        System.out.println();
        System.out.println("SERVIDORES:");
        System.out.println("  server start <tipo> [port] - Inicia servidor (http|tcp|nfs|gateway|proxy|nat)");
        System.out.println("  server stop <tipo>         - Para servidor");
        System.out.println("  server status              - Status dos servidores");
        System.out.println();
        System.out.println("VPC/REDE:");
        System.out.println("  vpc create <name> <cidr>   - Cria VPC");
        System.out.println("  vpc list                   - Lista VPCs");
        System.out.println("  subnet create <vpc> <name> <cidr> [--public]");
        System.out.println("  instance create <name> [vpc] [subnet]");
        System.out.println("  instance list              - Lista instâncias");
        System.out.println();
        System.out.println("DNS:");
        System.out.println("  dns add <name> <ip>        - Adiciona registro DNS");
        System.out.println("  dns resolve <name>         - Resolve nome");
        System.out.println("  dns list                   - Lista registros");
        System.out.println();
        System.out.println("SERVICE DISCOVERY:");
        System.out.println("  service register <name> <host> <port> [tags...]");
        System.out.println("  service discover <name>    - Descobre instâncias");
        System.out.println("  service list               - Lista serviços");
        System.out.println();
        System.out.println("QUOTAS:");
        System.out.println("  quota set <user> <size> [max-files]");
        System.out.println("  quota show <user>          - Mostra quota");
        System.out.println("  quota list                 - Lista quotas");
        System.out.println();
        System.out.println("CONTAINERS (Docker-like):");
        System.out.println("  docker run <name> <image>  - Cria e inicia container");
        System.out.println("  docker ps                  - Lista containers");
        System.out.println("  docker stop <name>         - Para container");
        System.out.println("  docker network ls          - Lista redes");
        System.out.println("  docker network create <name> <cidr>");
        System.out.println();
        System.out.println("CLOUD STORAGE (S3-like):");
        System.out.println("  s3 mb <bucket>             - Cria bucket");
        System.out.println("  s3 ls [bucket]             - Lista buckets/objetos");
        System.out.println("  s3 cp <bucket> <key> <content>");
        System.out.println("  s3 cat <bucket> <key>      - Lê objeto");
        System.out.println("  s3 presign <get|put> <bucket> <key> [expires]");
        System.out.println();
        System.out.println("HTTP CLIENT:");
        System.out.println("  curl <url>                 - GET request");
        System.out.println("  curl -X POST <url> -d <data>");
        System.out.println("  curl -v <url>              - Verbose output");
        System.out.println();
        System.out.println("LOAD BALANCER:");
        System.out.println("  lb start <name> <port> [algorithm]");
        System.out.println("  lb add <backend> <host> <port>");
        System.out.println("  lb status                  - Status do LB");
        System.out.println();
        System.out.println("PORT FORWARDING:");
        System.out.println("  port forward <ext> <instance> <int>");
        System.out.println("  port list                  - Lista mappings");
        System.out.println();
        System.out.println("DFS (Sistema Distribuído):");
        System.out.println("  dfs put <path> <content>   - Escreve arquivo");
        System.out.println("  dfs get <path>             - Lê arquivo");
        System.out.println("  dfs ls [prefix]            - Lista arquivos");
        System.out.println("  dfs status                 - Status do DFS");
        System.out.println();
        System.out.println("NFS:");
        System.out.println("  nfs export <path> <fsPath> [--ro]");
        System.out.println("  nfs exports                - Lista exports");
        System.out.println("  nfs status                 - Status do NFS");
        System.out.println();
        System.out.println("CDN:");
        System.out.println("  cdn edge <region> <location> [cache-size]");
        System.out.println("  cdn invalidate <key|*>     - Invalida cache");
        System.out.println("  cdn status                 - Status do CDN");
        System.out.println();
    }

    private void printDiskInfo() {
        FileSystemSimulator.DiskInfo info = simulator.diskInfo();
        System.out.println();
        System.out.println("INFORMAÇÕES DO DISCO FAT");
        System.out.println();
        System.out.printf("Arquivo de dados: %s%n", simulator.dataPath().toAbsolutePath());
        System.out.printf("Número de clusters: %d%n", info.clusterCount());
        System.out.printf("Tamanho do cluster: %d bytes%n", info.clusterSize());
        System.out.println();
        System.out.printf("Capacidade total:  %s (%d bytes)%n", formatSize(info.totalBytes()), info.totalBytes());
        System.out.printf("Espaço usado:      %s (%d bytes)%n", formatSize(info.usedBytes()), info.usedBytes());
        System.out.printf("Espaço livre:      %s (%d bytes)%n", formatSize(info.freeBytes()), info.freeBytes());
        System.out.println();
        double usagePercent = (double) info.usedBytes() / info.totalBytes() * 100;
        System.out.printf("Uso: %.1f%%%n", usagePercent);
        System.out.println();
    }
}
