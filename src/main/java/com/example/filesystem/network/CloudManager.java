package com.example.filesystem.network;

import com.example.filesystem.FileSystemSimulator;

import java.io.IOException;

/**
 * Gerenciador central de todos os serviços de cloud.
 */
public class CloudManager {
    private final FileSystemSimulator fs;
    private final NetworkManager networkManager;
    private final ServiceDiscovery serviceDiscovery;
    private final QuotaManager quotaManager;
    private final ContainerNetwork containerNetwork;
    private final CDN cdn;
    private final CloudStorage cloudStorage;
    private final DistributedFileSystem dfs;

    // Servidores
    private HttpServer httpServer;
    private TcpFileServer tcpServer;
    private NFSServer nfsServer;
    private ApiGateway apiGateway;
    private ReverseProxy reverseProxy;
    private LoadBalancer loadBalancer;
    private NatGateway natGateway;

    // Configurações
    private final String region;
    private final String baseUrl;

    public CloudManager(FileSystemSimulator fs, String region) {
        this.fs = fs;
        this.region = region;
        this.baseUrl = "http://localhost";

        // Inicializar componentes
        this.networkManager = new NetworkManager(region);
        this.serviceDiscovery = new ServiceDiscovery();
        this.quotaManager = new QuotaManager();
        this.containerNetwork = new ContainerNetwork();
        this.cdn = new CDN();
        this.cloudStorage = new CloudStorage(fs, baseUrl, region);
        this.dfs = new DistributedFileSystem(64 * 1024, 2); // 64KB blocos, replicação 2 para demo

        // Configurar infraestrutura padrão
        setupDefaultInfrastructure();
    }

    private void setupDefaultInfrastructure() {
        // VPC padrão já criada pelo NetworkManager

        // CDN edge nodes
        cdn.addEdgeNode("us-east-1", "Virginia", 100);
        cdn.addEdgeNode("eu-west-1", "Ireland", 100);
        cdn.addEdgeNode("sa-east-1", "São Paulo", 100);

        // DFS data nodes
        dfs.addDataNode("10.0.1.1", 9001, 1024 * 1024 * 100); // 100MB
        dfs.addDataNode("10.0.1.2", 9002, 1024 * 1024 * 100);
        dfs.addDataNode("10.0.1.3", 9003, 1024 * 1024 * 100);

        // Bucket padrão
        cloudStorage.createBucket("default-bucket");
    }

    // ==================== Server Management ====================

    public void startHttpServer(int port) throws IOException {
        if (httpServer != null && httpServer.isRunning()) {
            throw new IllegalStateException("HTTP Server já está rodando");
        }
        httpServer = new HttpServer(port, "0.0.0.0", fs);
        httpServer.start();

        serviceDiscovery.register("http-server", "localhost", port, "http", "api");
        System.out.println("HTTP Server iniciado na porta " + port);
    }

