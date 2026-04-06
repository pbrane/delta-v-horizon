# Eliminate opennms-services Monolith — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Delete the `opennms-services` monolith by extracting Pollerd, Collectd, and EventTranslator implementations into focused feature modules, deleting all daemon-loader modules, and breaking all 14 remaining dependents.

**Architecture:** Three new modules (`features/poller/impl`, `features/collection/impl`, `features/event-translator`) absorb the living daemon implementations. Registry classes move to `core/daemon-common`. Dead code (~50 classes) is deleted without extraction.

**Tech Stack:** Java 17, Maven, Spring Boot 4 / Spring 7 (daemon-boots), OSGi bundle packaging (feature modules)

**Spec:** `docs/superpowers/specs/2026-03-27-eliminate-opennms-services-design.md`

---

## File Map

### New Files
- `features/poller/impl/pom.xml` — New Maven module for Pollerd implementation
- `features/poller/impl/src/main/java/org/opennms/netmgt/poller/*.java` — 6 Pollerd classes moved from opennms-services
- `features/poller/impl/src/main/java/org/opennms/netmgt/poller/jmx/*.java` — 2 JMX classes
- `features/poller/impl/src/main/java/org/opennms/netmgt/poller/pollables/*.java` — 20 pollables classes
- `features/poller/impl/src/main/java/org/opennms/netmgt/passive/*.java` — 3 passive status classes
- `features/collection/impl/pom.xml` — New Maven module for Collectd implementation
- `features/collection/impl/src/main/java/org/opennms/netmgt/collectd/*.java` — 10 Collectd classes
- `features/collection/impl/src/main/java/org/opennms/netmgt/collectd/jmx/*.java` — 2 JMX classes
- `features/event-translator/pom.xml` — New Maven module for EventTranslator
- `features/event-translator/src/main/java/org/opennms/netmgt/translator/*.java` — 1 class
- `features/event-translator/src/main/java/org/opennms/netmgt/translator/jmx/*.java` — 2 JMX classes

### Modified Files
- `core/daemon-common/pom.xml` — Add poller.api + collection.api dependencies for registries
- `core/daemon-common/src/main/java/org/opennms/core/daemon/common/registry/` — 3 registry classes extracted from daemon-loaders
- `core/pom.xml` — Remove 7 daemon-loader module entries
- `features/poller/pom.xml` — Add `impl` child module
- `features/collection/pom.xml` — Add `impl` child module
- `features/pom.xml` — Add `event-translator` child module
- `root pom.xml` — Remove `opennms-services` module
- `container/features/src/main/resources/features.xml` — Remove daemon-loader feature definitions
- `core/daemon-boot-pollerd/pom.xml` — Replace opennms-services with poller.impl
- `core/daemon-boot-collectd/pom.xml` — Replace opennms-services with collection.impl
- `core/daemon-boot-eventtranslator/pom.xml` — Replace opennms-services with event-translator
- 11 dependent module pom.xml files — Replace/remove opennms-services dependency

### Deleted Files
- `opennms-services/` — Entire module (84 source + 88 test files + pom.xml)
- `core/daemon-loader-shared/` — Entire module
- `core/daemon-loader-collectd/` — Entire module
- `core/daemon-loader-pollerd/` — Entire module
- `core/daemon-loader-perspectivepoller/` — Entire module
- `core/daemon-loader-enlinkd/` — Entire module
- `core/daemon-loader-bsmd/` — Entire module
- `core/daemon-loader-telemetryd/` — Entire module
- `core/daemon-loader-alarmd/` — Stub directory
- `core/daemon-loader-discovery/` — Stub directory
- `core/daemon-loader-eventtranslator/` — Stub directory
- `core/daemon-loader-provisiond/` — Stub directory
- `core/daemon-loader-rtcd/` — Stub directory
- `core/daemon-loader-syslogd/` — Stub directory
- `core/daemon-loader-ticketer/` — Stub directory
- `core/daemon-loader-trapd/` — Stub directory

---

### Task 1: Create Feature Branch

**Files:** None

- [ ] **Step 1: Create and switch to feature branch**

```bash
git checkout develop
git pull origin develop
git checkout -b chore/eliminate-opennms-services
```

- [ ] **Step 2: Verify clean state**

```bash
git status
```

Expected: clean working tree on `chore/eliminate-opennms-services`

---

### Task 2: Extract Registry Classes to daemon-common

**Files:**
- Modify: `core/daemon-common/pom.xml`
- Create: `core/daemon-common/src/main/java/org/opennms/core/daemon/common/registry/LocalServiceDetectorRegistry.java`
- Create: `core/daemon-common/src/main/java/org/opennms/core/daemon/common/registry/LocalServiceCollectorRegistry.java`
- Create: `core/daemon-common/src/main/java/org/opennms/core/daemon/common/registry/LocalServiceMonitorRegistry.java`
- Source: `core/daemon-loader-shared/src/main/java/org/opennms/core/daemon/loader/LocalServiceDetectorRegistry.java`
- Source: `core/daemon-loader-collectd/src/main/java/org/opennms/core/daemon/loader/LocalServiceCollectorRegistry.java`
- Source: `core/daemon-loader-pollerd/src/main/java/org/opennms/core/daemon/loader/LocalServiceMonitorRegistry.java`

- [ ] **Step 1: Add collection.api and poller.api dependencies to daemon-common**

In `core/daemon-common/pom.xml`, add these dependencies (needed for `ServiceCollectorRegistry` and `ServiceMonitorRegistry` interfaces). Find the `</dependencies>` closing tag and add before it:

```xml
        <dependency>
            <groupId>org.opennms.features.collection</groupId>
            <artifactId>org.opennms.features.collection.api</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.opennms.features.poller</groupId>
            <artifactId>org.opennms.features.poller.api</artifactId>
            <version>${project.version}</version>
        </dependency>
```

- [ ] **Step 2: Copy and repackage LocalServiceDetectorRegistry**

Copy `core/daemon-loader-shared/src/main/java/org/opennms/core/daemon/loader/LocalServiceDetectorRegistry.java` to `core/daemon-common/src/main/java/org/opennms/core/daemon/common/registry/LocalServiceDetectorRegistry.java`.

Change the package declaration from:
```java
package org.opennms.core.daemon.loader;
```
to:
```java
package org.opennms.core.daemon.common.registry;
```

