# SnmpPeerFactory Refactoring — Karaf Removal Phase 4

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Decouple SnmpPeerFactory from BeanUtils, JaxbUtils, and FileReloadContainer — replacing them with constructor-injected dependencies and Jackson XmlMapper — to unlock 5 daemon-boot modules (pollerd, collectd, enlinkd, perspectivepollerd, provisiond) from the opennms-config dependency.

**Architecture:** Add a new Spring-Boot-friendly constructor `SnmpPeerFactory(SnmpConfig, EntityScopeProvider, TextEncryptor)` that daemon-boot modules use after reading the XML with Jackson XmlMapper. Keep the `setInstance()` bridge so 8+ static callers in feature modules (SnmpMonitorStrategy, NodeCollector, etc.) continue working unchanged. Do NOT touch SnmpConfigManager or IP range matching logic.

**Tech Stack:** Jackson XmlMapper 2.19.4 with JaxbAnnotationModule (reads existing JAXB annotations), Spring Boot 4.0.3, Java 21.

---

## File Structure

| Action | File | Responsibility |
|--------|------|----------------|
| Modify | `opennms-config/pom.xml` | Add jackson-dataformat-xml + jackson-module-jaxb-annotations |
| Modify | `opennms-config/src/main/java/org/opennms/netmgt/config/SnmpPeerFactory.java` | New constructor, XmlMapper, remove BeanUtils/FileReloadContainer |
| Modify | `opennms-config/src/test/java/org/opennms/netmgt/config/SnmpPeerFactoryTest.java` | Update round-trip test from JaxbUtils to XmlMapper |
| Modify | `core/daemon-boot-enlinkd/src/main/java/.../EnlinkdDaemonConfiguration.java` | New snmpPeerFactory bean pattern |
| Modify | `core/daemon-boot-pollerd/src/main/java/.../PollerdDaemonConfiguration.java` | New snmpPeerFactory bean pattern |
| Modify | `core/daemon-boot-perspectivepollerd/src/main/java/.../PerspectivePollerdDaemonConfiguration.java` | New snmpPeerFactory bean pattern |
| Modify | `core/daemon-boot-collectd/src/main/java/.../CollectdDaemonConfiguration.java` | New snmpPeerFactory bean pattern |
| Modify | `core/daemon-boot-provisiond/src/main/java/.../ProvisiondBootConfiguration.java` | New snmpPeerFactory bean pattern, delete SnmpPeerFactoryInitializer ref |
| Delete | `core/daemon-boot-provisiond/src/main/java/.../SnmpPeerFactoryInitializer.java` | Obsolete wrapper |

---

### Task 1: Create feature branch

- [ ] **Step 1: Pull latest develop and create branch**

```bash
cd /Users/david/development/src/opennms/delta-v
git checkout develop && git pull
git checkout -b refactor/snmppeer-spring-boot
```

- [ ] **Step 2: Verify clean state**

```bash
git status
```

Expected: clean working tree on `refactor/snmppeer-spring-boot`.

---

### Task 2: Add Jackson XmlMapper dependencies to opennms-config

**Files:**
- Modify: `opennms-config/pom.xml`

- [ ] **Step 1: Add jackson-dataformat-xml and jackson-module-jaxb-annotations**

In `opennms-config/pom.xml`, add these two dependencies after the existing `commons-codec` dependency (around line 76). Versions are managed by the root POM — do not specify versions.

```xml
    <dependency>
      <groupId>com.fasterxml.jackson.dataformat</groupId>
      <artifactId>jackson-dataformat-xml</artifactId>
    </dependency>
    <dependency>
      <groupId>com.fasterxml.jackson.module</groupId>
      <artifactId>jackson-module-jaxb-annotations</artifactId>
    </dependency>
```

- [ ] **Step 2: Verify POM parses**

Run: `cd /Users/david/development/src/opennms/delta-v && ./compile.pl --projects :opennms-config -N validate`

Expected: BUILD SUCCESS

---

### Task 3: Refactor SnmpPeerFactory — new constructor + eliminate hidden deps

