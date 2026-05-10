<div align="center">

<pre>
 ██████╗██╗      ██████╗ ██╗   ██╗██████╗ ███████╗██╗███╗   ███╗
██╔════╝██║     ██╔═══██╗██║   ██║██╔══██╗██╔════╝██║████╗ ████║
██║     ██║     ██║   ██║██║   ██║██║  ██║███████╗██║██╔████╔██║
██║     ██║     ██║   ██║██║   ██║██║  ██║╚════██║██║██║╚██╔╝██║
╚██████╗███████╗╚██████╔╝╚██████╔╝██████╔╝███████║██║██║ ╚═╝ ██║
 ╚═════╝╚══════╝ ╚═════╝  ╚═════╝ ╚═════╝ ╚══════╝╚═╝╚═╝     ╚═╝
</pre>

### FAT Filesystem Simulator with WAL Journaling and Cloud / Networking Stack

</div>

Educational simulator written in Java. Originally an Operating Systems class project — it implements a **FAT-style filesystem** backed by persistent clusters and a **Write-Ahead Logging journal**, then grows into a full **cloud-style networking layer**: real HTTP/TCP/NFS servers, VPC + subnets + DNS, S3-like storage, load balancer, API gateway, CDN, container networking and a distributed filesystem.

## Highlights

### Filesystem core
- **FAT table** persisted on disk (`filesystem_data.dat`), configurable cluster size (default 4096 B).
- **Write-Ahead Logging** journal (`journal.log`) — atomic operations, replay on recovery.
- **Interactive shell** for `cp`, `rm`, `mv`, `mkdir`, `ls`, `cat`, …
- Virtual tree: `VirtualFile`, `VirtualDirectory`, `FileSystemNode`.

### Cloud / networking stack (`com.example.filesystem.network`)
- **Real servers**: `HttpServer` (REST API), `TcpFileServer`, `NFSServer`.
- **Network infrastructure**: `VPC`, `Subnet`, `DNSServer`, `NatGateway`, `IPAddress`.
- **Cloud services**: `CloudStorage` (S3-like), `LoadBalancer`, `ApiGateway`, `CDN`, `ReverseProxy`, `ServiceDiscovery`, `QuotaManager`.
- **Containers**: `ContainerNetwork`, `VirtualInstance` — Docker-like isolation.
- **Distributed FS**: `DistributedFileSystem` with replication.
- **Security**: `SecurityGroup`, `SecurityRule`.

## Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                    FilesystemShell (CLI)                     │
└────────────────────────────┬─────────────────────────────────┘
                             │
                  ┌──────────▼──────────┐
                  │ FileSystemSimulator │
                  └──────┬───────┬──────┘
                         │       │
              ┌──────────▼──┐ ┌──▼──────────┐
              │   FatDisk    │ │   Journal    │
              │  (clusters)  │ │  (WAL log)   │
              └──────────────┘ └──────────────┘

           ┌────────────────────────────────────┐
           │      Cloud / Network layer         │
           │  HTTP · TCP · NFS · DNS · NAT      │
           │  S3-like · LB · APIGW · CDN        │
           │  VPC · Subnet · SG · DFS · Docker  │
           └────────────────────────────────────┘
```

## Build & run

```bash
mvn package
java -jar target/Gestor-1.0-SNAPSHOT.jar
# then drop into the interactive shell
```

## Project layout

```
Cloudsim/
├── pom.xml
├── REPORT.md                 # Full academic report (PT-BR)
└── src/main/java/com/example/filesystem/
    ├── Main.java
    ├── FilesystemShell.java
    ├── FileSystemSimulator.java
    ├── FatDisk.java
    ├── Journal.java · JournalEntry.java · OperationType.java
    ├── VirtualFile.java · VirtualDirectory.java · FileSystemNode.java
    └── network/              # ~25 classes — see Highlights
```

## Authors

University coursework — Operating Systems class, Prof. Izequiel.
Group: **Alberto** & **Fernando** ([Nandobez](https://github.com/Nandobez)).

See [`REPORT.md`](./REPORT.md) for the full academic write-up in Portuguese (with AI-generated diagrams).

## License

Educational use.