- [ ] **Step 3: Copy and repackage LocalServiceCollectorRegistry**

Copy `core/daemon-loader-collectd/src/main/java/org/opennms/core/daemon/loader/LocalServiceCollectorRegistry.java` to `core/daemon-common/src/main/java/org/opennms/core/daemon/common/registry/LocalServiceCollectorRegistry.java`.

Change the package declaration from:
```java
package org.opennms.core.daemon.loader;
```
to:
```java
package org.opennms.core.daemon.common.registry;
```

- [ ] **Step 4: Copy and repackage LocalServiceMonitorRegistry**

Copy `core/daemon-loader-pollerd/src/main/java/org/opennms/core/daemon/loader/LocalServiceMonitorRegistry.java` (the full version with explicit registration, not the simplified perspectivepoller version) to `core/daemon-common/src/main/java/org/opennms/core/daemon/common/registry/LocalServiceMonitorRegistry.java`.

Change the package declaration from:
```java
package org.opennms.core.daemon.loader;
```
to:
```java
package org.opennms.core.daemon.common.registry;
```

- [ ] **Step 5: Update all imports referencing the old package**

Search the entire codebase for imports of `org.opennms.core.daemon.loader.LocalService` and update to `org.opennms.core.daemon.common.registry.LocalService`. Key files to check:

```bash
grep -r "org.opennms.core.daemon.loader.LocalService" --include="*.java" .
```

Update each hit. Expected locations:
- `core/daemon-common/src/main/java/org/opennms/core/daemon/common/DaemonProvisioningConfiguration.java` (LocalServiceDetectorRegistry)
- Various daemon-boot configuration classes

- [ ] **Step 6: Compile daemon-common to verify**

```bash
./compile.pl -DskipTests --projects :org.opennms.core.daemon-common -am install
```

Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add core/daemon-common/
git commit -m "refactor: extract registry classes from daemon-loaders into daemon-common

Move LocalServiceDetectorRegistry, LocalServiceCollectorRegistry, and
LocalServiceMonitorRegistry to org.opennms.core.daemon.common.registry
package in preparation for daemon-loader deletion."
```

---

### Task 3: Delete All Daemon-Loader Modules

**Files:**
- Delete: All 15 `core/daemon-loader-*` directories
- Modify: `core/pom.xml` — Remove 7 module entries

- [ ] **Step 1: Remove daemon-loader module entries from core/pom.xml**

Remove these 7 `<module>` lines from `core/pom.xml`:

```xml
        <module>daemon-loader-shared</module>
        <module>daemon-loader-collectd</module>
        <module>daemon-loader-pollerd</module>
        <module>daemon-loader-enlinkd</module>
        <module>daemon-loader-bsmd</module>
        <module>daemon-loader-perspectivepoller</module>
        <module>daemon-loader-telemetryd</module>
```

- [ ] **Step 2: Remove daemon-loader dependencies from daemon-common/pom.xml**

The `core/daemon-common/pom.xml` currently depends on all daemon-loader modules. Remove these dependency blocks:

```xml
        <dependency>
            <groupId>org.opennms.core</groupId>
            <artifactId>org.opennms.core.daemon-loader-shared</artifactId>
            ...
        </dependency>
```

Remove all `<dependency>` blocks with artifactIds matching `org.opennms.core.daemon-loader-*` (there should be 7).

- [ ] **Step 3: Delete all 15 daemon-loader directories**

```bash
rm -rf core/daemon-loader-shared
rm -rf core/daemon-loader-collectd
rm -rf core/daemon-loader-pollerd
rm -rf core/daemon-loader-perspectivepoller
rm -rf core/daemon-loader-enlinkd
rm -rf core/daemon-loader-bsmd
rm -rf core/daemon-loader-telemetryd
rm -rf core/daemon-loader-alarmd
rm -rf core/daemon-loader-discovery
rm -rf core/daemon-loader-eventtranslator
rm -rf core/daemon-loader-provisiond
rm -rf core/daemon-loader-rtcd
rm -rf core/daemon-loader-syslogd
rm -rf core/daemon-loader-ticketer
rm -rf core/daemon-loader-trapd
```

- [ ] **Step 4: Search for any remaining references to daemon-loader artifacts**

```bash
grep -r "daemon-loader" --include="*.xml" --include="*.java" --include="*.properties" . | grep -v target/ | grep -v ".git/"
```

Fix any remaining references. Expected hits: `features.xml` (handled in Task 4) and possibly other Karaf feature files.

- [ ] **Step 5: Compile core module to verify**

```bash
./compile.pl -DskipTests --projects :org.opennms.core.daemon-common -am install
```

Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add -A core/daemon-loader-* core/pom.xml core/daemon-common/pom.xml
git commit -m "chore: delete all 15 daemon-loader modules

Daemon-loaders were Karaf OSGi launchers from the pre-Spring-Boot era.
All daemons now run as standalone Spring Boot apps (daemon-boot-*).
Registry classes were extracted to daemon-common in the previous commit."
```

---

### Task 4: Clean Up Karaf Features.xml

**Files:**
- Modify: `container/features/src/main/resources/features.xml`

- [ ] **Step 1: Remove daemon-loader feature definitions from features.xml**

In `container/features/src/main/resources/features.xml`, remove the following feature definitions entirely (approximately lines 2077-2268):

- `<feature name="opennms-daemon-pollerd" ...>` through its closing `</feature>`
- `<feature name="opennms-daemon-perspectivepoller" ...>` through its closing `</feature>`
- `<feature name="opennms-daemon-collectd" ...>` through its closing `</feature>`
- `<feature name="opennms-daemon-enlinkd" ...>` through its closing `</feature>`
- `<feature name="opennms-daemon-bsmd" ...>` through its closing `</feature>`
- `<feature name="opennms-daemon-telemetryd" ...>` through its closing `</feature>`

- [ ] **Step 2: Search for references to removed features**

```bash
grep -r "opennms-daemon-pollerd\|opennms-daemon-collectd\|opennms-daemon-enlinkd\|opennms-daemon-bsmd\|opennms-daemon-telemetryd\|opennms-daemon-perspectivepoller" --include="*.xml" --include="*.boot" --include="*.cfg" . | grep -v target/ | grep -v ".git/"
```

