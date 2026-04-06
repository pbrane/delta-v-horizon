# ==============================================================================
# OpenNMS Build Facade
#
# Usage:
#   make build                          Compile and install (tests skipped)
#   make module MODULE=:opennms-dao     Build one module + its dependencies
#   make dependents MODULE=:opennms-dao Build one module + modules that depend on it
#   make test-class MODULE=:opennms-dao TEST=SomeDaoTest   Run a single unit test
#   make test-class MODULE=:opennms-dao TEST=SomeDaoIT     Run a single integration test
#   make unit-tests                     Build and run all unit tests
#   make it-tests                       Build and run all integration tests
#   make all-test                       Build and run all tests (unit + integration)
#
# Delta-V fast-build targets (mvnd, parallel, skip flags):
#   make deltav                         Rebuild all Delta-V daemon boot JARs (no --also-make)
#   make deltav-full                    Rebuild Delta-V daemon boot JARs + all transitive deps
#   make deltav-daemon DAEMON=provisiond   Rebuild a single daemon boot JAR
#
# Overridable variables (set on command line or in environment):
#   MODULE           Maven module selector, e.g. :opennms-dao or groupId:artifactId
#   DAEMON           Delta-V daemon short name for deltav-daemon (e.g. provisiond, minion)
#   TEST             Test class name for test-class target (suffix IT = integration test)
#   MAVEN_FLAGS      Extra Maven flags (default: -DskipTests -B)
#   MAVEN_OPTS       JVM options for Maven (has a sensible default below)
#   DELTAV_THREADS   Parallel threads for Delta-V builds (default: 4, matches Apple Silicon P-cores)
# ==============================================================================

MODULE      ?=
DAEMON      ?=
TEST        ?=
MAVEN_FLAGS ?= -DskipTests -B
MAVEN_OPTS  ?= -Xmx3g \
               -XX:ReservedCodeCacheSize=512m \
               -XX:+TieredCompilation \
               -XX:TieredStopAtLevel=1 \
               -XX:-UseGCOverheadLimit \
               -XX:+UseParallelGC \
               -XX:-MaxFDLimit \
               -Djdk.util.zip.disableZip64ExtraFieldValidation=true \
               -Dmaven.wagon.http.retryHandler.count=3

MVN         := ./mvnw
MVND        := mvnd
COMMON      := --color=always \
               -Djava.awt.headless=true \
               -Daether.connector.resumeDownloads=false \
               -Daether.connector.basic.threads=1 \
               -Droot.dir=$(CURDIR)

# Delta-V fast-build configuration.
# -T 4 targets the 4 performance cores on Apple Silicon M-series (4P+6E).
# Override DELTAV_THREADS for other machines (e.g. DELTAV_THREADS=8 on x86 servers).
DELTAV_THREADS ?= 4
DELTAV_FLAGS   := -T $(DELTAV_THREADS) \
                  -DskipTests \
                  -DskipCheckstyle \
                  -DskipEnforcer \
                  -Dmaven.javadoc.skip=true

# The Delta-V daemon boot JARs and their shared support modules.
# No trailing comma. Keep alphabetical after the shared modules.
#
# NOTE: Keep this list in sync with .github/workflows/delta-v-build-images.yml
#       which has its own DAEMON_BOOTS variable listing the same modules.
#       If you add, remove, or rename a daemon-boot module, update BOTH.
#       (These lists are separate because CI keeps checkstyle/enforcer
#       enabled as quality gates — the dev targets below skip them for
#       speed.)
DELTAV_MODULES := :org.opennms.core.daemon-registry,:org.opennms.core.daemon-common,:org.opennms.core.daemon-boot-minion-common,:org.opennms.core.daemon-boot-alarmd,:org.opennms.core.daemon-boot-bsmd,:org.opennms.core.daemon-boot-collectd,:org.opennms.core.daemon-boot-discovery,:org.opennms.core.daemon-boot-enlinkd,:org.opennms.core.daemon-boot-eventtranslator,:org.opennms.core.daemon-boot-minion,:org.opennms.core.daemon-boot-perspectivepollerd,:org.opennms.core.daemon-boot-pollerd,:org.opennms.core.daemon-boot-provisiond,:org.opennms.core.daemon-boot-syslogd,:org.opennms.core.daemon-boot-telemetryd,:org.opennms.core.daemon-boot-trapd

export MAVEN_OPTS

.PHONY: help build module dependents test-class test ui clean deltav deltav-full deltav-daemon

