# Simulador de Sistema de Arquivos FAT com Journaling

**Disciplina:** Sistemas Operacionais
**Atividade:** Simulador de Sistema de Arquivos
**Professor:** Izequiel
**Grupo:** Alberto e Fernando
**Link do Repositório GitHub:** https://github.com/Nandobez/File_manager

---

(aviso, para facilitar a leitura e entendimento do projeto, os diagramas foram gerados por IA)



## Resumo

Este trabalho apresenta um simulador educacional de sistema de arquivos baseado na arquitetura **FAT (File Allocation Table)** com suporte a **Journaling externo**. O sistema utiliza dois arquivos principais:

- **`.dat`** - Arquivo de dados contendo: FAT Table persistida, área de dados (clusters) e metadados
- **`.log`** - Journal externo implementando o protocolo **Write-Ahead Logging (WAL)**

O simulador permite executar operações típicas de um sistema operacional (copiar, apagar, renomear arquivos e diretórios) e disponibiliza um modo shell interativo para experimentação.

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

### 4.2 Compilação

```bash
# Clone o repositório
git clone <URL_DO_REPOSITORIO>
cd Gestor

# Compile o projeto
mvn clean package

# O JAR será gerado em:
# target/filesystem-simulator-1.0-SNAPSHOT.jar
```

### 4.3 Execução

**Modo Shell Interativo (Recomendado):**
```bash
java -cp target/filesystem-simulator-1.0-SNAPSHOT.jar \
     com.example.filesystem.Main [caminho_do_journal.log]

# Exemplo:
java -cp target/filesystem-simulator-1.0-SNAPSHOT.jar \
     com.example.filesystem.Main ./journal.log
```

**Modo Demonstração:**
```bash
java -cp target/filesystem-simulator-1.0-SNAPSHOT.jar \
     com.example.filesystem.FileSystemSimulator
```

### 4.4 Comandos do Shell

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
| `help` | Mostra ajuda |
| `exit` / `quit` | Encerra o simulador |

### 4.5 Exemplo de Sessão

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
│   └── FilesystemShell.java         # Interface de comandos
└── target/
    └── filesystem-simulator-1.0-SNAPSHOT.jar
```

---

## Considerações Finais

- O simulador **persiste todos os dados** em arquivos físicos (`.dat` e `.log`)
- A **FAT é salva no disco**, não apenas em memória
- O **journal é externo** ao sistema de arquivos, seguindo a arquitetura correta de WAL
- **Transações atômicas** garantem que operações são "tudo ou nada"
- O sistema suporta **recuperação automática** após falhas

---
