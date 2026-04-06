# Delta-V Horizon

Frozen snapshot of [OpenNMS Horizon](https://github.com/OpenNMS/opennms) 36 modules, published as Maven artifacts for the [Delta-V](https://github.com/pbrane/delta-v) project.

## Lineage

This repository is a **hard fork** of OpenNMS Horizon, snapshotted on 2026-04-05 from OpenNMS 36.0.0-SNAPSHOT. It is owned and maintained entirely by the Delta-V project. There is no upstream sync — fixes are reimplemented as new Delta-V commits when relevant.

See [NOTICE.md](NOTICE.md) for trademark attribution.

## Versioning

Delta-V Horizon uses its own semver scheme:

| Change type | Bump | Example |
|---|---|---|
| Bugfix, no API change | Patch | `1.0.0` → `1.0.1` |
| New API, backward compatible | Minor | `1.0.0` → `1.1.0` |
| Module pruning, API removal | Major | `1.0.0` → `2.0.0` |

## Consuming Artifacts

### 1. Create a GitHub PAT

Generate a [Personal Access Token](https://github.com/settings/tokens) with `read:packages` scope.

### 2. Configure Maven

Add to `~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>github</id>
      <username>YOUR_GITHUB_USERNAME</username>
      <password>YOUR_PAT</password>
    </server>
  </servers>
</settings>
```

### 3. Add Repository

In your project's `pom.xml`:

```xml
<repositories>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/pbrane/delta-v-horizon</url>
  </repository>
</repositories>
```

### 4. Declare Dependencies

```xml
<properties>
  <deltav.horizon.version>1.0.0</deltav.horizon.version>
</properties>

<dependency>
  <groupId>org.opennms</groupId>
  <artifactId>opennms-dao</artifactId>
  <version>${deltav.horizon.version}</version>
</dependency>
```

## Building from Source

```bash
./mvnw -DskipTests install
```

Requires JDK 17.

## Reserved Capabilities

These modules are included even though no current Delta-V daemon-boot references them. They provide capabilities Delta-V expects to adopt:

| Module | Reserved for |
|---|---|
| `features/telemetry` | Telemetryd adapter/listener migration |
| `features/graph` | Topology Graph API |
| `features/distributed` | ALEC / plugin registration |
| `features/bsm` | Business Service Monitor |

## License

AGPL v3 — inherited from OpenNMS Horizon. See [LICENSE.md](LICENSE.md).
