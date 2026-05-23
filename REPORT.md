# Simulador de Sistema de Arquivos FAT com Journaling e Cloud Features

**Disciplina:** Sistemas Operacionais
**Atividade:** Simulador de Sistema de Arquivos
**Professor:** Izequiel
**Grupo:** Alberto e Fernando
**Link do Repositório GitHub:** https://github.com/Nandobez/File_manager

---

(aviso, para facilitar a leitura e entendimento do projeto, os diagramas foram gerados por IA)



## Resumo

Este trabalho apresenta um simulador educacional de sistema de arquivos baseado na arquitetura **FAT (File Allocation Table)** com suporte a **Journaling externo** e **funcionalidades de Cloud/Rede**. O sistema utiliza dois arquivos principais:

- **`.dat`** - Arquivo de dados contendo: FAT Table persistida, área de dados (clusters) e metadados
- **`.log`** - Journal externo implementando o protocolo **Write-Ahead Logging (WAL)**

O simulador permite executar operações típicas de um sistema operacional (copiar, apagar, renomear arquivos e diretórios) e disponibiliza um modo shell interativo para experimentação.

### Funcionalidades Cloud/Rede

O projeto inclui uma simulação completa de infraestrutura cloud:

- **Servidores Reais:** HTTP (REST API), TCP, NFS
- **Infraestrutura de Rede:** VPC, Subnets, DNS, NAT Gateway
- **Serviços Cloud:** S3-like Storage, Load Balancer, API Gateway, CDN
- **Containers:** Sistema Docker-like com rede isolada
- **Sistema Distribuído:** DFS (Distributed File System) com replicação

---

## Introdução

O gerenciamento eficiente de arquivos é crucial para o funcionamento dos sistemas operacionais. Para compreender como um sistema de arquivos é montado e organizado, é necessário entender suas estruturas fundamentais.

A arquitetura **FAT (File Allocation Table)** foi escolhida por sua simplicidade e ampla utilização em dispositivos removíveis (pen drives, cartões SD). Neste projeto, implementamos uma FAT com as seguintes características:

- **Clusters de tamanho configurável** (padrão: 4096 bytes)
- **Tabela FAT persistida em disco** (não apenas em memória)
- **Journaling externo** seguindo o protocolo WAL para garantir integridade

---

## Objetivo

Desenvolver em Java um simulador de sistema de arquivos que:

1. Implemente uma **estrutura FAT completa** com persistência em arquivo `.dat`
2. Execute **operações básicas** de manipulação de arquivos e diretórios
3. Utilize um **Journal externo** (arquivo `.log`) com protocolo WAL
4. Suporte **transações atômicas** com BEGIN/COMMIT/ABORT
5. Permita **recuperação automática** após falhas
6. Disponibilize um **modo shell avançado** com comandos interativos

---

## Metodologia

O simulador foi desenvolvido em Java seguindo uma arquitetura em camadas:

```
┌─────────────────────────────────────────────────────────────────┐
│                    FilesystemShell (UI Layer)                   │
│                   Interface de linha de comando                 │
├─────────────────────────────────────────────────────────────────┤
│                     CloudManager (Cloud Layer)                  │
│      HTTP Server | TCP Server | NFS | Load Balancer | CDN       │
│      VPC | DNS | S3 Storage | DFS | Container Network           │
├─────────────────────────────────────────────────────────────────┤
│                FileSystemSimulator (Business Layer)             │
│          Orquestração, validação e controle de transações       │
├──────────────────────────┬──────────────────────────────────────┤
│   FatDisk (.dat)         │         Journal (.log)               │
│   FAT Table + Clusters   │      Write-Ahead Logging             │
│   Persistência de dados  │      Controle de transações          │
└──────────────────────────┴──────────────────────────────────────┘
```

 
---

## Parte 1: Introdução ao Sistema de Arquivos com Journaling

### 1.1 O que é um Sistema de Arquivos?

Um sistema de arquivos é uma estrutura que organiza e controla como os dados são armazenados e recuperados em dispositivos de armazenamento. Ele provê:

- **Organização lógica:** hierarquia de diretórios e arquivos
- **Alocação de espaço:** gerenciamento de blocos/clusters
- **Metadados:** informações sobre arquivos (nome, tamanho, datas)
- **Controle de acesso:** permissões e segurança

### 1.2 Arquitetura FAT (File Allocation Table)

Na arquitetura FAT, o disco é dividido em unidades chamadas **clusters**. A **File Allocation Table** é uma estrutura que mapeia cada cluster:

```
Cluster 0:  → Cluster 3   (próximo na cadeia)
Cluster 1:  → FREE        (livre para uso)
Cluster 2:  → END_OF_CHAIN (fim do arquivo)
Cluster 3:  → Cluster 2   (continua para cluster 2)
...
```

**Valores especiais da FAT:**
- `FREE (-1)`: Cluster disponível
- `END_OF_CHAIN (-2)`: Último cluster de um arquivo
- `NO_CLUSTER (-3)`: Arquivo sem dados

### 1.3 Journaling e Write-Ahead Logging (WAL)

**Journaling** é uma técnica para garantir a integridade do sistema de arquivos. O princípio é simples:

> "Antes de fazer qualquer modificação nos dados, registre a intenção em um log."

