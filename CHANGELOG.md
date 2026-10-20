# Changelog

All examples share one samples repository version. Platform and SDK releases are
versioned separately. Detailed notes live under [docs/releases](docs/releases).

## Unreleased

- Move the generic Java/Spring provider starter into `starter-java-spring`, with
  a standalone Spring Boot build, lifecycle/catalog conformance and work replay
  checks. Distribute it as sample source instead of an SDK Maven artifact.
- Use published `ai.astraform:sdk-java:0.4.5` and `astraform-sdk==0.3.5` from
  Maven Central and PyPI, retaining imports and public APIs. Refresh the Python
  registry locks for the renamed SDK distribution.
- Use published contracts 1.2.3 and `ai.astraform:conformance-java:0.4.5` for Java
  provider tests. Python provider checks use `astraform-conformance==0.3.5`,
  preserving imports and the `remote-domain-conformance` command.
- Preserve the published v0.2.0 images, archives, checksums and release selection.

## v0.2.0

- Prepare Python-issued, Java-issued, Java-monthly and scheduled-marketing
  archives under one samples release, targeting platform v0.4.0 and published
  Java 0.4.4/Python 0.3.4/contracts 1.2.2.
- Validate and forward explicit bank/scenario selections through the existing
  release owner; retain native completion/domain requirements and deterministic
  archive, anonymous-image and immutable-publication checks.
- Declare each selected scenario's domain identity, strict-completion and
  request-fact binding requirements; reject unsupported platform versions before
  image access or archive output without removing scenario fields.
- Preserve explicitly marked local rehearsals and historical v0.1.0 bytes.
  The published v0.2.0 selection records its source revisions, image digests
  and archive checksums; future releases must use a new reviewed selection.

See [v0.2.0 preparation notes](docs/releases/v0.2.0.md).

## v0.1.0

- Publish the public Java, Python, banking, marketing and refund source examples
  under Apache 2.0.
- Package the Python banking starter with a separate bank database, LLM partner,
  scoped MCP access, Gemini/Ollama profiles and SDK launch/inspection tools.
- Provide one repository release workflow with dry-run artifacts and publication
  of the reviewed starter archive, checksum and release manifest. Additional
  prepared starters can join later repository releases.
- Document public platform-bundle deployment and published SDK dependencies;
  no private platform source checkout is required.
- Use published Java SDK 0.4.2 and Python SDK 0.3.2, with refreshed banking
  starter images and explicit packaging/image revision and checksum selection.

See [v0.1.0 release notes](docs/releases/v0.1.0.md) for compatibility, verification
results and the distinction between source examples and packaged starters.
