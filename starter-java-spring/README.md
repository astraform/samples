# Astraform Domain Provider Starter for Java and Spring

A minimal Spring Boot provider implementing `remote-domain.v1` through the
[Astraform SDK for Java](https://github.com/astraform/sdk-java). Start here when
building a new domain's lifecycle and population catalog. For complete banking
or marketing simulations, use the other [sample services](../README.md#sample-services).

This provider is sample source, built independently of the SDK repository. It
uses Java 21+, Maven 3.9+, Spring Boot 4.1.1 and these dependencies:

- `ai.astraform:sdk-java:0.4.5` at runtime, including the generated platform client.
- `ai.astraform:conformance-java:0.4.5` in test scope.

Both SDK packages are published on Maven Central and target published contracts
1.2.3. No SDK source checkout or local SDK build is required; see
[source dependency setup](../README.md#sdk-development). Published SDK 0.4.4
retains its original coordinates and contracts 1.2.2.

Copy this folder to your own service repository and keep the SDK dependencies.
Its Maven parent is Spring Boot's public starter parent; it does not use an
Astraform source parent, build scripts or platform modules. Spring hosting and
test dependencies belong to your service. The sample is distributed with samples
source, without a separate Maven Central template artifact, Docker image or
prebuilt deployment archive. Previously released template artifacts remain
available under their original coordinates and versions.

## Build and run

From the samples repository root:

```bash
mvn -B -ntp -f starter-java-spring/pom.xml verify
java -jar starter-java-spring/target/starter-java-spring.jar
```

The default port is `8093`. Inspect service health and the provider's public
metadata:

```text
GET http://localhost:8093/actuator/health
GET http://localhost:8093/api/remote-domain/v1/manifest
GET http://localhost:8093/api/remote-domain/v1/population/catalog
```

The SDK controller adapter hosts the provider lifecycle routes. The example
service owns preparation, work execution, status, inspection, shutdown and opaque
state. It does not start Astraform or launch a customer simulation. Configure a
separately installed platform to reach this provider before launching a domain
scenario; see [deployment and ownership](../docs/PLATFORM_PACKAGING.md).

For customer configuration and launch operations, use the
[SDK platform client](https://github.com/astraform/sdk-java/tree/main/sdk-java#create-publish-discover-and-launch)
from the same runtime dependency.

## Population catalog

The catalog is file-backed at:

```text
src/main/resources/population-catalogs/acme-remote.population-catalog.json
```

`AcmeRemoteDomainService.populationCatalog()` loads it with
`PopulationCatalogs.fromClasspath(...)`. The SDK validates the JSON before the
provider publishes it. Invalid archetypes, segment templates, run-path
eligibility or numeric fields are rejected at the provider boundary.

Run the catalog check without starting Spring:

```bash
mvn -B -ntp -f starter-java-spring/pom.xml compile exec:java -DskipTests
```

The same command works inside this folder with `-f starter-java-spring/pom.xml`
omitted. To run only the direct catalog-file test:

```bash
mvn -B -ntp -f starter-java-spring/pom.xml -Dtest=AcmePopulationCatalogFileTest test
```

## Adapt your provider

1. Replace `DOMAIN_ID` and manifest metadata with your domain identity.
2. Replace the catalog JSON with your archetypes, recipes, member templates,
   validation rules and run-path eligibility.
3. Replace `AcmeRemoteDomainService` lifecycle logic with your business logic.
   Keep state opaque and versioned, and keep platform hosting concerns outside
   the domain service.
4. Run `mvn verify` against your provider. Its service-local conformance test
   drives lifecycle operations and the catalog route through HTTP using MockMvc.

The example records work IDs with insertion-ordered set membership. New IDs
count once; repeats return replay evidence without applying another effect. A
replay-only batch preserves the supplied checkpoint. The example does not cap
replay history: a production domain must choose durable state and retention
appropriate to its workload.

Add only the public-contract capabilities your service needs, such as system
work windows, Policy Wind Tunnel endpoints, outcome scorecards or evidence for
provider-owned external calls. The provider must not import Astraform runtime
modules. If a capability requires host internals, address the public contract or
SDK gap rather than coupling the service to the platform implementation.