**Files:**
- Modify: `opennms-config/src/main/java/org/opennms/netmgt/config/SnmpPeerFactory.java`

This is the core task. We make five changes in one pass:
1. Add XmlMapper static field
2. Add new constructor + EntityScopeProvider instance field
3. Refactor Resource constructor (JaxbUtils→XmlMapper, remove FileReloadContainer)
4. Remove BeanUtils lookups (getSecureCredentialsScope, initializeTextEncryptor)
5. Replace JaxbUtils.marshal in getSnmpConfigAsString

- [ ] **Step 1: Replace imports**

Remove these imports:
```java
import org.apache.commons.io.IOUtils;
import org.opennms.core.spring.BeanUtils;
import org.opennms.core.spring.FileReloadCallback;
import org.opennms.core.spring.FileReloadContainer;
import org.opennms.core.xml.JaxbUtils;
```

Add these imports:
```java
import java.io.UncheckedIOException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.module.jaxb.JaxbAnnotationModule;
```

- [ ] **Step 2: Add XmlMapper static field and EntityScopeProvider instance field**

After the `ENCRYPTION_ENABLED` constant (line 94), add:

```java
    private static final XmlMapper XML_MAPPER;
    static {
        XML_MAPPER = new XmlMapper();
        XML_MAPPER.registerModule(new JaxbAnnotationModule());
        XML_MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
```

Replace these three field declarations:
```java
    private FileReloadContainer<SnmpConfig> m_container;
    private FileReloadCallback<SnmpConfig> m_callback;
    private TextEncryptor textEncryptor;
```

With:
```java
    private TextEncryptor textEncryptor;
    private EntityScopeProvider entityScopeProvider;
```

- [ ] **Step 3: Add new Spring Boot constructor**

Add this constructor immediately after the existing `Resource` constructor:

```java
    /**
     * Spring Boot constructor. Config is pre-loaded by the daemon-boot module
     * using Jackson XmlMapper. Dependencies are explicitly wired — no BeanUtils.
     *
     * @param config              pre-parsed SnmpConfig
     * @param entityScopeProvider for MATE metadata interpolation of SNMP credentials (nullable)
     * @param textEncryptor       for SNMP credential encryption (nullable, only used when
     *                            system property org.opennms.snmp.encryption.enabled=true)
     */
    public SnmpPeerFactory(SnmpConfig config, EntityScopeProvider entityScopeProvider, TextEncryptor textEncryptor) {
        LOG.debug("creating new instance from pre-loaded SnmpConfig: {}", this);
        m_config = config;
        this.entityScopeProvider = entityScopeProvider;
        this.textEncryptor = textEncryptor;
    }
```

- [ ] **Step 4: Refactor existing Resource constructor**

Replace the entire `public SnmpPeerFactory(final Resource resource)` constructor body with:

```java
    public SnmpPeerFactory(final Resource resource) {
        LOG.debug("creating new instance for resource {}: {}", resource, this);
        try {
            m_config = XML_MAPPER.readValue(resource.getInputStream(), SnmpConfig.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load SnmpConfig from " + resource, e);
        }
    }
```

This removes FileReloadContainer setup and replaces JaxbUtils.unmarshal with XmlMapper.readValue.

- [ ] **Step 5: Refactor getSecureCredentialsScope — remove BeanUtils**

Replace the entire `getSecureCredentialsScope()` method with:

```java
    private Scope getSecureCredentialsScope() {
        if (secureCredentialsVaultScope == null && entityScopeProvider != null) {
            secureCredentialsVaultScope = entityScopeProvider.getScopeForScv();
        }
        return secureCredentialsVaultScope;
    }
```

This is now an instance method (not static) that uses the constructor-injected `entityScopeProvider` field. The static `secureCredentialsVaultScope` field remains for backward compat with `setSecureCredentialsVaultScope()` (used by tests).

- [ ] **Step 6: Remove initializeTextEncryptor — eliminate BeanUtils**

Delete the `initializeTextEncryptor()` method entirely (lines 773–781):