Remove any references found (likely in featuresBoot files or webapp overlays).

- [ ] **Step 3: Commit**

```bash
git add container/features/
git commit -m "chore: remove daemon-loader Karaf feature definitions

These features loaded daemons into the Karaf OSGi container. All daemons
now run as standalone Spring Boot apps, making these features dead code."
```

---

### Task 5: Create features/poller/impl Module and Move Pollerd Classes

**Files:**
- Create: `features/poller/impl/pom.xml`
- Create: `features/poller/impl/src/main/java/org/opennms/netmgt/poller/` (move 6 classes)
- Create: `features/poller/impl/src/main/java/org/opennms/netmgt/poller/jmx/` (move 2 classes)
- Create: `features/poller/impl/src/main/java/org/opennms/netmgt/poller/pollables/` (move 20 classes)
- Create: `features/poller/impl/src/main/java/org/opennms/netmgt/passive/` (move 3 classes)
- Modify: `features/poller/pom.xml` — Add `impl` child module

- [ ] **Step 1: Create the features/poller/impl/pom.xml**

Read `opennms-services/pom.xml` to understand the dependency set, then create a focused POM. The new module needs the subset of opennms-services' dependencies that the Pollerd classes actually use.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.opennms.features.poller</groupId>
        <artifactId>org.opennms.features.poller</artifactId>
        <version>36.0.0-SNAPSHOT</version>
    </parent>
    <artifactId>org.opennms.features.poller.impl</artifactId>
    <packaging>bundle</packaging>
    <name>OpenNMS :: Features :: Poller :: Implementation</name>
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.felix</groupId>
                <artifactId>maven-bundle-plugin</artifactId>
                <extensions>true</extensions>
                <configuration>
                    <instructions>
                        <Bundle-RequiredExecutionEnvironment>JavaSE-17</Bundle-RequiredExecutionEnvironment>
                        <Bundle-SymbolicName>${project.artifactId}</Bundle-SymbolicName>
                    </instructions>
                </configuration>
            </plugin>
        </plugins>
    </build>
    <dependencies>
        <!-- Poller API -->
        <dependency>
            <groupId>org.opennms.features.poller</groupId>
            <artifactId>org.opennms.features.poller.api</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.opennms.features.poller</groupId>
            <artifactId>org.opennms.features.poller.client-rpc</artifactId>
            <version>${project.version}</version>
        </dependency>
        <!-- Collection API (for thresholding/latency storage) -->
        <dependency>
            <groupId>org.opennms.features.collection</groupId>
            <artifactId>org.opennms.features.collection.api</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.opennms.features.collection</groupId>
            <artifactId>org.opennms.features.collection.thresholding.api</artifactId>
            <version>${project.version}</version>
        </dependency>
        <!-- Core -->
        <dependency>
            <groupId>org.opennms</groupId>
            <artifactId>opennms-dao-api</artifactId>
        </dependency>
        <dependency>
            <groupId>org.opennms</groupId>
            <artifactId>opennms-dao</artifactId>
        </dependency>
        <dependency>
            <groupId>org.opennms</groupId>
            <artifactId>opennms-config</artifactId>
        </dependency>
        <dependency>
            <groupId>org.opennms</groupId>
            <artifactId>opennms-config-api</artifactId>
        </dependency>
        <dependency>
            <groupId>org.opennms</groupId>
            <artifactId>opennms-model</artifactId>
        </dependency>
        <dependency>
            <groupId>org.opennms.core</groupId>
            <artifactId>org.opennms.core.daemon</artifactId>
        </dependency>
        <dependency>
            <groupId>org.opennms.core</groupId>
            <artifactId>org.opennms.core.lib</artifactId>
        </dependency>
        <!-- Events -->
        <dependency>
            <groupId>org.opennms.features.events</groupId>
            <artifactId>org.opennms.features.events.api</artifactId>
            <version>${project.version}</version>
        </dependency>
        <!-- IPC Twin (for PassiveStatusTwinPublisher) -->
        <dependency>
            <groupId>org.opennms.core.ipc.twin</groupId>
            <artifactId>org.opennms.core.ipc.twin.api</artifactId>
            <version>${project.version}</version>
        </dependency>
        <!-- MATE (EntityScopeProvider) -->
        <dependency>
            <groupId>org.opennms.core</groupId>
            <artifactId>org.opennms.core.mate.api</artifactId>
            <version>${project.version}</version>
        </dependency>
        <!-- Resilience4j (AsyncPollingEngine) -->
        <dependency>
            <groupId>io.github.resilience4j</groupId>
            <artifactId>resilience4j-circuitbreaker</artifactId>
        </dependency>
        <dependency>
            <groupId>io.github.resilience4j</groupId>
            <artifactId>resilience4j-bulkhead</artifactId>
        </dependency>
        <!-- Logging -->
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
        </dependency>
        <!-- Spring -->
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-beans</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-context</artifactId>
        </dependency>
        <!-- TSID -->
        <dependency>
            <groupId>org.opennms.core</groupId>
            <artifactId>org.opennms.core.tsid</artifactId>
            <version>${project.version}</version>
        </dependency>
    </dependencies>
</project>
```

**Note:** This POM is a starting point. The exact dependency set will be refined during compilation — expect to iterate by adding missing dependencies and removing unused ones. Use `./compile.pl -DskipTests --projects :org.opennms.features.poller.impl -am install` to test.

- [ ] **Step 2: Add impl module to features/poller/pom.xml**

Add `<module>impl</module>` to the `<modules>` section of `features/poller/pom.xml`:

```xml
    <modules>
        <module>api</module>
        <module>client-rpc</module>
        <module>impl</module>
        <module>monitors</module>
        <module>runtime</module>
        <module>shell</module>
    </modules>