O **Write-Ahead Logging (WAL)** implementa isso de forma rigorosa:

1. **BEGIN_TRANSACTION**: Marca início de uma operação atômica
2. **LOG das operações**: Registra cada ação a ser executada
3. **COMMIT_TRANSACTION**: Confirma que todas as ações foram aplicadas com sucesso
4. **ABORT_TRANSACTION**: Indica falha, operações devem ser desfeitas

**Benefícios do WAL:**
- Se o sistema falhar **antes do COMMIT**: operação é ignorada na recuperação
- Se o sistema falhar **após o COMMIT**: operação é garantidamente aplicada
- Permite **rollback automático** de transações incompletas

### 1.4 Tipos de Journaling

| Tipo | Descrição | Trade-off |
|------|-----------|-----------|
| **Metadata-only** | Apenas metadados são logados | Rápido, mas dados podem ser inconsistentes |
| **Full journaling** | Dados e metadados são logados | Mais seguro, porém mais lento |
| **Log-structured** | Todo o FS é um log imutável | Excelente para SSDs |

Neste simulador, implementamos **Full journaling** onde o conteúdo dos arquivos é codificado em Base64 no journal.

---

## Parte 2: Arquitetura do Simulador

### 2.1 Layout do Arquivo de Dados (.dat)

O arquivo `.dat` contém toda a estrutura persistente do sistema de arquivos:

```
┌──────────────────────────────────────────────────────────────┐
│ HEADER (512 bytes)                                           │
│   - Magic Number (4 bytes): "FAT1"                           │
│   - Versão (4 bytes): 1                                      │
│   - Número de clusters (4 bytes)                             │
│   - Tamanho do cluster (4 bytes)                             │
│   - Offset da FAT Table (8 bytes)                            │
│   - Offset da área de dados (8 bytes)                        │
│   - Offset dos metadados (8 bytes)                           │
│   - Checksum (4 bytes)                                       │
│   - Reservado (468 bytes)                                    │
├──────────────────────────────────────────────────────────────┤
│ FAT TABLE (clusterCount × 4 bytes)                           │
│   Cada entrada de 4 bytes indica o próximo cluster           │
├──────────────────────────────────────────────────────────────┤
│ ÁREA DE DADOS (clusterCount × clusterSize bytes)             │
│   Clusters contendo dados dos arquivos                       │
├──────────────────────────────────────────────────────────────┤
│ METADADOS (tamanho variável)                                 │
│   Estrutura de diretórios serializada                        │
└──────────────────────────────────────────────────────────────┘
```

### 2.2 Estrutura do Journal (.log)

O arquivo `.log` é um log sequencial com entradas no formato:

```
timestamp|OPERATION_TYPE|argCount|arg1_base64|arg2_base64|...
```

**Exemplo de journal:**
```
2025-12-05T21:41:36.732Z|BEGIN_TRANSACTION|1|ODQ2MjAzYjUtMQ==
2025-12-05T21:41:36.740Z|CREATE_DIRECTORY|1|L2RvY3M=
2025-12-05T21:41:36.742Z|COMMIT_TRANSACTION|1|ODQ2MjAzYjUtMQ==
```

### 2.3 Tipos de Operações no Journal

| Operação | Argumentos | Descrição |
|----------|------------|-----------|
| `BEGIN_TRANSACTION` | transactionId | Início de transação |
| `COMMIT_TRANSACTION` | transactionId | Confirmação de transação |
| `ABORT_TRANSACTION` | transactionId | Cancelamento de transação |
| `CREATE_DIRECTORY` | path | Criar diretório |
| `DELETE_DIRECTORY` | path, recursive | Remover diretório |
| `RENAME_DIRECTORY` | oldPath, newName | Renomear diretório |
| `CREATE_FILE_WITH_CONTENT` | path, base64Content | Criar/atualizar arquivo |
| `WRITE_FILE` | path, firstCluster, size | Metadados do arquivo |
| `COPY_FILE` | source, destination | Copiar arquivo |
| `DELETE_FILE` | path | Remover arquivo |
| `RENAME_FILE` | oldPath, newName | Renomear arquivo |
| `CHECKPOINT` | timestamp | Ponto de consistência |

### 2.4 Fluxo de uma Operação

```
Usuário: touch /docs/arquivo.txt "Conteúdo"
                    │
                    ▼
        ┌───────────────────────┐
        │  BEGIN_TRANSACTION    │ ──► Gravado no .log
        └───────────────────────┘
                    │
                    ▼
        ┌───────────────────────┐
        │ CREATE_FILE_WITH_     │ ──► Gravado no .log
        │ CONTENT               │     (path + conteúdo base64)
        └───────────────────────┘
                    │
                    ▼
        ┌───────────────────────┐
        │ Aloca clusters FAT    │ ──► Atualiza FAT no .dat
        │ Escreve dados         │ ──► Escreve nos clusters
        └───────────────────────┘
                    │
                    ▼
        ┌───────────────────────┐
        │ WRITE_FILE            │ ──► Gravado no .log
        │ (cluster, tamanho)    │     (metadados para recovery)
        └───────────────────────┘
                    │
                    ▼
        ┌───────────────────────┐
        │  COMMIT_TRANSACTION   │ ──► Gravado no .log
        └───────────────────────┘
                    │
                    ▼
              Operação concluída!
```