```java
    // DELETE THIS ENTIRE METHOD:
    private void initializeTextEncryptor() {
        if (textEncryptor == null) {
            try {
                textEncryptor = BeanUtils.getBean("daoContext", "textEncryptor", TextEncryptor.class);
            } catch (Exception e) {
                LOG.warn("Exception while trying to get textEncryptor", e);
            }
        }
    }
```

Remove the `initializeTextEncryptor()` call from `encryptSnmpConfig()` (the private no-arg one):

```java
    private void encryptSnmpConfig() {
        if (textEncryptor != null) {
            try {
                s_singleton.saveCurrent();
            } catch (IOException e) {
                LOG.debug("Exception while saving encrypted credentials");
            }
        }
    }
```

Remove the `initializeTextEncryptor()` call from `encryptSnmpConfig(SnmpConfig)`:

```java
    private void encryptSnmpConfig(SnmpConfig snmpConfig) {
        if (!encryptionEnabled || textEncryptor == null) {
            return;
        }
        encryptConfig(snmpConfig);
        snmpConfig.getDefinitions().forEach(this::encryptConfig);
        if (snmpConfig.getSnmpProfiles() != null) {
            snmpConfig.getSnmpProfiles().getSnmpProfiles().forEach(this::encryptConfig);
        }
    }
```

Remove the `initializeTextEncryptor()` call from `decryptSnmpConfig(SnmpConfig)`:

```java
    private void decryptSnmpConfig(SnmpConfig snmpConfig) {
        if (!encryptionEnabled || textEncryptor == null) {
            return;
        }
        decryptConfig(snmpConfig);
        snmpConfig.getDefinitions().forEach(this::decryptConfig);
        if (snmpConfig.getSnmpProfiles() != null) {
            snmpConfig.getSnmpProfiles().getSnmpProfiles().forEach(this::decryptConfig);
        }
    }
```

- [ ] **Step 7: Replace JaxbUtils.marshal in getSnmpConfigAsString**

Replace the entire `getSnmpConfigAsString()` method with:

```java
    public String getSnmpConfigAsString() {
        SnmpConfig snmpConfig = getSnmpConfig();
        encryptSnmpConfig(snmpConfig);
        try {
            return XML_MAPPER.writeValueAsString(snmpConfig);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize SnmpConfig to XML", e);
        }
    }
```

- [ ] **Step 8: Simplify getSnmpConfig — remove FileReloadContainer branch**

Replace the `getSnmpConfig()` method body with:

```java
    @Override
    public SnmpConfig getSnmpConfig() {
        getReadLock().lock();
        try {
            decryptSnmpConfig(m_config);
            return m_config;
        } finally {
            getReadLock().unlock();
        }
    }
```

- [ ] **Step 9: Simplify saveToFile — remove FileReloadContainer reload**

Replace the `saveToFile()` method with:

```java
    public void saveToFile(final File file) throws UnsupportedEncodingException, FileNotFoundException, IOException {
        getWriteLock().lock();
        try {
            final String marshalledConfig = getSnmpConfigAsString();
            if (marshalledConfig != null) {
                try (var out = new FileOutputStream(file);
                     var writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
                    writer.write(marshalledConfig);
                    writer.flush();
                }
            }
        } finally {
            getWriteLock().unlock();
        }
    }
```

- [ ] **Step 10: Verify compilation**

Run: `cd /Users/david/development/src/opennms/delta-v && ./compile.pl -DskipTests --projects :opennms-config install`

Expected: BUILD SUCCESS with no compilation errors.

---

### Task 4: Update SnmpPeerFactoryTest

**Files:**
- Modify: `opennms-config/src/test/java/org/opennms/netmgt/config/SnmpPeerFactoryTest.java`

- [ ] **Step 1: Replace JaxbUtils import with XmlMapper**

Remove this import:
```java
import org.opennms.core.xml.JaxbUtils;
```

Add these imports:
```java
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.module.jaxb.JaxbAnnotationModule;
```

- [ ] **Step 2: Add XmlMapper field to test class**

