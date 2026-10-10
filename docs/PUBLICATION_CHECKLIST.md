# Publishing sample images and starters

This is a maintainer guide for [Astraform Samples](https://github.com/astraform/samples).
Partners can run samples against the
[released platform bundle](https://github.com/astraform/releases/releases/tag/v0.2.0)
using the sample READMEs; they do not need to publish images or access platform source.

## Release boundaries

The public banking starter archive is pending. Source availability does not imply
that a downloadable starter or its container images have been published.

This repository begins with a clean source snapshot. Historical release tags and
images from earlier development have not been imported. Use a new immutable
source version for releases built here; never recreate old tags or relabel
existing images as builds of this repository.

All Astraform-authored examples and documentation use [Apache License 2.0](../LICENSE).
Preserve [NOTICE](../NOTICE) and component copies in standalone distributions.
The separately distributed platform is excluded; dependencies retain their own terms.

## Verify and publish images

1. Merge reviewed source changes and wait for **Astraform Samples CI**. For a new
   image version, create a new immutable version tag on that exact reviewed
   revision. Existing tags are never moved.
2. Before publishing to an existing GHCR package, its owner must configure this
   repository's Actions access to that specific package. Workflow permissions
   alone do not grant package access. Do not grant access to unrelated private
   packages. See [GitHub package access guidance](https://docs.github.com/en/packages/learn-github-packages/configuring-a-packages-access-control-and-visibility).
3. Run **Astraform Samples CI** from the version tag. Enable **Publish sample
   images to GHCR** and only the intended per-image options. Pushes, pull requests
   and tag builds verify but do not publish.
4. Keep the full source commit, workflow run and emitted image digest for each
   selected package. The workflow builds AMD64 and ARM64; build availability
   is separate from runtime verification on those architectures.
5. After release-owner approval, make only reviewed packages public and verify
   anonymous access to their exact immutable digests. Repository visibility and
   package visibility are separate settings.

The banking starter uses dedicated packages
`ghcr.io/astraform/banking-starter-bank` and
`ghcr.io/astraform/banking-starter-advice`. The historical
`python-fastapi-banking` package is not a substitute for either.

## Prepare the banking starter

Run **Prepare Banking Starter** (`banking-starter.yml`) from the source tag in
this repository that produced the selected images. Supply the starter version,
full immutable bank/advice image references from that publication run, and the
reviewed public PostgreSQL digest. Both service images must identify the selected
workflow commit as their source revision. Images from a different repository's
history cannot be paired with this workflow's new commit.

This workflow prepares review artifacts only; it does not publish a release.
**Artifacts and logs in this public repository are publicly accessible.** Include
only distribution-ready files and never upload credentials, simulation exports
or private release records as review artifacts.

Review the archive, checksum, manifest, release notes and selected LICENSE/NOTICE
files. The manifest must identify the matching source, images and supported
platform/SDK/contracts. The first starter targets platform **v0.2.0**, Python SDK
**0.3.1** and contracts **1.2.0**. Source/image tags and starter archive versions
are independent; new documentation does not change an already-published image.

Verify the extracted starter against its declared platform release using public
downloads and SDK dependencies. Record the actual model choices, run outcome,
customer/partner exchanges and exported evidence. Preserve failed goals and
model decisions; a successful command is not evidence of an achieved goal.
Remove only trial-owned containers and temporary resources after saving evidence.

After approval, publish the exact reviewed archive/checksum/notes to
[`astraform/releases`](https://github.com/astraform/releases) using tag
`banking-v0.1.0`, with **latest release disabled** so the platform remains the
default download. Never silently replace a published asset. Verify anonymous
downloads before enabling website links. See the
[starter release notes](releases/banking-starter-v0.1.0.md) and
[starter operating guide](../deploy/banking-starter/README.md).

## Public content

Publish only reviewed examples, schemas, operator instructions and license files.
Keep provider credentials, customer exports, service logs, private release records
and platform proof tooling out of the samples tree and release artifacts.
The samples' bank data and partner history belong to the partner deployment,
separately from Astraform's database. The repository's visibility does not change
the platform's source or usage terms.