---

## Parte 3: Implementação em Java

### 3.1 Classes Principais

**Classes do Sistema de Arquivos:**

| Classe | Responsabilidade |
|--------|------------------|
| `FileSystemSimulator` | API de alto nível; gerencia operações, journaling e recuperação |
| `FatDisk` | Disco FAT com persistência completa (header + FAT + clusters) |
| `VirtualDirectory` | Representa diretórios na árvore em memória |
| `VirtualFile` | Representa arquivos com referência à cadeia de clusters |
| `Journal` | Gerencia o log de operações com suporte a transações |
| `JournalEntry` | Representa uma entrada individual no journal |
| `OperationType` | Enumeração dos tipos de operações |
| `FilesystemShell` | Interface de linha de comando interativa |
| `Main` | Ponto de entrada da aplicação |

**Classes Cloud/Rede (pacote `network`):**

| Classe | Responsabilidade |
|--------|------------------|
| `CloudManager` | Gerenciador central de todos os serviços cloud |
| `HttpServer` | Servidor HTTP com REST API real |
| `TcpFileServer` | Servidor TCP para transferência de arquivos |
| `NFSServer` | Network File System simplificado |
| `HttpClient` | Cliente HTTP interno (curl) |
| `NetworkManager` | Gerencia VPCs, subnets e instâncias |
| `VPC` | Virtual Private Cloud com CIDR |
| `Subnet` | Sub-rede com alocação de IPs |
| `DNSServer` | Servidor DNS com cache e TTL |
| `ServiceDiscovery` | Registry de serviços (Consul-like) |
| `LoadBalancer` | Balanceador de carga com múltiplos algoritmos |
| `ApiGateway` | Gateway com rate limiting e API keys |
| `ReverseProxy` | Proxy reverso com cache |
| `NatGateway` | NAT com port forwarding |
| `CloudStorage` | Storage S3-like com presigned URLs |
| `DistributedFileSystem` | DFS com replicação (HDFS-like) |
| `CDN` | Content Delivery Network com edge nodes |
| `ContainerNetwork` | Rede Docker-like para containers |
| `QuotaManager` | Gerenciamento de cotas por usuário |

### 3.2 Operações Implementadas

**Operações de Diretório:**
- `createDirectory(path)` - Cria um novo diretório
- `deleteDirectory(path, recursive)` - Remove diretório (opção recursiva)
- `renameDirectory(path, newName)` - Renomeia diretório

**Operações de Arquivo:**
- `createOrOverwriteFile(path, content)` - Cria ou sobrescreve arquivo
- `copyFile(source, destination)` - Copia arquivo
- `deleteFile(path)` - Remove arquivo
- `renameFile(path, newName)` - Renomeia arquivo

**Operações de Leitura:**
- `list(path)` - Lista conteúdo de diretório
- `readFile(path)` - Lê conteúdo de arquivo
- `exists(path)` - Verifica existência
- `isDirectory(path)` / `isFile(path)` - Verifica tipo
- `dumpTree()` - Mostra árvore completa do sistema

**Operações de Manutenção:**
- `checkpoint()` - Cria ponto de consistência
- `truncateJournal()` - Remove entradas antigas do journal

### 3.3 Processo de Recuperação

Na inicialização, o simulador executa a recuperação automática:

```java
private void recoverFromJournal() {
    recovering = true;
    // Lê apenas entradas de transações CONFIRMADAS
    List<JournalEntry> entries = journal.readCommittedEntries();

    for (JournalEntry entry : entries) {
        applyRecoveryEntry(entry);  // Reconstrói o estado
    }
    recovering = false;
}
```

**Regras de Recuperação:**
1. Apenas transações com `COMMIT_TRANSACTION` são aplicadas
2. Transações sem `COMMIT` são ignoradas (rollback implícito)
3. Transações com `ABORT_TRANSACTION` são ignoradas
4. Operações sem transação (legado) são aplicadas normalmente

### 3.4 Algoritmos Utilizados

O simulador utiliza 8 algoritmos principais:

#### 1. First-Fit (Alocação de Clusters)

**Onde:** `FatDisk.java` - método `allocateChainForSize()`

**Descrição:** Percorre a FAT sequencialmente e aloca os primeiros clusters livres encontrados.

```java
for (int i = 0; i < clusterCount && free.size() < needed; i++) {
    if (fat[i] == FREE) {
        free.add(i);  // Pega o primeiro livre
    }
}
```

**Complexidade:** O(n) onde n = número de clusters

---

#### 2. Lista Encadeada (Cadeia de Clusters)

**Onde:** `FatDisk.java` - métodos `readChain()`, `writeChain()`, `freeChain()`

**Descrição:** Cada arquivo é armazenado como uma lista encadeada de clusters. A FAT funciona como array de ponteiros.

```
Arquivo "doc.txt" (10KB) → 3 clusters encadeados:
fat[5] = 12    // Cluster 5 aponta para 12
fat[12] = 8    // Cluster 12 aponta para 8
fat[8] = -2    // Cluster 8 é o fim (END_OF_CHAIN)
```

**Complexidade:** O(c) onde c = número de clusters do arquivo

---

#### 3. Árvore N-ária (Estrutura de Diretórios)

**Onde:** `VirtualDirectory.java` - usando `LinkedHashMap<String, FileSystemNode>`