```

- [ ] **Step 3: Move Pollerd source files**

Move these files from `opennms-services/src/main/java/` to `features/poller/impl/src/main/java/`, preserving package structure:

**org.opennms.netmgt.poller/ (6 files):**
```bash
mkdir -p features/poller/impl/src/main/java/org/opennms/netmgt/poller
cp opennms-services/src/main/java/org/opennms/netmgt/poller/AsyncPollingEngine.java features/poller/impl/src/main/java/org/opennms/netmgt/poller/
cp opennms-services/src/main/java/org/opennms/netmgt/poller/Poller.java features/poller/impl/src/main/java/org/opennms/netmgt/poller/
cp opennms-services/src/main/java/org/opennms/netmgt/poller/PollerEventProcessor.java features/poller/impl/src/main/java/org/opennms/netmgt/poller/
cp opennms-services/src/main/java/org/opennms/netmgt/poller/QueryManager.java features/poller/impl/src/main/java/org/opennms/netmgt/poller/
cp opennms-services/src/main/java/org/opennms/netmgt/poller/QueryManagerDaoImpl.java features/poller/impl/src/main/java/org/opennms/netmgt/poller/
cp opennms-services/src/main/java/org/opennms/netmgt/poller/DefaultPollContext.java features/poller/impl/src/main/java/org/opennms/netmgt/poller/
```

**org.opennms.netmgt.poller.jmx/ (2 files):**
```bash
mkdir -p features/poller/impl/src/main/java/org/opennms/netmgt/poller/jmx
cp opennms-services/src/main/java/org/opennms/netmgt/poller/jmx/Pollerd.java features/poller/impl/src/main/java/org/opennms/netmgt/poller/jmx/
cp opennms-services/src/main/java/org/opennms/netmgt/poller/jmx/PollerdMBean.java features/poller/impl/src/main/java/org/opennms/netmgt/poller/jmx/
```

**org.opennms.netmgt.poller.pollables/ (20 files):**
```bash
mkdir -p features/poller/impl/src/main/java/org/opennms/netmgt/poller/pollables
cp opennms-services/src/main/java/org/opennms/netmgt/poller/pollables/*.java features/poller/impl/src/main/java/org/opennms/netmgt/poller/pollables/
```

**org.opennms.netmgt.passive/ (3 files):**
```bash
mkdir -p features/poller/impl/src/main/java/org/opennms/netmgt/passive
cp opennms-services/src/main/java/org/opennms/netmgt/passive/*.java features/poller/impl/src/main/java/org/opennms/netmgt/passive/
```

- [ ] **Step 4: Compile the new module**

```bash
./compile.pl -DskipTests --projects :org.opennms.features.poller.impl -am install
```

Expected: BUILD SUCCESS. If not, iterate on pom.xml dependencies based on compiler errors.

- [ ] **Step 5: Move relevant test files**

Move Pollerd-related test files from `opennms-services/src/test/java/` to `features/poller/impl/src/test/java/`:

```bash
mkdir -p features/poller/impl/src/test/java/org/opennms/netmgt/poller
mkdir -p features/poller/impl/src/test/java/org/opennms/netmgt/poller/pollables
mkdir -p features/poller/impl/src/test/java/org/opennms/netmgt/poller/mock
mkdir -p features/poller/impl/src/test/java/org/opennms/netmgt/passive

cp opennms-services/src/test/java/org/opennms/netmgt/poller/*.java features/poller/impl/src/test/java/org/opennms/netmgt/poller/
cp opennms-services/src/test/java/org/opennms/netmgt/poller/pollables/*.java features/poller/impl/src/test/java/org/opennms/netmgt/poller/pollables/
cp opennms-services/src/test/java/org/opennms/netmgt/poller/mock/*.java features/poller/impl/src/test/java/org/opennms/netmgt/poller/mock/
cp opennms-services/src/test/java/org/opennms/netmgt/passive/*.java features/poller/impl/src/test/java/org/opennms/netmgt/passive/
cp opennms-services/src/test/java/org/opennms/netmgt/poller/monitors/PassiveServiceMonitorIT.java features/poller/impl/src/test/java/org/opennms/netmgt/poller/monitors/
```

Also copy test resource files if any exist under `opennms-services/src/test/resources/` that are Pollerd-specific. Check with:
```bash
find opennms-services/src/test/resources -name "*poller*" -o -name "*poll*" -o -name "*passive*" | head -20
```

Add test-scoped dependencies to `features/poller/impl/pom.xml` as needed (JUnit, Mockito, opennms-test-api, mock-elements, etc.).

- [ ] **Step 6: Commit**

```bash
git add features/poller/impl/ features/poller/pom.xml
git commit -m "refactor: extract Pollerd implementation into features/poller/impl

Move 31 Pollerd classes (Poller, QueryManager, pollables, passive status)
from opennms-services into a focused features/poller/impl module."
```

---

### Task 6: Create features/collection/impl Module and Move Collectd Classes

**Files:**
- Create: `features/collection/impl/pom.xml`
- Create: `features/collection/impl/src/main/java/org/opennms/netmgt/collectd/` (move 10 classes)
- Create: `features/collection/impl/src/main/java/org/opennms/netmgt/collectd/jmx/` (move 2 classes)
- Modify: `features/collection/pom.xml` — Add `impl` child module

- [ ] **Step 1: Create the features/collection/impl/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.opennms.features.collection</groupId>
        <artifactId>org.opennms.features.collection</artifactId>
        <version>36.0.0-SNAPSHOT</version>
    </parent>
    <artifactId>org.opennms.features.collection.impl</artifactId>
    <packaging>bundle</packaging>
    <name>OpenNMS :: Features :: Collection :: Implementation</name>
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.felix</groupId>
                <artifactId>maven-bundle-plugin</artifactId>
                <extensions>true</extensions>
                <configuration>
                    <instructions>
                        <Bundle-RequiredExecutionEnvironment>JavaSE-17</Bundle-RequiredExecutionEnvironment>
                        <Bundle-SymbolicName>${project.artifactId}</Bundle-SymbolicName>
                    </instructions>
                </configuration>
            </plugin>
        </plugins>
    </build>
    <dependencies>
        <!-- Collection API + Core -->
        <dependency>
            <groupId>org.opennms.features.collection</groupId>
            <artifactId>org.opennms.features.collection.api</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.opennms.features.collection</groupId>
            <artifactId>org.opennms.features.collection.core</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.opennms.features.collection</groupId>
            <artifactId>org.opennms.features.collection.snmp-collector</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.opennms.features.collection</groupId>
            <artifactId>org.opennms.features.collection.client-rpc</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.opennms.features.collection</groupId>
            <artifactId>org.opennms.features.collection.thresholding.api</artifactId>
            <version>${project.version}</version>
        </dependency>
        <!-- Poller API (for PollOutagesConfig) -->
        <dependency>
            <groupId>org.opennms.features.poller</groupId>
            <artifactId>org.opennms.features.poller.api</artifactId>
            <version>${project.version}</version>
        </dependency>
        <!-- Core -->
        <dependency>
            <groupId>org.opennms</groupId>
            <artifactId>opennms-dao-api</artifactId>
        </dependency>
        <dependency>
            <groupId>org.opennms</groupId>
            <artifactId>opennms-dao</artifactId>
        </dependency>
        <dependency>
            <groupId>org.opennms</groupId>
            <artifactId>opennms-config</artifactId>
        </dependency>
        <dependency>
            <groupId>org.opennms</groupId>
            <artifactId>opennms-config-api</artifactId>
        </dependency>
        <dependency>
            <groupId>org.opennms</groupId>
            <artifactId>opennms-model</artifactId>
        </dependency>
        <dependency>
            <groupId>org.opennms.core</groupId>
            <artifactId>org.opennms.core.daemon</artifactId>
        </dependency>
        <dependency>
            <groupId>org.opennms.core</groupId>
            <artifactId>org.opennms.core.lib</artifactId>
        </dependency>
        <!-- Events -->
        <dependency>
            <groupId>org.opennms.features.events</groupId>
            <artifactId>org.opennms.features.events.api</artifactId>
            <version>${project.version}</version>
        </dependency>
        <!-- SNMP -->
        <dependency>
            <groupId>org.opennms.core.snmp</groupId>
            <artifactId>org.opennms.core.snmp.api</artifactId>
            <version>${project.version}</version>
        </dependency>
        <!-- MATE (EntityScopeProvider) -->
        <dependency>
            <groupId>org.opennms.core</groupId>
            <artifactId>org.opennms.core.mate.api</artifactId>
            <version>${project.version}</version>
        </dependency>
        <!-- Logging -->
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
        </dependency>
        <!-- Spring -->
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-beans</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-context</artifactId>
        </dependency>
    </dependencies>
</project>
```

**Note:** As with poller/impl, iterate on dependencies during compilation.

- [ ] **Step 2: Add impl module to features/collection/pom.xml**

Add `<module>impl</module>` to the `<modules>` section of `features/collection/pom.xml`:

```xml
    <modules>
        <module>api</module>
        <module>client-rpc</module>
        <module>collectors</module>
        <module>core</module>
        <module>impl</module>
        <module>persistence-rrd</module>
        <module>persistence-tcp</module>
        <module>persistence-osgi</module>
        <module>shell-commands</module>
        <module>snmp-collector</module>
        <module>thresholding</module>
        <module>test-api</module>
    </modules>
```

- [ ] **Step 3: Move Collectd source files**

```bash
mkdir -p features/collection/impl/src/main/java/org/opennms/netmgt/collectd
mkdir -p features/collection/impl/src/main/java/org/opennms/netmgt/collectd/jmx

cp opennms-services/src/main/java/org/opennms/netmgt/collectd/Collectd.java features/collection/impl/src/main/java/org/opennms/netmgt/collectd/
cp opennms-services/src/main/java/org/opennms/netmgt/collectd/CollectableService.java features/collection/impl/src/main/java/org/opennms/netmgt/collectd/
cp opennms-services/src/main/java/org/opennms/netmgt/collectd/CollectorUpdates.java features/collection/impl/src/main/java/org/opennms/netmgt/collectd/
cp opennms-services/src/main/java/org/opennms/netmgt/collectd/DefaultResourceTypeMapper.java features/collection/impl/src/main/java/org/opennms/netmgt/collectd/
cp opennms-services/src/main/java/org/opennms/netmgt/collectd/DefaultSnmpCollectionAgent.java features/collection/impl/src/main/java/org/opennms/netmgt/collectd/
cp opennms-services/src/main/java/org/opennms/netmgt/collectd/DefaultSnmpCollectionAgentFactory.java features/collection/impl/src/main/java/org/opennms/netmgt/collectd/
cp opennms-services/src/main/java/org/opennms/netmgt/collectd/DefaultSnmpCollectionAgentService.java features/collection/impl/src/main/java/org/opennms/netmgt/collectd/
cp opennms-services/src/main/java/org/opennms/netmgt/collectd/CiscoQoSPropertyExtender.java features/collection/impl/src/main/java/org/opennms/netmgt/collectd/
cp opennms-services/src/main/java/org/opennms/netmgt/collectd/IndexSplitPropertyExtender.java features/collection/impl/src/main/java/org/opennms/netmgt/collectd/
cp opennms-services/src/main/java/org/opennms/netmgt/collectd/PersistRegexSelectorStrategy.java features/collection/impl/src/main/java/org/opennms/netmgt/collectd/

cp opennms-services/src/main/java/org/opennms/netmgt/collectd/jmx/Collectd.java features/collection/impl/src/main/java/org/opennms/netmgt/collectd/jmx/
cp opennms-services/src/main/java/org/opennms/netmgt/collectd/jmx/CollectdMBean.java features/collection/impl/src/main/java/org/opennms/netmgt/collectd/jmx/
```

- [ ] **Step 4: Compile the new module**

```bash
./compile.pl -DskipTests --projects :org.opennms.features.collection.impl -am install
```

Expected: BUILD SUCCESS. Iterate on dependencies if needed.

- [ ] **Step 5: Move relevant test files**

```bash
mkdir -p features/collection/impl/src/test/java/org/opennms/netmgt/collectd

cp opennms-services/src/test/java/org/opennms/netmgt/collectd/*.java features/collection/impl/src/test/java/org/opennms/netmgt/collectd/
```

Copy test resources as needed:
```bash
find opennms-services/src/test/resources -name "*collect*" -o -name "*snmp*" | head -20
```

Add test-scoped dependencies to pom.xml.

- [ ] **Step 6: Commit**

```bash
git add features/collection/impl/ features/collection/pom.xml
git commit -m "refactor: extract Collectd implementation into features/collection/impl

Move 12 Collectd classes (Collectd, CollectableService, SNMP agents,
property extenders) from opennms-services into features/collection/impl."
```

---

### Task 7: Create features/event-translator Module and Move EventTranslator Classes

**Files:**
- Create: `features/event-translator/pom.xml`
- Create: `features/event-translator/src/main/java/org/opennms/netmgt/translator/EventTranslator.java`
- Create: `features/event-translator/src/main/java/org/opennms/netmgt/translator/jmx/EventTranslator.java`
- Create: `features/event-translator/src/main/java/org/opennms/netmgt/translator/jmx/EventTranslatorMBean.java`
- Modify: `features/pom.xml` — Add `event-translator` child module

- [ ] **Step 1: Create features/event-translator/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.opennms</groupId>
        <artifactId>org.opennms.features</artifactId>
        <version>36.0.0-SNAPSHOT</version>
    </parent>
    <groupId>org.opennms.features</groupId>
    <artifactId>org.opennms.features.event-translator</artifactId>
    <packaging>bundle</packaging>
    <name>OpenNMS :: Features :: Event Translator</name>
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.felix</groupId>
                <artifactId>maven-bundle-plugin</artifactId>
                <extensions>true</extensions>
                <configuration>
                    <instructions>
                        <Bundle-RequiredExecutionEnvironment>JavaSE-17</Bundle-RequiredExecutionEnvironment>
                        <Bundle-SymbolicName>${project.artifactId}</Bundle-SymbolicName>
                    </instructions>
                </configuration>
            </plugin>
        </plugins>
    </build>
    <dependencies>
        <!-- Core -->
        <dependency>
            <groupId>org.opennms</groupId>
            <artifactId>opennms-config</artifactId>
        </dependency>
        <dependency>
            <groupId>org.opennms</groupId>
            <artifactId>opennms-config-api</artifactId>
        </dependency>
        <dependency>
            <groupId>org.opennms</groupId>
            <artifactId>opennms-model</artifactId>
        </dependency>
        <dependency>
            <groupId>org.opennms</groupId>
            <artifactId>opennms-dao-api</artifactId>
        </dependency>
        <dependency>
            <groupId>org.opennms.core</groupId>
            <artifactId>org.opennms.core.daemon</artifactId>
        </dependency>
        <!-- Events -->
        <dependency>
            <groupId>org.opennms.features.events</groupId>
            <artifactId>org.opennms.features.events.api</artifactId>
            <version>${project.version}</version>
        </dependency>
        <!-- Logging -->
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
        </dependency>
        <!-- Spring -->
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-beans</artifactId>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 2: Add event-translator module to features/pom.xml**

Add `<module>event-translator</module>` to the `<modules>` section of `features/pom.xml`.

- [ ] **Step 3: Move EventTranslator source files**

```bash
mkdir -p features/event-translator/src/main/java/org/opennms/netmgt/translator
mkdir -p features/event-translator/src/main/java/org/opennms/netmgt/translator/jmx

cp opennms-services/src/main/java/org/opennms/netmgt/translator/EventTranslator.java features/event-translator/src/main/java/org/opennms/netmgt/translator/
cp opennms-services/src/main/java/org/opennms/netmgt/translator/jmx/EventTranslator.java features/event-translator/src/main/java/org/opennms/netmgt/translator/jmx/
cp opennms-services/src/main/java/org/opennms/netmgt/translator/jmx/EventTranslatorMBean.java features/event-translator/src/main/java/org/opennms/netmgt/translator/jmx/
```

- [ ] **Step 4: Compile the new module**

```bash
./compile.pl -DskipTests --projects :org.opennms.features.event-translator -am install
```

Expected: BUILD SUCCESS.

- [ ] **Step 5: Move test files**

```bash
mkdir -p features/event-translator/src/test/java/org/opennms/netmgt/translator
cp opennms-services/src/test/java/org/opennms/netmgt/translator/EventTranslatorIT.java features/event-translator/src/test/java/org/opennms/netmgt/translator/
```

Also move the EventTranslatorConfigFactory test:
```bash
mkdir -p features/event-translator/src/test/java/org/opennms/netmgt/config
cp opennms-services/src/test/java/org/opennms/netmgt/config/EventTranslatorConfigFactoryIT.java features/event-translator/src/test/java/org/opennms/netmgt/config/
```

- [ ] **Step 6: Commit**

```bash
git add features/event-translator/ features/pom.xml
git commit -m "refactor: extract EventTranslator into features/event-translator

Move EventTranslator daemon (3 classes) from opennms-services into
a focused features/event-translator module."
```

---

### Task 8: Update Daemon-Boot POMs to Use New Modules

**Files:**
- Modify: `core/daemon-boot-pollerd/pom.xml`
- Modify: `core/daemon-boot-collectd/pom.xml`
- Modify: `core/daemon-boot-eventtranslator/pom.xml`
- Modify: `core/daemon-boot-perspectivepollerd/pom.xml`

- [ ] **Step 1: Update daemon-boot-pollerd/pom.xml**

Replace the `opennms-services` dependency block (with its ~21 Spring/Hibernate exclusions) with:

```xml
        <dependency>
            <groupId>org.opennms.features.poller</groupId>
            <artifactId>org.opennms.features.poller.impl</artifactId>
            <version>${project.version}</version>
        </dependency>
```

Remove the entire old block:
```xml
        <dependency>
            <groupId>org.opennms</groupId>
            <artifactId>opennms-services</artifactId>
            <version>${project.version}</version>
            <exclusions>
                ...
            </exclusions>
        </dependency>
```

- [ ] **Step 2: Update daemon-boot-collectd/pom.xml**

Same pattern — replace `opennms-services` dependency with:

```xml
        <dependency>
            <groupId>org.opennms.features.collection</groupId>
            <artifactId>org.opennms.features.collection.impl</artifactId>
            <version>${project.version}</version>
        </dependency>
```

- [ ] **Step 3: Update daemon-boot-eventtranslator/pom.xml**

Replace `opennms-services` dependency with:

```xml
        <dependency>
            <groupId>org.opennms.features</groupId>
            <artifactId>org.opennms.features.event-translator</artifactId>
            <version>${project.version}</version>
        </dependency>
```

- [ ] **Step 4: Update daemon-boot-perspectivepollerd/pom.xml**

This module gets `opennms-services` transitively via `features/perspectivepoller`. After Task 9 updates perspectivepoller's dependency, this should resolve automatically. Verify by checking:

```bash
grep -r "opennms-services" core/daemon-boot-perspectivepollerd/pom.xml
```

If there's a direct dependency, replace it. If transitive only, it will be fixed when perspectivepoller is updated in Task 9.

- [ ] **Step 5: Update import statements for registry classes**

If any daemon-boot configuration classes import from `org.opennms.core.daemon.loader`, update to `org.opennms.core.daemon.common.registry`. Check with:

```bash
grep -r "org.opennms.core.daemon.loader" core/daemon-boot-*/src/ | grep -v target/
```

- [ ] **Step 6: Compile all three daemon-boot modules**

```bash
./compile.pl -DskipTests --projects :org.opennms.core.daemon-boot-pollerd,:org.opennms.core.daemon-boot-collectd,:org.opennms.core.daemon-boot-eventtranslator -am install
```

Expected: BUILD SUCCESS for all three.

- [ ] **Step 7: Commit**

```bash
git add core/daemon-boot-pollerd/pom.xml core/daemon-boot-collectd/pom.xml core/daemon-boot-eventtranslator/pom.xml core/daemon-boot-perspectivepollerd/pom.xml
git commit -m "refactor: update daemon-boot modules to use extracted feature modules

