# 📦 hg4j — Pure Java Mercurial (hg) Native Library

[![Build Status](https://img.shields.io/badge/build-passing-brightgreen.svg)]()
[![Java Version](https://img.shields.io/badge/java-21-blue.svg)](https://jdk.java.net/21/)
[![Gradle Version](https://img.shields.io/badge/gradle-9.4.1-blue.svg)](https://gradle.org)
[![Coverage Status](https://img.shields.io/badge/coverage-93.5%25%2B-brightgreen.svg)]()
[![License](https://img.shields.io/badge/license-Apache%202.0-orange.svg)](LICENSE)

**hg4j** is an enterprise-grade, high-performance, pure Java implementation of the **Mercurial (hg) SCM** version control system. Modeled after the architectural philosophy of **JGit** to the Git ecosystem, `hg4j` provides complete read/write capability, robust transactional durability, and binary-level interoperability with the official Mercurial CLI (verified against SCM v7.2.2).

---

## 🚀 Key Features

### 1. Plumbing API (Low-Level Storage & Parsing)
* **`HgRepository`**: Managing physical `.hg/` stores, file locks, transactional rollbacks, and system settings.
* **`Dirstate` (v1 / v2)**: High-performance tracking of working copy states. Supports high-throughput 44-byte fixed-size binary structures (`DirstateV2Node`) using Java NIO memory-mapping.
* **`Revlog` (v1)**: Robust parser for Mercurial index (`.i`) and data (`.d`) files, generaldelta support, and Myers-diff / LCS based multi-hunk delta generation.
* **`TreeWalk` / `RevWalk`**: High-performance directory tree and revision graph traversal engines.

### 2. Porcelain API (Command Facade)
* **`init`**: Initialize `.hg` repositories and writes system `requires`.
* **`add` / `remove` / `revert`**: Control tracked working copy files and update `dirstate`.
* **`commit`**: Generate atomic manifests, changesets (changelog), and append revisions with copy/rename metadata tracking.
* **`status`**: Fast changed, added, removed, and untracked file discovery.
* **`log` / `cat` / `diff`**: Browse history, recover file content, and diff modifications.
* **`branch` / `tag` / `bookmark`**: Full management of Mercurial branches, tags, and bookmarks.
* **`merge` / `rebase`**: LCA (Least Common Ancestor) lookup with caching, 3-way line-by-line merge engine (`Merge3`), and graph relocations.
* **`shelve` / `strip`**: Temporarily stash modifications or prune repository histories.
* **`push` / `pull` / `fetch`**: Synchronize repositories over HTTP and SSH wire protocols with capability negotiations.

### 3. Enterprise Durability & Performance
* **Atomicity (Journaling & Automatic Rollback)**: Ensures database safety by logging a `journal` of transactions; triggers automatic recovery upon operation interruptions.
* **Concurrent Lock Guards**: Utilizes OS-level physical locks (`.hg/wlock` and `.hg/store/lock`) via Java `FileChannel` to protect against write collisions in multi-user/multi-threaded deployments.
* **High-Throughput IO**: Zero-copy Memory-Mapped IO (`MappedByteBuffer`) limits JVM heap footprint during deep historical traversals.

---

## 🛠️ Technology Stack & Environment

* **Java Language Version**: **Java 21 (LTS)** (Pinned via `.sdkmanrc`)
* **Build System**: **Gradle 9.4.1**
* **Test Platform**: **JUnit 5**
* **Coverage Verification**: **JaCoCo 0.8.11** (Enforcing rigorous 90%+ branch and instruction coverage checks)

---

## ⚡ Quick Start

### 1. Requirements & Setup
Make sure you have `sdkman` installed to switch to the validated compiler and build environments:
```bash
# Set up compiler and build environment
sdk env
```

### 2. Compile and Test
Run full compilation, unit tests, and coverage verification using the Gradle wrapper:
```bash
# Compile and run E2E test suite (486+ test cases)
./gradlew clean test
```

Generate the Jacoco code coverage reports:
```bash
./gradlew jacocoTestReport
```

### 3. Generating Documentation
`hg4j` supports comprehensive multi-lingual documentation (English and Korean) for both high-level reference guides and Javadoc API specifications.

* **Asciidoctor Technical Reference Guide**:
  Compiles high-quality technical architecture manuals into standalone, structured HTML sites.
  ```bash
  ./gradlew asciidoctor
  ```
  * English Manual: `build/docs/asciidoc/en/index.html`
  * Korean Manual: `build/docs/asciidoc/ko/index.html`

* **Multi-lingual Javadoc API Reference**:
  Generates clean Java API documentations targeted at specific locales.
  ```bash
  # Generate English-standard API documentation (Locale: en_US)
  ./gradlew javadoc
  
  # Generate Korean-standard API documentation (Locale: ko_KR)
  ./gradlew javadocKo
  ```
  * English Javadoc: `build/docs/javadoc/index.html`
  * Korean Javadoc: `build/docs/javadoc-ko/index.html`

---

## 📖 Basic Usage Code Examples

### 1. Initialize and Add Files
```java
import com.github.search5.hg4j.api.Hg;
import com.github.search5.hg4j.core.HgRepository;
import java.io.File;

// Initialize a new repository
File repoDir = new File("/path/to/my_repository");
HgRepository repository = Hg.init()
    .setDirectory(repoDir)
    .call();

// Add untracked files
File textFile = new File(repoDir, "hello.txt");
Hg.add(repository)
    .addFile(textFile)
    .call();
```

### 2. Commit and Verify Status
```java
// Commit tracked changes
Hg.commit(repository)
    .setMessage("Initial commit of hg4j repository!")
    .setAuthor("Antigravity <antigravity@google.com>")
    .call();

// Inspect working directory status
com.github.search5.hg4j.api.Status status = Hg.status(repository).call();
System.out.println("Clean files: " + status.getClean());
System.out.println("Modified files: " + status.getModified());
```

### 3. History Traversal (Log)
```java
import com.github.search5.hg4j.api.HgCommit;
import java.util.List;

// Traverse commit log
List<HgCommit> commits = Hg.log(repository).call();
for (HgCommit commit : commits) {
    System.out.printf("Revision: %d | NodeID: %s | Author: %s\n", 
        commit.getRevision(), commit.getNodeIdHex(), commit.getAuthor());
    System.out.println("Message: " + commit.getMessage());
}
```

---

## ⚖️ JGit Comparison & Architectural Philosophy

While representing the structural purity of JGit's API design (decoupling Porcelain wrappers from low-level Plumbing kernels), `hg4j` adapts selectively to Mercurial's native differences:

| Architectural Metric | JGit (Git 진영 업계 표준) | hg4j (Mercurial 네이티브 라이브러리) | Design Resolution |
| :--- | :--- | :--- | :--- |
| **Storage Structure** | Pack-file indexing and Loose Object store | Revlog-per-file system (`.i` and `.d` files) | **No Garbage Collection**: Mercurial relies on self-contained file log databases, making Git's heavy pack compaction (`GcCommand`) redundant. |
| **History Query** | `RevFilter` graph traversers | Function-oriented `revset` engine | **Revset Engine**: Employs Mercurial's 19-function native SCM functional query language for flexible revision selections. |
| **Submodules** | Decoupled nested submodule checkouts | Native repositories only | **Omission**: Mercurial lacks Git's traditional nested submodule structures; this complexity is bypassed to increase stability. |
| **Branch Traversal** | RefSpec remote branches (`refs/heads/*`) | Named branches (`.hg/branch`) & Bookmarks | **Native Integration**: Seamlessly maps physical bookmarks and native branch labels, matching the official SCM round-trip spec. |

---

## 📄 License

This project is licensed under the **Apache License, Version 2.0**. For details, please consult the `LICENSE` file.