**Descrição:** Diretórios podem ter múltiplos filhos (arquivos ou subdiretórios). HashMap permite busca O(1) por nome. (Estrutura mais comum nesses gestores)

```
        / (raiz)
       /|\
      / | \
   docs usr tmp
    |
  readme.txt
```

**Complexidade:** O(1) para busca, O(n) para listagem

---

#### 4. DFS - Depth-First Search (Remoção Recursiva)

**Onde:** `FileSystemSimulator.java` - método `clearDirectory()`

**Descrição:** Para remover um diretório com `-r`, percorre a árvore em profundidade, removendo primeiro os filhos. (Ideia de tratar como grafo retirada da aula)

```java
private void clearDirectory(VirtualDirectory directory) {
    for (FileSystemNode node : snapshot.values()) {
        if (node instanceof VirtualDirectory dir) {
            clearDirectory(dir);  // Recursão (vai fundo primeiro)
            directory.removeChild(dir.getName());
        } else if (node instanceof VirtualFile file) {
            disk.freeChain(file.getFirstCluster());
            directory.removeChild(file.getName());
        }
    }
}
```

**Complexidade:** O(n) onde n = total de nós na subárvore

---

#### 5. Write-Ahead Logging - WAL (Journaling)

**Onde:** `Journal.java` e `FileSystemSimulator.java`

**Descrição:** Toda operação é registrada no journal ANTES de ser aplicada aos dados. Garante atomicidade.

```
1. BEGIN_TRANSACTION  ──► grava no .log
2. CREATE_FILE        ──► grava no .log
3. [executa operação] ──► modifica .dat
4. COMMIT_TRANSACTION ──► grava no .log (confirma)
```

**Se falhar antes do COMMIT:** operação é ignorada na recuperação

---

#### 6. Two-Pass Scan (Recuperação de Transações)

**Onde:** `Journal.java` - método `readCommittedEntries()`

**Descrição:** Varre o journal duas vezes: primeiro identifica transações confirmadas, depois filtra operações válidas.

```java
// PRIMEIRA PASSAGEM: encontrar COMMITs
for (JournalEntry entry : allEntries) {
    if (entry.getType() == COMMIT_TRANSACTION) {
        committedTransactions.add(entry.getArguments().get(0));
    }
}

// SEGUNDA PASSAGEM: filtrar operações de transações confirmadas
for (JournalEntry entry : allEntries) {
    if (committedTransactions.contains(currentTxId)) {
        committed.add(entry);
    }
}
```

**Complexidade:** O(n) onde n = entradas no journal

---

#### 7. Stack (Resolução de Caminhos)

**Onde:** `FileSystemSimulator.java` - método `parseSegments()`

**Descrição:** Usa pilha para resolver caminhos com `.` (atual) e `..` (pai).

```java
Deque<String> stack = new ArrayDeque<>();
for (String part : parts) {
    if (part.equals("..")) {
        if (!stack.isEmpty()) stack.removeLast();  // Volta um nível
    } else if (!part.equals(".") && !part.isBlank()) {
        stack.add(part);  // Adiciona ao caminho
    }
}
// "/docs/../usr/./bin" → ["usr", "bin"] → "/usr/bin"
```

**Complexidade:** O(p) onde p = profundidade do caminho

---

#### 8. FSM - Máquina de Estados Finitos (Tokenização)

**Onde:** `FilesystemShell.java` - método `tokenize()`