Add after the `private int m_version;` field:

```java
    private static final XmlMapper XML_MAPPER;
    static {
        XML_MAPPER = new XmlMapper();
        XML_MAPPER.registerModule(new JaxbAnnotationModule());
        XML_MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
```

- [ ] **Step 3: Update testMergingWithMetadata**

In the `testMergingWithMetadata()` method, replace both `JaxbUtils.unmarshal` calls:

Replace:
```java
            final SnmpConfig snmpConfig1 = JaxbUtils.unmarshal(SnmpConfig.class, snmpPeerFactory.getSnmpConfigAsString());
```
With:
```java
            final SnmpConfig snmpConfig1 = XML_MAPPER.readValue(snmpPeerFactory.getSnmpConfigAsString(), SnmpConfig.class);
```

Replace:
```java
            final SnmpConfig snmpConfig2 = JaxbUtils.unmarshal(SnmpConfig.class, snmpPeerFactory.getSnmpConfigAsString());
```
With:
```java
            final SnmpConfig snmpConfig2 = XML_MAPPER.readValue(snmpPeerFactory.getSnmpConfigAsString(), SnmpConfig.class);
```

- [ ] **Step 4: Run the test**

Run: `cd /Users/david/development/src/opennms/delta-v && ./compile.pl --projects :opennms-config test -Dtest=SnmpPeerFactoryTest`

Expected: All tests PASS. The test exercises: Resource constructor (ByteArrayResource path), SCV interpolation (via setSecureCredentialsVaultScope), profile handling, range matching, location matching, and config round-trip (write via XmlMapper → read via XmlMapper).

- [ ] **Step 5: Commit**

```bash
git add opennms-config/pom.xml \
  opennms-config/src/main/java/org/opennms/netmgt/config/SnmpPeerFactory.java \
  opennms-config/src/test/java/org/opennms/netmgt/config/SnmpPeerFactoryTest.java
git commit -m "$(cat <<'EOF'
refactor: replace BeanUtils/JaxbUtils/FileReloadContainer in SnmpPeerFactory with Jackson XmlMapper

Add new Spring Boot constructor SnmpPeerFactory(SnmpConfig, EntityScopeProvider,
TextEncryptor) that daemon-boot modules will use. Existing Resource constructor
refactored to use XmlMapper instead of JaxbUtils for consistency. FileReloadContainer
removed — container deployments use config overlays and restart to reload.

BeanUtils.getBean() calls for EntityScopeProvider and TextEncryptor eliminated —
both are now constructor-injected. This removes the last hidden static lookups
from SnmpPeerFactory.

Part of Karaf removal Phase 4: SnmpPeerFactory decoupling unlocks pollerd, collectd,
enlinkd, perspectivepollerd, and provisiond.
EOF
)"
```

---

### Task 5: Update daemon-boot-enlinkd (simplest — only SnmpPeerFactory dep on opennms-config)

**Files:**
- Modify: `core/daemon-boot-enlinkd/src/main/java/org/opennms/netmgt/enlinkd/boot/EnlinkdDaemonConfiguration.java`

This is the simplest daemon-boot to update because Enlinkd's ONLY dependency on opennms-config is SnmpPeerFactory.

- [ ] **Step 1: Add imports**

Add these imports (replace the existing `SnmpPeerFactory` import):
```java
import java.io.File;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.module.jaxb.JaxbAnnotationModule;
import org.opennms.core.mate.api.EntityScopeProvider;
import org.opennms.netmgt.config.SnmpPeerFactory;
import org.opennms.netmgt.config.api.SnmpAgentConfigFactory;
import org.opennms.netmgt.config.snmp.SnmpConfig;
import org.springframework.lang.Nullable;
```

- [ ] **Step 2: Add XmlMapper static field**

Add after the `LOG` field:
```java
    private static final XmlMapper XML_MAPPER;
    static {
        XML_MAPPER = new XmlMapper();
        XML_MAPPER.registerModule(new JaxbAnnotationModule());
        XML_MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
```

