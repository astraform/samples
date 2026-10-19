# Publish a samples release

All examples share one repository-wide `vX.Y.Z` release in `astraform/samples`.
The platform is released independently in `astraform/releases`; SDK versions
remain independent. Do not create per-language or per-starter tags.

## Prepare v0.2.0

The next samples release targets the published
[platform v0.3.0](https://github.com/astraform/releases/releases/tag/v0.3.0),
**Java SDK 0.4.4**, **Python SDK 0.3.4** and **contracts 1.2.2**. Samples v0.2.0
is not published yet. The historical samples v0.1.0/platform v0.2.0 pairing
retains its original bytes and compatibility. Do not overwrite that samples tag,
its images or its assets.

`release.json` selects four archives and stays **`publicationReady:false`**.
Null revision, image and checksum fields are intentionally unknown until actual
artifacts are reviewed; they must never be copied from the previous release.
Preparation rejects an unready manifest before fetching source, running a
packager or creating output.

| Starter | Packager | Explicit selectors | Archive for v0.2.0 |
| --- | --- | --- | --- |
| Python issued banking | `scripts/package-banking-starter.py` | `bank: python`, `scenario: issued` | `astraform-banking-starter-v0.2.0.tar.gz` |
| Java issued banking | `scripts/package-banking-starter.py` | `bank: java`, `scenario: issued` | `astraform-java-banking-issued-starter-v0.2.0.tar.gz` |
| Java monthly banking | `scripts/package-banking-starter.py` | `bank: java`, `scenario: monthly` | `astraform-java-banking-monthly-starter-v0.2.0.tar.gz` |
| Scheduled marketing | `scripts/package-marketing-starter.py` | `scenario: scheduled`, no `bank` | `astraform-marketing-scheduled-starter-v0.2.0.tar.gz` |

The common top-level `platformRelease` is `v0.3.0`. The release driver validates
all four selections, known packager paths, immutable revisions, digest pins,
unique archive identities and reviewed hashes before external preparation.
It passes only the supported selectors and `--platform-release`; arbitrary
packager arguments are not accepted. Historical v0.1.0 reproduction uses its
original committed manifest/packager and omits these newer arguments.

## Source, images and reviewed archives

1. Obtain and verify the new public platform v0.3.0 bundle and exact image digests.
   A version declaration does not establish publication, strict-completion
   behavior or native retained-participation support. The old platform v0.2.0
   does not supply strict customer-FINISH behavior. Current banking domain labels
   and marketing `RESOLVE_TRUSTED` binding also require matching server support.
   Java 0.4.4/Python 0.3.4 preserve these fields but do not add that behavior to a
   platform image; do not publish a starter against an unsupported platform.
2. Review and merge sample source, packaging support and
   [versioned notes](releases/v0.2.0.md) to `main`. Record its full commit as **S**;
   require **Astraform Samples CI** on S. Keep `release.json` unready at this step.
3. Dispatch that existing CI workflow from S with **Publish sample images to
   GHCR** and all five image selectors enabled. Publish `java-spring-banking`,
   `marketing-ops-remote`, `marketing-ops-creative-agency`, `banking-starter-bank`
   and `banking-starter-advice`. The immutable image tags are `sha-<full-commit>`.
   This image operation does not create a GitHub release. The workflow does not
   publish images on an ordinary push merely because its tests are green.
4. Record actual registry digests and verify anonymous manifest/config access,
   Linux AMD64/ARM64 entries, source labels equal to S, and installed SDK versions.
   All sample services selected by an archive must have its recorded
   `sourceRevision`; the separately pinned PostgreSQL image keeps its upstream
   provenance. Package visibility and repository Actions write access are separate
   settings; neither should be inferred from repository visibility.
5. From the clean public packaging source S, prepare all four public-mode
   archives with Python 3.12+ and Docker Buildx. Use a fresh output per variant.
   Never use `--local-verification` for public assets.

For example, save the selected Java bank/advice/PostgreSQL digest references as
`java-banking-images.json` with `banking`, `advice` and `postgres` keys, then run:

```sh
python3 scripts/package-banking-starter.py \
  --version v0.2.0 --platform-release v0.3.0 \
  --bank java --scenario issued \
  --source-revision FULL_IMAGE_BUILD_COMMIT \
  --image-lock java-banking-images.json \
  --output /tmp/astraform-java-issued-v0.2.0-review
```

Use `--bank python --scenario issued` for Python banking and
`--bank java --scenario monthly` for the monthly case, with their matching image
maps. For marketing, save `domain`, `agency` and `tools` digests and run:

```sh
python3 scripts/package-marketing-starter.py \
  --version v0.2.0 --platform-release v0.3.0 --scenario scheduled \
  --source-revision FULL_IMAGE_BUILD_COMMIT \
  --image-lock marketing-images.json \
  --output /tmp/astraform-marketing-v0.2.0-review
```

Public-mode preparation verifies digest, architecture and source metadata
anonymously. The declared `requiredPlatform.release` and capability fields
preserve the native completion/domain dependency; they do not independently
prove the selected platform is available. Local mode remains available for
installed-image rehearsals, marks `localVerificationOnly` and records local IDs.
Its archives must never be relabelled as public candidates.

Review each extracted archive, all file hashes/sizes, exact scenario/policy and
declared compatibility. Run the affected real customer/partner flows against the
matching actual platform and images, including due work/later input for timed
examples. Use the genuine Java preparation/client route as well as Python.
Record original model outcomes and advice, same-run inspection/export, restart
limits and scoped cleanup. Keep detailed evidence outside the repositories.
Architecture descriptors alone are not runtime verification on each architecture.

## Record the final selection and publish

1. Replace every null selection in `release.json` with its real reviewed value:
   full public `packagingRevision` S, actual image `sourceRevision`, exact image
   digests and `archiveSha256` from each emitted checksum file. The release driver
   must reproduce those hashes. Keep source/notes fixed after calculating them.
2. Set `publicationReady:true` only after platform/image/archive review. Commit
   this release selection as **R** and require exact-main CI. R differs from S:
   the final artifact identities can only be recorded after S has built them.
3. Run Actions → **Publish Samples Release** from reviewed main R with
   **publish unchecked**. Download and review all four archives/checksums and the
   release manifest. This dry run fetches the immutable public packaging source,
   verifies anonymous images and reproduces the approved bytes. It publishes
   neither images nor tags/releases.
4. Run the same workflow from the same reviewed R with **publish checked**.
   It creates the repository-wide v0.2.0 draft, uploads all assets, reads them back
   and compares their bytes before publishing. Do not precreate the samples tag:
   the publisher refuses existing tags and releases. If main changes, repeat
   review for the selected revision; never silently replace an existing asset.
5. Verify anonymous downloads/checksums and all advertised image pulls. Only then
   update website availability and matching Java helper/POM. A fresh independent
   operator must still complete the public route before onboarding is accepted.

The release contains four archives, four checksum files and
`release-manifest.json`. Repository notes supply the release description; each
archive embeds notes from its selected packaging revision. GitHub's automatic
source archives contain public samples, never private platform source.

## Public content and licensing

Workflow artifacts and logs in this public repository are public. Include only
reviewed examples, schemas, operator instructions and license files. Keep
credentials, customer exports, service logs, private release records, raw
verification reports and platform proof tooling out of the tree and artifacts.

All Astraform-authored examples and documentation use [Apache License 2.0](../LICENSE).
Preserve [NOTICE](../NOTICE) and component copies in standalone distributions.
The separately distributed platform is excluded; dependencies retain their terms.