**Descrição:** Analisa comandos do shell reconhecendo strings entre aspas. (Ideia tirada de um exercício semelhante do Leetcode https://leetcode.com/problems/longest-absolute-file-path/description/)

```java
boolean quoted = false;  // Estado: NORMAL ou QUOTED
for (char c : line.toCharArray()) {
    if (c == '\"') {
        quoted = !quoted;  // Transição de estado
    } else if (Character.isWhitespace(c) && !quoted) {
        // Separador só vale fora de aspas
        tokens.add(current.toString());
    }
}
```

**Diagrama de Estados:**
```
         ┌─────────┐   "    ┌─────────┐
    ──►  │ NORMAL  │ ─────► │ QUOTED  │
         └─────────┘ ◄───── └─────────┘
              │         "
         espaço
              ▼
         [token]
```

**Complexidade:** O(n) onde n = tamanho do comando

---

#### Resumo dos Algoritmos

| # | Algoritmo | Onde é Usado | Complexidade |
|---|-----------|--------------|--------------|
| 1 | First-Fit | Alocação de clusters | O(n) |
| 2 | Lista Encadeada | Cadeia de clusters FAT | O(c) |
| 3 | Árvore N-ária | Estrutura de diretórios | O(1) busca |
| 4 | DFS | Remoção recursiva (`rmdir -r`) | O(n) |
| 5 | Write-Ahead Logging | Journaling transacional | O(1) escrita |
| 6 | Two-Pass Scan | Recuperação do journal | O(n) |
| 7 | Stack | Resolução de caminhos (`..`) | O(p) |
| 8 | FSM | Tokenização de comandos | O(n) |

---

## Parte 4: Instalação e Funcionamento

### 4.1 Requisitos

- **JDK 17+** (Java Development Kit)
- **Maven 3.8+** (Gerenciador de dependências)
- **Sistema Operacional:** Linux, macOS ou Windows

### 4.2 Compilação e Execução Rápida

```bash
# Clone o repositório
git clone https://github.com/Nandobez/File_manager
cd File_manager

# Compile o projeto
mvn clean package

# Execute (forma mais simples)
mvn exec:java -Dexec.mainClass="com.example.filesystem.Main"

# OU execute o JAR diretamente:
java -cp target/filesystem-simulator-1.0-SNAPSHOT.jar \
     com.example.filesystem.Main
```

### 4.3 Modos de Execução

**Modo Shell Interativo (Recomendado):**
```bash
# Usando Maven (mais simples)
mvn exec:java -Dexec.mainClass="com.example.filesystem.Main"

# Usando JAR
java -cp target/filesystem-simulator-1.0-SNAPSHOT.jar \
     com.example.filesystem.Main [caminho_do_journal.log]
```

**Modo Demonstração:**
```bash
java -cp target/filesystem-simulator-1.0-SNAPSHOT.jar \
     com.example.filesystem.FileSystemSimulator
```

### 4.4 Quick Start - Cloud Features

Após iniciar o simulador, experimente os comandos cloud:

```bash
# Ver ajuda de comandos cloud
cloud

# Ver status geral
cloud-status

# Iniciar servidor HTTP REST (porta 8080)
server start http 8080

# Em outro terminal, teste com curl:
# curl http://localhost:8080/api/v1/health

# Usar curl interno
curl http://localhost:8080/

# Criar e usar S3 Storage
s3 mb meu-bucket
s3 cp meu-bucket arquivo.txt "Conteúdo do arquivo"
s3 cat meu-bucket arquivo.txt

# Criar containers Docker-like
docker run web1 nginx
docker ps

# Sistema de arquivos distribuído
dfs put /dados/teste.txt "Hello DFS"
dfs get /dados/teste.txt
dfs status
```

### 4.5 Atalhos de Teclado do Shell

O shell suporta navegação no histórico de comandos e edição de linha:

| Tecla | Ação |
|-------|------|
| ↑ | Comando anterior |
| ↓ | Próximo comando |
| ← → | Mover cursor na linha |
| Ctrl+A | Início da linha |
| Ctrl+E | Fim da linha |
| Ctrl+R | Busca reversa no histórico |
| Ctrl+C | Cancelar linha atual |
| Ctrl+D | Sair do shell |
| `history` | Ver todos os comandos anteriores |

**Observação:** O histórico de comandos é salvo em `~/.fs_simulator_history` e persiste entre sessões.

### 4.6 Comandos do Shell (Sistema de Arquivos)

#### Navegação
| Comando | Descrição |
|---------|-----------|
| `pwd` | Mostra diretório atual |
| `cd <caminho>` | Muda para diretório |
| `ls [caminho]` | Lista conteúdo do diretório |
| `tree` | Mostra árvore completa do sistema |

#### Diretórios
| Comando | Descrição |
|---------|-----------|
| `mkdir <caminho>` | Cria diretório |
| `rmdir <caminho> [-r]` | Remove diretório (-r para recursivo) |
| `rename-dir <caminho> <novo>` | Renomeia diretório |

#### Arquivos
| Comando | Descrição |
|---------|-----------|
| `touch <arquivo> [conteúdo]` | Cria/atualiza arquivo |
| `cat <arquivo>` | Exibe conteúdo do arquivo |
| `cp <origem> <destino>` | Copia arquivo |
| `rm <arquivo>` | Remove arquivo |
| `rename-file <arquivo> <novo>` | Renomeia arquivo |

#### Sistema
| Comando | Descrição |
|---------|-----------|
| `disk` | Mostra informações do disco FAT |
| `journal` | Mostra informações do journal |
| `checkpoint` | Cria checkpoint de consistência |
| `clear` / `cls` | Limpa a tela do terminal |
| `history` | Mostra histórico de comandos |
| `help` | Mostra ajuda |
| `exit` / `quit` | Encerra o simulador |

### 4.7 Exemplo de Sessão

```
SIMULADOR DE SISTEMA DE ARQUIVOS COM JOURNALING
Arquitetura FAT + Write-Ahead Logging

Arquivo de dados: /home/user/filesystem_data.dat
Journal (WAL):    /home/user/journal.log
Disco FAT: 2048 clusters x 4096 bytes = 8388608 bytes total
Espaco: usado=0 bytes, livre=8388608 bytes

Digite 'help' para listar os comandos disponiveis.

/ $ mkdir documentos
/ $ cd documentos
/documentos $ touch readme.txt "Bem-vindo ao simulador!"
Arquivo criado com 23 bytes: /documentos/readme.txt
/documentos $ cat readme.txt
--- Conteudo de /documentos/readme.txt ---
Bem-vindo ao simulador!
--- Fim (23 bytes) ---
/documentos $ ls
TIPO    NOME                            TAMANHO  ATUALIZADO
--------------------------------------------------------------------------------
FILE    readme.txt                          23 B  2025-12-05T21:45:30
/documentos $ cd ..
/ $ tree
=== Sistema de Arquivos FAT ===
Arquivo de dados: /home/user/filesystem_data.dat
Journal: /home/user/journal.log
Clusters: 2048 x 4096 bytes
Espaco: total=8388608, usado=4096, livre=8384512 bytes
================================
/
  documentos/
    readme.txt (23 bytes, cluster=0)

/ $ disk

INFORMACOES DO DISCO FAT

Arquivo de dados: /home/user/filesystem_data.dat
Numero de clusters: 2048
Tamanho do cluster: 4096 bytes

Capacidade total:  8.0 MB (8388608 bytes)
Espaco usado:      4.0 KB (4096 bytes)
Espaco livre:      8.0 MB (8384512 bytes)

Uso: 0.0%

/ $ exit
Encerrando simulador...
```

---

## Resultados Esperados

1. **Compreensão prática** do funcionamento interno de um sistema de arquivos FAT
2. **Entendimento do Journaling** e como o protocolo WAL garante integridade
3. **Visualização de alocação de clusters** e encadeamento FAT
4. **Experiência com recuperação** de dados após falhas simuladas
5. **Base teórica e prática** para análise de sistemas de arquivos reais

---

## Arquivos Gerados pelo Simulador

| Arquivo | Descrição | Tamanho Típico |
|---------|-----------|----------------|
| `filesystem_data.dat` | Dados + FAT + Metadados | ~8.4 MB |
| `journal.log` | Log de operações (WAL) | Variável |

**Para reiniciar o simulador do zero:**
```bash
rm -f filesystem_data.dat journal.log
```

---

## Estrutura do Projeto

```
Gestor/
├── pom.xml                          # Configuração Maven
├── README.md                        # Este documento
├── src/main/java/com/example/filesystem/
│   ├── Main.java                    # Ponto de entrada
│   ├── FileSystemSimulator.java     # Orquestrador principal
│   ├── FatDisk.java                 # Disco FAT com persistência
│   ├── VirtualDirectory.java        # Nó de diretório
│   ├── VirtualFile.java             # Nó de arquivo
│   ├── FileSystemNode.java          # Classe base abstrata
│   ├── Journal.java                 # Gerenciador de log
│   ├── JournalEntry.java            # Entrada do journal
│   ├── OperationType.java           # Tipos de operação
│   ├── FilesystemShell.java         # Interface de comandos
│   │
│   └── network/                     # Módulo Cloud/Rede
│       ├── CloudManager.java        # Gerenciador central cloud
│       ├── HttpServer.java          # Servidor HTTP REST
│       ├── TcpFileServer.java       # Servidor TCP
│       ├── NFSServer.java           # Servidor NFS
│       ├── HttpClient.java          # Cliente HTTP (curl)
│       │
│       ├── NetworkManager.java      # Gerenciador de rede
│       ├── VPC.java                 # Virtual Private Cloud
│       ├── Subnet.java              # Sub-rede com CIDR
│       ├── IPAddress.java           # Representação IPv4
│       ├── VirtualInstance.java     # Instância virtual
│       ├── SecurityGroup.java       # Firewall rules
│       ├── SecurityRule.java        # Regras de segurança
│       │
│       ├── DNSServer.java           # Servidor DNS com cache
│       ├── ServiceDiscovery.java    # Service registry
│       ├── LoadBalancer.java        # Balanceador de carga
│       ├── ApiGateway.java          # API Gateway
│       ├── ReverseProxy.java        # Proxy reverso
│       ├── NatGateway.java          # NAT Gateway
│       │
│       ├── CloudStorage.java        # S3-like storage
│       ├── DistributedFileSystem.java # DFS (HDFS-like)
│       ├── CDN.java                 # Content Delivery Network
│       ├── ContainerNetwork.java    # Docker-like containers
│       └── QuotaManager.java        # Cotas de disco
│
└── target/
    └── filesystem-simulator-1.0-SNAPSHOT.jar
```

---

## Parte 5: Funcionalidades Cloud e Rede

O simulador inclui uma camada completa de cloud computing que simula serviços reais de infraestrutura.

### 5.1 Arquitetura Cloud

```
┌────────────────────────────────────────────────────────────────────┐
│                         CloudManager                                │
│  Gerenciador central de todos os serviços cloud                    │
├────────────────────────────────────────────────────────────────────┤
│                                                                    │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐             │
│  │ HTTP Server  │  │  TCP Server  │  │  NFS Server  │             │
│  │  REST API    │  │  Protocolo   │  │   Exports    │             │
│  │  Port 8080   │  │  Port 9000   │  │  Port 2049   │             │
│  └──────────────┘  └──────────────┘  └──────────────┘             │
│                                                                    │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐             │
│  │ API Gateway  │  │Load Balancer │  │Reverse Proxy │             │
│  │ Rate Limit   │  │ Round Robin  │  │   Routing    │             │
│  │ API Keys     │  │ Least Conn   │  │   Caching    │             │
│  └──────────────┘  └──────────────┘  └──────────────┘             │
│                                                                    │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐             │
│  │     VPC      │  │     DNS      │  │ NAT Gateway  │             │
│  │   Subnets    │  │   Records    │  │Port Forward  │             │
│  │  Instances   │  │   Caching    │  │   Mapping    │             │
│  └──────────────┘  └──────────────┘  └──────────────┘             │
│                                                                    │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐             │
│  │  S3 Storage  │  │     DFS      │  │     CDN      │             │
│  │   Buckets    │  │  DataNodes   │  │ Edge Nodes   │             │
│  │Presigned URL │  │ Replication  │  │   Caching    │             │
│  └──────────────┘  └──────────────┘  └──────────────┘             │
│                                                                    │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐             │
│  │  Containers  │  │   Service    │  │    Quota     │             │
│  │ Docker-like  │  │  Discovery   │  │   Manager    │             │
│  │   Network    │  │   Registry   │  │  Per User    │             │
│  └──────────────┘  └──────────────┘  └──────────────┘             │
└────────────────────────────────────────────────────────────────────┘
```

### 5.2 Servidores Disponíveis

| Servidor | Porta Padrão | Descrição |
|----------|--------------|-----------|
| HTTP Server | 8080 | REST API para operações de arquivo |
| TCP Server | 9000 | Protocolo texto para transferência |
| NFS Server | 2049 | Network File System simplificado |
| API Gateway | 8000 | Gateway com rate limiting e API keys |
| Reverse Proxy | 80 | Proxy reverso com cache |
| Load Balancer | Configurável | Balanceamento com múltiplos algoritmos |

### 5.3 Comandos Cloud do Shell

#### Status e Servidores

```bash
# Ver status geral do cloud
cloud-status

# Iniciar/parar servidores
server start http 8080      # Inicia HTTP Server na porta 8080
server start tcp 9000       # Inicia TCP Server
server start nfs 2049       # Inicia NFS Server
server start gateway 8000   # Inicia API Gateway
server start proxy 80       # Inicia Reverse Proxy
server start nat            # Inicia NAT Gateway

server stop http            # Para HTTP Server
server status               # Status de todos os servidores
```

#### VPC e Rede

```bash
# Virtual Private Cloud
vpc create minha-vpc 10.1.0.0/16
vpc list

# Subnets
subnet create minha-vpc web-subnet 10.1.1.0/24 --public
subnet create minha-vpc db-subnet 10.1.2.0/24

# Instâncias virtuais
instance create web-server minha-vpc web-subnet
instance list
```

#### DNS

```bash
# Gerenciar registros DNS
dns add api.exemplo.com 10.0.1.10
dns add db.exemplo.com 10.0.2.20
dns resolve api.exemplo.com
dns list
```

#### Service Discovery

```bash
# Registrar e descobrir serviços
service register api-service localhost 8080 http api v1
service register db-service localhost 5432 postgres
service discover api-service
service list
```

#### Quotas de Disco

```bash
# Gerenciar cotas por usuário
quota set joao 100MB 1000        # 100MB, máx 1000 arquivos
quota set maria 500MB 5000
quota show joao
quota list
```

#### Containers (Docker-like)

```bash
# Criar e gerenciar containers
docker run web1 nginx
docker run web2 nginx
docker run db1 postgres
docker ps                        # Lista containers
docker stop web1
docker network ls                # Lista redes
docker network create backend 172.18.0.0/16
```

#### Cloud Storage (S3-like)

```bash
# Buckets e objetos
s3 mb meu-bucket                 # Cria bucket
s3 ls                            # Lista buckets
s3 cp meu-bucket arquivo.txt "Conteúdo do arquivo"
s3 ls meu-bucket                 # Lista objetos
s3 cat meu-bucket arquivo.txt   # Lê objeto

# Presigned URLs (acesso temporário)
s3 presign get meu-bucket arquivo.txt 3600    # URL válida por 1h
s3 presign put meu-bucket upload.txt 600      # Upload URL por 10min
```

#### HTTP Client (curl interno)

```bash
# Fazer requisições HTTP
curl http://localhost:8080/                    # GET request
curl http://localhost:8080/api/v1/files/
curl -X POST http://localhost:8080/api/v1/files/test.txt -d "conteudo"
curl -v http://localhost:8080/api/v1/health   # Verbose
```

#### Load Balancer

```bash
# Configurar balanceamento de carga
lb start meu-lb 80 round-robin    # Algoritmos: round-robin, least-conn, ip-hash
lb add backend server1 10.0.1.1 8080
lb add backend server2 10.0.1.2 8080
lb add backend server3 10.0.1.3 8080
lb status
```

#### Port Forwarding

```bash
# NAT e redirecionamento de portas
port forward 80 web-server 8080   # Porta externa 80 → porta interna 8080
port forward 443 web-server 8443
port list
```

#### DFS (Sistema de Arquivos Distribuído)

```bash
# Arquivos distribuídos com replicação
dfs put /dados/arquivo.txt "Conteúdo distribuído"
dfs get /dados/arquivo.txt
dfs ls
dfs ls /dados
dfs status                        # Status dos DataNodes
```

#### NFS

```bash
# Network File System
nfs export /compartilhado /nfs/shared
nfs export /publico /nfs/public --ro   # Read-only
nfs exports                            # Lista exports
nfs status
```

#### CDN

```bash
# Content Delivery Network
cdn edge us-east Virginia 200          # Adiciona edge node
cdn edge eu-west Ireland 150
cdn invalidate arquivo.txt             # Invalida cache
cdn invalidate *                       # Invalida tudo
cdn status
```

### 5.4 API REST do HTTP Server

Quando o HTTP Server está rodando, você pode acessar via curl externo ou navegador:

| Método | Endpoint | Descrição |
|--------|----------|-----------|
| GET | `/` | Documentação da API |
| GET | `/api/v1/files/{path}` | Lê conteúdo do arquivo |
| POST | `/api/v1/files/{path}` | Cria/atualiza arquivo |
| DELETE | `/api/v1/files/{path}` | Remove arquivo |
| GET | `/api/v1/dirs/{path}` | Lista diretório |
| POST | `/api/v1/dirs/{path}` | Cria diretório |
| DELETE | `/api/v1/dirs/{path}` | Remove diretório |
| GET | `/api/v1/tree` | Árvore do filesystem |
| GET | `/api/v1/info` | Informações do disco |
| GET | `/api/v1/health` | Health check |

**Exemplo de uso com curl externo:**
```bash
# Iniciar o servidor no shell do simulador
# server start http 8080

# Em outro terminal:
curl http://localhost:8080/api/v1/health
curl http://localhost:8080/api/v1/dirs/
curl -X POST http://localhost:8080/api/v1/dirs/minha-pasta
curl -X POST http://localhost:8080/api/v1/files/minha-pasta/teste.txt -d "Hello World"
curl http://localhost:8080/api/v1/files/minha-pasta/teste.txt
```

### 5.5 Protocolo TCP Server

O TCP Server usa um protocolo simples baseado em texto:

```
COMANDO|arg1|arg2|... → OK|resultado ou ERR|mensagem
```

**Comandos disponíveis:**

| Comando | Uso | Descrição |
|---------|-----|-----------|
| LIST | `LIST\|/path` | Lista diretório |
| GET | `GET\|/path/file.txt` | Lê arquivo (base64) |
| PUT | `PUT\|/path/file.txt\|conteudo` | Cria arquivo |
| DELETE | `DELETE\|/path/file.txt` | Remove arquivo |
| MKDIR | `MKDIR\|/path` | Cria diretório |
| RMDIR | `RMDIR\|/path\|true` | Remove diretório (recursive) |
| EXISTS | `EXISTS\|/path` | Verifica existência |
| INFO | `INFO` | Informações do disco |
| TREE | `TREE` | Árvore completa |
| HELP | `HELP` | Lista comandos |
| QUIT | `QUIT` | Desconecta |

**Exemplo de conexão:**
```bash
# Conectar via netcat
nc localhost 9000
LIST|/
GET|/arquivo.txt
QUIT
```

### 5.6 Exemplo de Sessão Cloud

```
/ $ cloud-status
╔════════════════════════════════════════╗
║         CLOUD MANAGER STATUS           ║
╠════════════════════════════════════════╣
║ Region: sa-east-1                      ║
║ Public IP: 164.17.59.1                 ║
╠════════════════════════════════════════╣
║ SERVICES                               ║
╠════════════════════════════════════════╣
║ ○ HTTP Server        DOWN              ║
║ ○ TCP Server         DOWN              ║
║ ○ NFS Server         DOWN              ║
╠════════════════════════════════════════╣
║ RESOURCES                              ║
╠════════════════════════════════════════╣
║ VPCs: 1                                ║
║ DNS Records: 2                         ║
║ S3 Buckets: 1                          ║
║ DFS Files: 0                           ║
║ CDN Edge Nodes: 3                      ║
╚════════════════════════════════════════╝

/ $ server start http 8080
HTTP Server iniciado na porta 8080

/ $ curl http://localhost:8080/api/v1/health
{
    "status": "healthy",
    "uptime": "PT5.234S",
    "requests": 0,
    "bytesServed": 0
}

/ $ s3 mb dados-bucket
Bucket criado: dados-bucket

/ $ s3 cp dados-bucket config.json '{"env": "production"}'
Objeto criado: s3://dados-bucket/config.json

/ $ s3 presign get dados-bucket config.json 3600
URL (válida por 3600s):
http://localhost/dados-bucket/config.json?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Expires=3600&X-Amz-Signature=abc123...

/ $ docker run app1 nodejs
Container criado e iniciado: a1b2c3d4-567

/ $ docker ps
=== Containers ===
ID           NAME    IMAGE    STATUS     IP
a1b2c3d4-567 app1    nodejs   RUNNING    172.17.0.2

/ $ dfs put /logs/app.log "Application started successfully"
Arquivo escrito no DFS: /logs/app.log

/ $ dfs status
=== Distributed File System ===
Block Size: 64.0 KB
Default Replication: 2
Total Files: 1
Total Capacity: 300.0 MB
Total Used: 32 B

DataNodes:
  DataNode[node1 @ 10.0.1.1:9001, used=32B/100.0MB, blocks=1, alive=true]
  DataNode[node2 @ 10.0.1.2:9002, used=32B/100.0MB, blocks=1, alive=true]
  DataNode[node3 @ 10.0.1.3:9003, used=0B/100.0MB, blocks=0, alive=true]
```

---

## Considerações Finais

- O simulador **persiste todos os dados** em arquivos físicos (`.dat` e `.log`)
- A **FAT é salva no disco**, não apenas em memória
- O **journal é externo** ao sistema de arquivos, seguindo a arquitetura correta de WAL
- **Transações atômicas** garantem que operações são "tudo ou nada"
- O sistema suporta **recuperação automática** após falhas
- Os **servidores HTTP e TCP são reais** e podem ser acessados por clientes externos
- A **infraestrutura cloud simula** serviços como AWS VPC, S3, ELB, etc.

---