.DEFAULT_GOAL := help

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*##' $(MAKEFILE_LIST) \
	  | awk 'BEGIN {FS = ":.*##"}; {printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2}'
	@echo ""
	@echo "Variables (override on command line):"
	@echo "  MODULE           Maven module selector (e.g. :opennms-dao)          (current: $(MODULE))"
	@echo "  DAEMON           Delta-V daemon short name (e.g. provisiond)        (current: $(DAEMON))"
	@echo "  TEST             Test class name (suffix IT = integration test)     (current: $(TEST))"
	@echo "  MAVEN_FLAGS      Extra Maven flags                                  (current: $(MAVEN_FLAGS))"
	@echo "  DELTAV_THREADS   Parallel threads for deltav targets                (current: $(DELTAV_THREADS))"
	@echo "  MAVEN_OPTS       JVM options passed to Maven"

build: ## Compile and package all modules (tests skipped)
	$(MVN) $(MAVEN_FLAGS) $(COMMON) \
	  -Dbuild.profile=default \
	  install

module: ## Build one module and its upstream dependencies; set MODULE=:artifactId
	@test -n "$(MODULE)" || (echo "ERROR: MODULE is required, e.g.: make module MODULE=:opennms-dao" && exit 1)
	$(MVN) $(MAVEN_FLAGS) $(COMMON) \
	  -Dbuild.profile=default \
	  --projects $(MODULE) \
	  --also-make \
	  install

dependents: ## Build one module and all modules that depend on it; set MODULE=:artifactId
	@test -n "$(MODULE)" || (echo "ERROR: MODULE is required, e.g.: make dependents MODULE=:opennms-dao" && exit 1)
	$(MVN) $(MAVEN_FLAGS) $(COMMON) \
	  -Dbuild.profile=default \
	  --projects $(MODULE) \
	  --also-make-dependents \
	  install

test-class: ## Run a single test class; set MODULE=:artifactId TEST=ClassName (suffix IT = integration test)
	@test -n "$(MODULE)" || (echo "ERROR: MODULE is required, e.g.: make test-class MODULE=:opennms-dao TEST=SomeDaoTest" && exit 1)
	@test -n "$(TEST)"   || (echo "ERROR: TEST is required, e.g.: make test-class MODULE=:opennms-dao TEST=SomeDaoTest" && exit 1)
	$(MVN) -B $(COMMON) \
	  -Dbuild.profile=default \
	  --projects $(MODULE) \
	  --also-make \
	  $(if $(filter %IT,$(TEST)),-Dit.test=$(TEST),-Dtest=$(TEST) -DskipTests=false) \
	  $(if $(filter %IT,$(TEST)),failsafe:integration-test failsafe:verify,install)

unit-tests: ## Build and run all unit tests
	$(MVN) -B $(COMMON) \
	  -Dbuild.profile=default \
	  -DskipTests=false \
	  -DskipITs=true \
	  verify

it-tests: ## Build and run all unit integration tests
	$(MVN) -B $(COMMON) \
	  -Dbuild.profile=default \
	  -DskipTests=true \
	  -DskipITs=false \
	  verify

all-tests: ## Build and run all tests (unit + integration)
	$(MVN) -B $(COMMON) \
	  -Dbuild.profile=default \
	  -DskipTests=false \
	  -DskipITs=false \
	  verify

ui: ## Install, build, and test the Vue UI
	cd ui && pnpm install && pnpm build && pnpm test

clean: ## Remove all build artifacts
	$(MVN) -B clean

# ==============================================================================
# Delta-V fast-build targets (mvnd + skip flags, targeted module lists)
# ==============================================================================

deltav: ## Fast rebuild of all Delta-V daemon boot JARs (mvnd, no -am)
	@command -v $(MVND) >/dev/null 2>&1 || (echo "ERROR: mvnd not found. Install with: brew install mvndaemon/homebrew-mvnd/mvnd" && exit 1)
	$(MVND) $(DELTAV_FLAGS) $(COMMON) \
	  -Dbuild.profile=default \
	  --projects $(DELTAV_MODULES) \
	  install

deltav-full: ## Rebuild Delta-V daemon boot JARs with all transitive deps (mvnd, --also-make)
	@command -v $(MVND) >/dev/null 2>&1 || (echo "ERROR: mvnd not found. Install with: brew install mvndaemon/homebrew-mvnd/mvnd" && exit 1)
	$(MVND) $(DELTAV_FLAGS) $(COMMON) \
	  -Dbuild.profile=default \
	  --projects $(DELTAV_MODULES) \
	  --also-make \
	  install

deltav-daemon: ## Rebuild a single Delta-V daemon boot JAR; set DAEMON=provisiond (etc)
	@test -n "$(DAEMON)" || (echo "ERROR: DAEMON is required, e.g.: make deltav-daemon DAEMON=provisiond" && exit 1)
	@command -v $(MVND) >/dev/null 2>&1 || (echo "ERROR: mvnd not found. Install with: brew install mvndaemon/homebrew-mvnd/mvnd" && exit 1)
	$(MVND) $(DELTAV_FLAGS) $(COMMON) \
	  -Dbuild.profile=default \
	  --projects :org.opennms.core.daemon-boot-$(DAEMON) \
	  install