- [ ] **Step 3: Replace snmpPeerFactory bean**

Replace the existing `snmpPeerFactory()` method with:

```java
    @Bean
    public SnmpAgentConfigFactory snmpPeerFactory(
            @Value("${opennms.home:/opt/deltav}") String opennmsHome,
            EntityScopeProvider entityScopeProvider) throws IOException {
        var configFile = new File(opennmsHome, "etc/snmp-config.xml");
        LOG.info("Loading SnmpPeerFactory from {}", configFile);
        var config = XML_MAPPER.readValue(configFile, SnmpConfig.class);
        var factory = new SnmpPeerFactory(config, entityScopeProvider, null);
        SnmpPeerFactory.setInstance(factory);
        return factory;
    }
```

Note: `opennmsHome` was already a `@Value` field in this class — reuse it if preferred, or use inline `@Value` on the parameter. TextEncryptor is null because no daemon currently uses SNMP encryption.

- [ ] **Step 4: Remove the now-unused `@Value opennmsHome` field if inlined, or keep if shared**

The existing class has `@Value("${opennms.home:/opt/deltav}") private String opennmsHome;` used by `linkdConfig()`. Keep it. Use it in the new bean method instead of adding an inline `@Value`:

```java
    @Bean
    public SnmpAgentConfigFactory snmpPeerFactory(EntityScopeProvider entityScopeProvider) throws IOException {
        var configFile = new File(opennmsHome, "etc/snmp-config.xml");
        LOG.info("Loading SnmpPeerFactory from {}", configFile);
        var config = XML_MAPPER.readValue(configFile, SnmpConfig.class);
        var factory = new SnmpPeerFactory(config, entityScopeProvider, null);
        SnmpPeerFactory.setInstance(factory);
        return factory;
    }
```

- [ ] **Step 5: Verify compilation**

Run: `cd /Users/david/development/src/opennms/delta-v && ./compile.pl -DskipTests --projects :daemon-boot-enlinkd install`

Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add core/daemon-boot-enlinkd/src/main/java/org/opennms/netmgt/enlinkd/boot/EnlinkdDaemonConfiguration.java
git commit -m "$(cat <<'EOF'
refactor: Enlinkd boot uses Jackson XmlMapper + new SnmpPeerFactory constructor

Replace SnmpPeerFactory.init()/getInstance() with explicit XmlMapper config
loading and new constructor injection of EntityScopeProvider. Static
setInstance() bridge maintained for feature module callers.