Replace opennms-services dependency with focused feature module
dependencies (poller.impl, collection.impl, event-translator)."
```

---

### Task 9: Break Remaining Dependents Free

**Files:**
- Modify: 11 pom.xml files (see below)

- [ ] **Step 1: Update test-only dependents (8 modules)**

For each of these modules, find the `opennms-services` dependency block and either replace or remove it:

**features/perspectivepoller/pom.xml** — Replace with poller.impl (test scope):
```xml
        <dependency>
            <groupId>org.opennms.features.poller</groupId>
            <artifactId>org.opennms.features.poller.impl</artifactId>
            <version>${project.version}</version>
            <scope>test</scope>
        </dependency>
```

**integrations/opennms-dns-provisioning-adapter/pom.xml** — Remove the opennms-services dependency entirely. The actual imports come from dao-api, model, and events-api which are already separate dependencies.

**integrations/opennms-geoip-provisioning-adapter/pom.xml** — Remove entirely (same reason).

**integrations/opennms-snmp-hardware-inventory-provisioning-adapter/pom.xml** — Remove entirely.

**integrations/opennms-snmp-metadata-provisioning-adapter/pom.xml** — Remove entirely.

**opennms-alarms/daemon/pom.xml** — Remove entirely. The alarm daemon classes are in its own module.

**opennms-correlation/drools-correlation-engine/pom.xml** — Remove entirely. Correlation classes are in the correlation module itself.

**protocols/xml/pom.xml** — Replace with collection.impl (test scope):
```xml
        <dependency>
            <groupId>org.opennms.features.collection</groupId>
            <artifactId>org.opennms.features.collection.impl</artifactId>
            <version>${project.version}</version>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 2: Update compile-time dependents (3 modules)**