    public void stopHttpServer() {
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
            System.out.println("HTTP Server parado");
        }
    }

    public void startTcpServer(int port) throws IOException {
        if (tcpServer != null && tcpServer.isRunning()) {
            throw new IllegalStateException("TCP Server já está rodando");
        }
        tcpServer = new TcpFileServer(port, "0.0.0.0", fs);
        tcpServer.start();

        serviceDiscovery.register("tcp-server", "localhost", port, "tcp", "files");
        System.out.println("TCP Server iniciado na porta " + port);
    }

    public void stopTcpServer() {
        if (tcpServer != null) {
            tcpServer.stop();
            tcpServer = null;
            System.out.println("TCP Server parado");
        }
    }

    public void startNfsServer(int port) throws IOException {
        if (nfsServer != null && nfsServer.isRunning()) {
            throw new IllegalStateException("NFS Server já está rodando");
        }
        nfsServer = new NFSServer(fs, port);
        nfsServer.addExport("/shared", "/nfs/shared");
        nfsServer.addExport("/public", "/nfs/public", true); // read-only
        nfsServer.start();

        serviceDiscovery.register("nfs-server", "localhost", port, "nfs");
        System.out.println("NFS Server iniciado na porta " + port);
    }

    public void stopNfsServer() {
        if (nfsServer != null) {
            nfsServer.stop();
            nfsServer = null;
            System.out.println("NFS Server parado");
        }
    }

    public void startApiGateway(int port) throws IOException {
        if (apiGateway != null && apiGateway.isRunning()) {
            throw new IllegalStateException("API Gateway já está rodando");
        }
        apiGateway = new ApiGateway(port);

        // Rotas padrão
        if (httpServer != null && httpServer.isRunning()) {
            apiGateway.addRoute("/api/*", "localhost", httpServer.getPort(), "/api");
        }

        apiGateway.start();
        serviceDiscovery.register("api-gateway", "localhost", port, "gateway");
        System.out.println("API Gateway iniciado na porta " + port);
    }

    public void stopApiGateway() {
        if (apiGateway != null) {
            apiGateway.stop();
            apiGateway = null;
            System.out.println("API Gateway parado");
        }
    }

    public void startReverseProxy(int port) throws IOException {
        if (reverseProxy != null && reverseProxy.isRunning()) {
            throw new IllegalStateException("Reverse Proxy já está rodando");
        }
        reverseProxy = new ReverseProxy(port);
        reverseProxy.start();

        serviceDiscovery.register("reverse-proxy", "localhost", port, "proxy");
        System.out.println("Reverse Proxy iniciado na porta " + port);
    }

    public void stopReverseProxy() {
        if (reverseProxy != null) {
            reverseProxy.stop();
            reverseProxy = null;
            System.out.println("Reverse Proxy parado");
        }
    }

    public void startLoadBalancer(String name, int port, LoadBalancer.Algorithm algorithm) throws IOException {
        if (loadBalancer != null && loadBalancer.isRunning()) {
            throw new IllegalStateException("Load Balancer já está rodando");
        }
        loadBalancer = new LoadBalancer(name, port, algorithm);
        loadBalancer.start();

        serviceDiscovery.register("load-balancer", "localhost", port, "lb");
        System.out.println("Load Balancer '" + name + "' iniciado na porta " + port);
    }

    public void stopLoadBalancer() {
        if (loadBalancer != null) {
            loadBalancer.stop();
            loadBalancer = null;
            System.out.println("Load Balancer parado");
        }
    }

    public void startNatGateway() {
        if (natGateway != null && natGateway.isRunning()) {
            throw new IllegalStateException("NAT Gateway já está rodando");
        }
        natGateway = new NatGateway(networkManager.getPublicIP(), "10.0.0.0/16");
        natGateway.start();
        System.out.println("NAT Gateway iniciado (IP público: " + networkManager.getPublicIP() + ")");
    }

    public void stopNatGateway() {
        if (natGateway != null) {
            natGateway.stop();
            natGateway = null;
            System.out.println("NAT Gateway parado");
        }
    }

    // ==================== Getters ====================

    public FileSystemSimulator getFs() { return fs; }
    public NetworkManager getNetworkManager() { return networkManager; }
    public ServiceDiscovery getServiceDiscovery() { return serviceDiscovery; }
    public QuotaManager getQuotaManager() { return quotaManager; }
    public ContainerNetwork getContainerNetwork() { return containerNetwork; }
    public CDN getCdn() { return cdn; }
    public CloudStorage getCloudStorage() { return cloudStorage; }
    public DistributedFileSystem getDfs() { return dfs; }
    public DNSServer getDns() { return networkManager.getDNSServer(); }

    public HttpServer getHttpServer() { return httpServer; }
    public TcpFileServer getTcpServer() { return tcpServer; }
    public NFSServer getNfsServer() { return nfsServer; }
    public ApiGateway getApiGateway() { return apiGateway; }
    public ReverseProxy getReverseProxy() { return reverseProxy; }
    public LoadBalancer getLoadBalancer() { return loadBalancer; }
    public NatGateway getNatGateway() { return natGateway; }

    public String getRegion() { return region; }

    // ==================== Shutdown ====================

    public void shutdown() {
        stopHttpServer();
        stopTcpServer();
        stopNfsServer();
        stopApiGateway();
        stopReverseProxy();
        stopLoadBalancer();
        stopNatGateway();

        serviceDiscovery.shutdown();
        dfs.shutdown();

        System.out.println("CloudManager encerrado");
    }

    // ==================== Status ====================

    public String getStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("╔════════════════════════════════════════╗\n");
        sb.append("║         CLOUD MANAGER STATUS           ║\n");
        sb.append("╠════════════════════════════════════════╣\n");
        sb.append("║ Region: ").append(String.format("%-30s", region)).append("║\n");
        sb.append("║ Public IP: ").append(String.format("%-27s", networkManager.getPublicIP())).append("║\n");
        sb.append("╠════════════════════════════════════════╣\n");
        sb.append("║ SERVICES                               ║\n");
        sb.append("╠════════════════════════════════════════╣\n");

        sb.append(formatServiceStatus("HTTP Server", httpServer != null && httpServer.isRunning(),
            httpServer != null ? httpServer.getPort() : 0));
        sb.append(formatServiceStatus("TCP Server", tcpServer != null && tcpServer.isRunning(),
            tcpServer != null ? tcpServer.getPort() : 0));
        sb.append(formatServiceStatus("NFS Server", nfsServer != null && nfsServer.isRunning(),
            nfsServer != null ? nfsServer.getPort() : 0));
        sb.append(formatServiceStatus("API Gateway", apiGateway != null && apiGateway.isRunning(),
            apiGateway != null ? apiGateway.getPort() : 0));
        sb.append(formatServiceStatus("Reverse Proxy", reverseProxy != null && reverseProxy.isRunning(),
            reverseProxy != null ? reverseProxy.getPort() : 0));
        sb.append(formatServiceStatus("Load Balancer", loadBalancer != null && loadBalancer.isRunning(),
            loadBalancer != null ? loadBalancer.getListenPort() : 0));
        sb.append(formatServiceStatus("NAT Gateway", natGateway != null && natGateway.isRunning(), 0));

        sb.append("╠════════════════════════════════════════╣\n");
        sb.append("║ RESOURCES                              ║\n");
        sb.append("╠════════════════════════════════════════╣\n");
        sb.append(String.format("║ VPCs: %-33d║\n", networkManager.getVPCs().size()));
        sb.append(String.format("║ Instances: %-28d║\n", networkManager.getInstances().size()));
        sb.append(String.format("║ DNS Records: %-26d║\n", networkManager.getDNSServer().getAllRecords().size()));
        sb.append(String.format("║ Services Registered: %-18d║\n", serviceDiscovery.getServiceNames().size()));
        sb.append(String.format("║ Containers: %-27d║\n", containerNetwork.getContainers().size()));
        sb.append(String.format("║ S3 Buckets: %-27d║\n", cloudStorage.listBuckets().size()));
        sb.append(String.format("║ DFS Files: %-28d║\n", dfs.listFiles().size()));
        sb.append(String.format("║ CDN Edge Nodes: %-23d║\n", cdn.getEdgeNodes().size()));

        sb.append("╚════════════════════════════════════════╝\n");
        return sb.toString();
    }

    private String formatServiceStatus(String name, boolean running, int port) {
        String status = running ? "●" : "○";
        String portStr = running && port > 0 ? ":" + port : "";
        return String.format("║ %s %-18s %s%-13s║\n",
            status, name, running ? "UP" : "DOWN", portStr);
    }
}