Part of Karaf removal Phase 4.
EOF
)"
```

---

### Task 6: Update daemon-boot-pollerd

**Files:**
- Modify: `core/daemon-boot-pollerd/src/main/java/org/opennms/netmgt/poller/boot/PollerdDaemonConfiguration.java`

- [ ] **Step 1: Add imports**

Add these imports:
```java
import java.io.File;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.module.jaxb.JaxbAnnotationModule;
import org.opennms.core.mate.api.EntityScopeProvider;
import org.opennms.netmgt.config.api.SnmpAgentConfigFactory;
import org.opennms.netmgt.config.snmp.SnmpConfig;
```

- [ ] **Step 2: Add XmlMapper static field and opennmsHome @Value**

After the `LOG` field, add:
```java
    private static final XmlMapper XML_MAPPER;
    static {
        XML_MAPPER = new XmlMapper();
        XML_MAPPER.registerModule(new JaxbAnnotationModule());
        XML_MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Value("${opennms.home:/opt/deltav}")
    private String opennmsHome;
```

- [ ] **Step 3: Replace snmpPeerFactory bean**

Replace:
```java
    @Bean
    public SnmpPeerFactory snmpPeerFactory() throws IOException {
        LOG.info("Initializing SnmpPeerFactory");
        SnmpPeerFactory.init();
        return SnmpPeerFactory.getInstance();
    }
```

With:
```java
    @Bean
    public SnmpAgentConfigFactory snmpPeerFactory(EntityScopeProvider entityScopeProvider) throws IOException {
        var configFile = new File(opennmsHome, "etc/snmp-config.xml");
        LOG.info("Loading SnmpPeerFactory from {}", configFile);
        var config = XML_MAPPER.readValue(configFile, SnmpConfig.class);
        var factory = new SnmpPeerFactory(config, entityScopeProvider, null);
        SnmpPeerFactory.setInstance(factory);
        return factory;
    }
```

- [ ] **Step 4: Update import of SnmpPeerFactory**

Keep the existing `import org.opennms.netmgt.config.SnmpPeerFactory;` — still needed for `new SnmpPeerFactory(...)` and `SnmpPeerFactory.setInstance(...)`.

- [ ] **Step 5: Verify compilation**

Run: `cd /Users/david/development/src/opennms/delta-v && ./compile.pl -DskipTests --projects :daemon-boot-pollerd install`

Expected: BUILD SUCCESS

---

### Task 7: Update daemon-boot-perspectivepollerd

**Files:**
- Modify: `core/daemon-boot-perspectivepollerd/src/main/java/org/opennms/netmgt/perspectivepoller/boot/PerspectivePollerdDaemonConfiguration.java`

- [ ] **Step 1: Add imports**

Add:
```java
import java.io.File;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.module.jaxb.JaxbAnnotationModule;
import org.opennms.core.mate.api.EntityScopeProvider;
import org.opennms.netmgt.config.api.SnmpAgentConfigFactory;
import org.opennms.netmgt.config.snmp.SnmpConfig;
import org.springframework.beans.factory.annotation.Value;
```

- [ ] **Step 2: Add XmlMapper static field and opennmsHome @Value**

After the `LOG` field:
```java
    private static final XmlMapper XML_MAPPER;
    static {
        XML_MAPPER = new XmlMapper();
        XML_MAPPER.registerModule(new JaxbAnnotationModule());
        XML_MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Value("${opennms.home:/opt/deltav}")
    private String opennmsHome;
```

- [ ] **Step 3: Replace snmpPeerFactory bean**

Replace:
```java
    public SnmpPeerFactory snmpPeerFactory() throws IOException {
        LOG.info("Initializing SnmpPeerFactory");
        SnmpPeerFactory.init();
        return SnmpPeerFactory.getInstance();
    }
```

With:
```java
    public SnmpAgentConfigFactory snmpPeerFactory(EntityScopeProvider entityScopeProvider) throws IOException {
        var configFile = new File(opennmsHome, "etc/snmp-config.xml");
        LOG.info("Loading SnmpPeerFactory from {}", configFile);
        var config = XML_MAPPER.readValue(configFile, SnmpConfig.class);
        var factory = new SnmpPeerFactory(config, entityScopeProvider, null);
        SnmpPeerFactory.setInstance(factory);
        return factory;
    }
```

- [ ] **Step 4: Verify compilation**

Run: `cd /Users/david/development/src/opennms/delta-v && ./compile.pl -DskipTests --projects :daemon-boot-perspectivepollerd install`

Expected: BUILD SUCCESS

---

### Task 8: Update daemon-boot-collectd

**Files:**
- Modify: `core/daemon-boot-collectd/src/main/java/org/opennms/netmgt/collectd/boot/CollectdDaemonConfiguration.java`

- [ ] **Step 1: Add imports**

Add:
```java
import java.io.File;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.module.jaxb.JaxbAnnotationModule;
import org.opennms.core.mate.api.EntityScopeProvider;
import org.opennms.netmgt.config.api.SnmpAgentConfigFactory;
import org.opennms.netmgt.config.snmp.SnmpConfig;
```

- [ ] **Step 2: Add XmlMapper static field**

Check if `CollectdDaemonConfiguration` already has an `opennmsHome` field. If not, add it along with XmlMapper:

```java
    private static final XmlMapper XML_MAPPER;
    static {
        XML_MAPPER = new XmlMapper();
        XML_MAPPER.registerModule(new JaxbAnnotationModule());
        XML_MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Value("${opennms.home:/opt/deltav}")
    private String opennmsHome;
```

- [ ] **Step 3: Replace snmpPeerFactory bean**

Replace:
```java
    public SnmpPeerFactory snmpPeerFactory() throws Exception {
        LOG.info("Initializing SnmpPeerFactory");
        SnmpPeerFactory.init();
        return SnmpPeerFactory.getInstance();
    }
```

With:
```java
    public SnmpAgentConfigFactory snmpPeerFactory(EntityScopeProvider entityScopeProvider) throws IOException {
        var configFile = new File(opennmsHome, "etc/snmp-config.xml");
        LOG.info("Loading SnmpPeerFactory from {}", configFile);
        var config = XML_MAPPER.readValue(configFile, SnmpConfig.class);
        var factory = new SnmpPeerFactory(config, entityScopeProvider, null);
        SnmpPeerFactory.setInstance(factory);
        return factory;
    }
```

- [ ] **Step 4: Verify compilation**

Run: `cd /Users/david/development/src/opennms/delta-v && ./compile.pl -DskipTests --projects :daemon-boot-collectd install`

Expected: BUILD SUCCESS

---

### Task 9: Update daemon-boot-provisiond + delete SnmpPeerFactoryInitializer

**Files:**
- Modify: `core/daemon-boot-provisiond/src/main/java/org/opennms/netmgt/provision/boot/ProvisiondBootConfiguration.java`
- Delete: `core/daemon-boot-provisiond/src/main/java/org/opennms/netmgt/provision/boot/SnmpPeerFactoryInitializer.java`

- [ ] **Step 1: Add imports to ProvisiondBootConfiguration**

Add:
```java
import java.io.File;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.module.jaxb.JaxbAnnotationModule;
import org.opennms.core.mate.api.EntityScopeProvider;
import org.opennms.netmgt.config.SnmpPeerFactory;
import org.opennms.netmgt.config.snmp.SnmpConfig;
```

Remove this import (no longer needed):
```java
import org.opennms.netmgt.config.SnmpPeerFactory;  // check — may still be needed for setInstance
```

Wait — `SnmpPeerFactory` is still needed for `new SnmpPeerFactory(...)` and `setInstance()`. Keep it.

- [ ] **Step 2: Add XmlMapper static field**

After the `opennmsHome` field (which already exists), add:
```java
    private static final XmlMapper XML_MAPPER;
    static {
        XML_MAPPER = new XmlMapper();
        XML_MAPPER.registerModule(new JaxbAnnotationModule());
        XML_MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
```

- [ ] **Step 3: Replace Section 3 (SNMP Config) beans**

Replace:
```java
    @Bean
    public SnmpPeerFactoryInitializer snmpPeerFactoryInitializer() {
        return new SnmpPeerFactoryInitializer();
    }

    @Bean
    public SnmpAgentConfigFactory snmpPeerFactory(SnmpPeerFactoryInitializer init) {
        return init.getInstance();
    }
```

With:
```java
    @Bean
    public SnmpAgentConfigFactory snmpPeerFactory(EntityScopeProvider entityScopeProvider) throws IOException {
        var configFile = new File(opennmsHome, "etc/snmp-config.xml");
        LOG.info("Loading SnmpPeerFactory from {}", configFile);
        var config = XML_MAPPER.readValue(configFile, SnmpConfig.class);
        var factory = new SnmpPeerFactory(config, entityScopeProvider, null);
        SnmpPeerFactory.setInstance(factory);
        return factory;
    }
```

Add LOG import if not present:
```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
```

And add the LOG field if not present:
```java
    private static final Logger LOG = LoggerFactory.getLogger(ProvisiondBootConfiguration.class);
```

- [ ] **Step 4: Remove SnmpPeerFactoryInitializer import**

Remove:
```java
import org.opennms.netmgt.config.SnmpPeerFactory;  // Keep this — still used
```

Actually, remove the unused import for `SnmpPeerFactoryInitializer` — there should be no explicit import since it's in the same package. Just verify no reference to `SnmpPeerFactoryInitializer` remains.

- [ ] **Step 5: Delete SnmpPeerFactoryInitializer**

```bash
rm core/daemon-boot-provisiond/src/main/java/org/opennms/netmgt/provision/boot/SnmpPeerFactoryInitializer.java
```

- [ ] **Step 6: Verify compilation**

Run: `cd /Users/david/development/src/opennms/delta-v && ./compile.pl -DskipTests --projects :daemon-boot-provisiond install`

Expected: BUILD SUCCESS

- [ ] **Step 7: Commit all daemon-boot changes**

```bash
git add \
  core/daemon-boot-enlinkd/src/main/java/org/opennms/netmgt/enlinkd/boot/EnlinkdDaemonConfiguration.java \
  core/daemon-boot-pollerd/src/main/java/org/opennms/netmgt/poller/boot/PollerdDaemonConfiguration.java \
  core/daemon-boot-perspectivepollerd/src/main/java/org/opennms/netmgt/perspectivepoller/boot/PerspectivePollerdDaemonConfiguration.java \
  core/daemon-boot-collectd/src/main/java/org/opennms/netmgt/collectd/boot/CollectdDaemonConfiguration.java \
  core/daemon-boot-provisiond/src/main/java/org/opennms/netmgt/provision/boot/ProvisiondBootConfiguration.java
git rm core/daemon-boot-provisiond/src/main/java/org/opennms/netmgt/provision/boot/SnmpPeerFactoryInitializer.java
git commit -m "$(cat <<'EOF'
refactor: all 5 daemon-boot modules use Jackson XmlMapper for SnmpPeerFactory

Replace SnmpPeerFactory.init()/getInstance() pattern with explicit Jackson
XmlMapper config loading and new SnmpPeerFactory(SnmpConfig, EntityScopeProvider,
TextEncryptor) constructor in: pollerd, collectd, enlinkd, perspectivepollerd,
provisiond.

Delete SnmpPeerFactoryInitializer — no longer needed.

SnmpPeerFactory.setInstance() bridge maintained for 8 static callers in feature
modules (SnmpMonitorStrategy, NodeCollector, DefaultSnmpCollectionAgent, etc.).

Part of Karaf removal Phase 4.
EOF
)"
```

---

### Task 10: Full build verification

- [ ] **Step 1: Build all modified modules together**

Run:
```bash
cd /Users/david/development/src/opennms/delta-v
./compile.pl -DskipTests --projects \
  :opennms-config,\
  :daemon-boot-enlinkd,\
  :daemon-boot-pollerd,\
  :daemon-boot-perspectivepollerd,\
  :daemon-boot-collectd,\
  :daemon-boot-provisiond \
  -am install
```

Expected: BUILD SUCCESS

- [ ] **Step 2: Run SnmpPeerFactoryTest**

Run:
```bash
cd /Users/david/development/src/opennms/delta-v
./compile.pl --projects :opennms-config test -Dtest=SnmpPeerFactoryTest
```

Expected: All tests PASS

- [ ] **Step 3: Rebuild all daemon boot JARs**

Per the project rules, always rebuild ALL daemon boot JARs:

Run: `make build` (or the daemon rebuild target)

Expected: BUILD SUCCESS

---

### Task 11: Verify Enlinkd is free of opennms-config (stretch goal)

After this refactoring, Enlinkd's only dependency on `opennms-config` was `SnmpPeerFactory`. Since the bean now uses the constructor directly, check if `opennms-config` can be removed from `daemon-boot-enlinkd/pom.xml` entirely.

- [ ] **Step 1: Check remaining opennms-config references in Enlinkd boot**

Search for any remaining `import org.opennms.netmgt.config.*` in Enlinkd daemon boot files. The only reference should be `SnmpPeerFactory` and `EnhancedLinkdConfigFactory`.

`EnhancedLinkdConfigFactory` is in `opennms-config`. If it's still used, `opennms-config` dep must stay. Otherwise, this task is deferred.

- [ ] **Step 2: Evaluate and document**

If `EnhancedLinkdConfigFactory` is the only remaining reference, note it for a future refactoring session. Do NOT attempt to decouple it in this PR.

---