**features/juniper-tca-collector/pom.xml** — Replace opennms-services with collection.impl:
```xml
        <dependency>
            <groupId>org.opennms.features.collection</groupId>
            <artifactId>org.opennms.features.collection.impl</artifactId>
            <version>${project.version}</version>
        </dependency>
```

**opennms-asterisk/pom.xml** — Replace opennms-services with poller.impl:
```xml
        <dependency>
            <groupId>org.opennms.features.poller</groupId>
            <artifactId>org.opennms.features.poller.impl</artifactId>
            <version>${project.version}</version>
        </dependency>
```

Note: opennms-asterisk also uses `AmiPeerFactory` and daemon base classes. Verify these come from `opennms-config` and `core/daemon` which should already be in its dependency tree.

**protocols/nsclient/pom.xml** — Replace opennms-services with both:
```xml
        <dependency>
            <groupId>org.opennms.features.collection</groupId>
            <artifactId>org.opennms.features.collection.impl</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.opennms.features.poller</groupId>
            <artifactId>org.opennms.features.poller.impl</artifactId>
            <version>${project.version}</version>
        </dependency>
```

- [ ] **Step 3: Verify no remaining references to opennms-services**

```bash
grep -r "opennms-services" --include="pom.xml" . | grep -v target/ | grep -v ".git/" | grep -v "opennms-services/pom.xml"
```

Expected: Zero hits (only opennms-services' own pom.xml should reference itself).

- [ ] **Step 4: Compile the updated modules**

```bash
./compile.pl -DskipTests --projects :org.opennms.features.perspectivepoller,:org.opennms.features.juniper-tca-collector,:opennms-asterisk,:nsclient -am install
```

For provisioning adapters:
```bash
./compile.pl -DskipTests --projects :opennms-dns-provisioning-adapter,:opennms-geoip-provisioning-adapter,:opennms-snmp-hardware-inventory-provisioning-adapter,:opennms-snmp-metadata-provisioning-adapter -am install
```

For alarms/correlation:
```bash
./compile.pl -DskipTests --projects :opennms-alarmd-daemon,:drools-correlation-engine -am install
```

For protocols:
```bash
./compile.pl -DskipTests --projects :org.opennms.protocols.xml,:nsclient -am install
```

Expected: BUILD SUCCESS for all.

- [ ] **Step 5: Compile check for removed modules**

Some modules may fail if they were importing classes from opennms-services that aren't in the new feature modules. Common causes:
- `MockEventIpcManager` — should be in `tests/mock-elements`
- `DatabasePopulator` — should be in `opennms-dao-mock`
- `AbstractServiceDaemon` — should be in `core/daemon`

If compilation fails, check which class is missing and add the correct dependency.

- [ ] **Step 6: Commit**

```bash
git add features/perspectivepoller/pom.xml features/juniper-tca-collector/pom.xml
git add integrations/*/pom.xml
git add opennms-alarms/daemon/pom.xml opennms-asterisk/pom.xml opennms-correlation/drools-correlation-engine/pom.xml
git add protocols/nsclient/pom.xml protocols/xml/pom.xml
git commit -m "refactor: break all remaining modules free from opennms-services

Replace opennms-services dependencies across 11 modules:
- 8 test-only: removed or replaced with focused feature modules
- 3 compile-time: replaced with poller.impl and collection.impl"
```

---

### Task 10: Delete opennms-services Module

**Files:**
- Delete: `opennms-services/` entire directory
- Modify: Root `pom.xml` — Remove module entry

- [ ] **Step 1: Remove opennms-services from root pom.xml**

In the root `pom.xml`, remove the line:
```xml
        <module>opennms-services</module>
```

- [ ] **Step 2: Delete the opennms-services directory**

```bash
rm -rf opennms-services
```

This deletes ~84 source files, ~88 test files, and all associated resources. The living classes (Pollerd, Collectd, EventTranslator) have already been moved. What remains is dead code:
- `org.opennms.netmgt.rtc` (22 classes) — RTCd deleted
- `org.opennms.netmgt.snmpinterfacepoller` (9 classes) — dead
- `org.opennms.netmgt.capsd` (1 class) — Capsd deleted years ago
- `org.opennms.netmgt.scriptd.helper` (12 classes) — Scriptd deleted (PR #55), helpers restored in PR #63
- `org.opennms.netmgt.provisiond.jmx` (2 classes) — Provisiond lives in features/provisioning
- `org.opennms.spring.xml` (1 class) — OnmsNamespaceHandler (Karaf-specific)
- Associated test classes for all of the above

- [ ] **Step 3: Check for scriptd helper references**

The scriptd helper classes were restored by PR #63 for syslog-northbounder. Verify they're now in a different location:

```bash
grep -r "org.opennms.netmgt.scriptd.helper" --include="*.java" --include="*.xml" . | grep -v target/ | grep -v ".git/" | grep -v opennms-services/
```

If any module still imports from `scriptd.helper`, those classes need to be moved to the consuming module (likely `opennms-alarms/syslog-northbounder`).

- [ ] **Step 4: Final grep for opennms-services references**

```bash
grep -r "opennms-services" --include="pom.xml" --include="*.xml" --include="*.java" --include="*.properties" . | grep -v target/ | grep -v ".git/"
```

Expected: Zero hits. Fix any remaining references.

- [ ] **Step 5: Commit**

```bash
git add -A opennms-services/ pom.xml
git commit -m "chore: delete opennms-services monolith

Remove the legacy monolith module. All living daemon implementations
have been extracted to focused feature modules:
- Pollerd → features/poller/impl
- Collectd → features/collection/impl
- EventTranslator → features/event-translator

~50 dead classes deleted without extraction (RTCd, Capsd,
SnmpInterfacePoller, Scriptd helpers)."
```

---

### Task 11: Full Build Verification

**Files:** None (verification only)

- [ ] **Step 1: Full reactor compile**

```bash
make build
```

Expected: BUILD SUCCESS. This compiles all modules with tests skipped.

If the build fails, the error will indicate which module and which missing class/dependency. Fix by:
1. Identifying the missing class
2. Determining which new module it now lives in (or which existing module already provides it)
3. Adding the correct `<dependency>` to the failing module's pom.xml

- [ ] **Step 2: Verify no split-package issues with opennms-model-jakarta**

```bash
grep -r "opennms-model-jakarta\|opennms-model" features/poller/impl/pom.xml features/collection/impl/pom.xml features/event-translator/pom.xml
```

Ensure only `opennms-model` (not `opennms-model-jakarta`) is a dependency. The Jakarta transform happens at the daemon-boot level via the jakarta-transform Maven profile.

- [ ] **Step 3: Build all 12 daemon-boot JARs**

```bash
./compile.pl -DskipTests --projects :org.opennms.core.daemon-boot-pollerd,:org.opennms.core.daemon-boot-collectd,:org.opennms.core.daemon-boot-eventtranslator,:org.opennms.core.daemon-boot-perspectivepollerd,:org.opennms.core.daemon-boot-alarmd,:org.opennms.core.daemon-boot-trapd,:org.opennms.core.daemon-boot-syslogd,:org.opennms.core.daemon-boot-discovery,:org.opennms.core.daemon-boot-provisiond,:org.opennms.core.daemon-boot-bsmd,:org.opennms.core.daemon-boot-telemetryd,:org.opennms.core.daemon-boot-enlinkd -am install
```

Expected: BUILD SUCCESS for all 12.

- [ ] **Step 4: Build Docker images**

```bash
./build.sh deltav
```

Expected: All daemon images build successfully.

- [ ] **Step 5: Run Docker health checks**

```bash
docker compose up -d
# Wait for startup
docker compose ps
```

Verify Pollerd, Collectd, and EventTranslator containers are healthy. These are the three daemons whose implementation was moved.

- [ ] **Step 6: Final verification — zero opennms-services references**

```bash
grep -r "opennms-services" --include="pom.xml" . | grep -v target/ | grep -v ".git/"
find . -path "*/daemon-loader-*" -not -path "*/target/*" -not -path "*/.git/*" | head -5
```

Expected: Zero hits on both.

- [ ] **Step 7: Commit any verification fixes**

If any fixes were needed during verification:
```bash
git add -A
git commit -m "fix: resolve build issues from opennms-services elimination"
```

---

### Task 12: Create Pull Request

**Files:** None

- [ ] **Step 1: Push branch and create PR**

```bash
git push -u origin chore/eliminate-opennms-services
gh pr create --repo pbrane/delta-v --base develop --title "chore: eliminate opennms-services monolith" --body "$(cat <<'EOF'
## Summary
- Delete all 15 daemon-loader modules (Karaf OSGi launchers — dead code since Spring Boot migration)
- Extract Pollerd implementation (31 classes) into `features/poller/impl`
- Extract Collectd implementation (12 classes) into `features/collection/impl`
- Extract EventTranslator (3 classes) into `features/event-translator`
- Extract registry classes into `core/daemon-common`
- Break 11 remaining modules free from opennms-services dependency
- Delete opennms-services module and ~50 classes of dead code (RTCd, Capsd, SnmpInterfacePoller, Scriptd)

## Test plan
- [ ] Full reactor compile passes (`make build`)
- [ ] All 12 daemon-boot JARs build successfully
- [ ] `./build.sh deltav` produces working Docker images
- [ ] Docker health checks pass for Pollerd, Collectd, EventTranslator
- [ ] Zero references to `opennms-services` remain in pom.xml files
- [ ] Zero `daemon-loader-*` directories remain
EOF
)"
```

**CRITICAL:** Uses `--repo pbrane/delta-v` — never against OpenNMS/opennms.
