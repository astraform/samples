# Publish a samples release

All examples in [Astraform Samples](https://github.com/astraform/samples) share
one release stream: **v0.1.0**, **v0.1.1**, and so on. A release contains the
source examples and the available starter archives. There are no separate
banking, marketing, language or framework release tags.

The platform is released independently in `astraform/releases`; SDKs keep their
own versions. Partners only need the sample's supported platform bundle and
published dependencies, not platform source.

## Publish v0.1.0

This release refreshes the SDK-consuming images to Java **0.4.2** and Python
**0.3.2**. Existing image tags and digests retain their original contents. The
Python banking archive must select the refreshed public bank/advice pair, their
genuine image-build revision and a committed public packaging revision.

`publicationReady` stays **false** in `release.json` until those exact selections
and the archive SHA256 have been reviewed. Preparation and publication reject
an unready selection; the retained earlier digests are not the refreshed release.

1. Merge the reviewed source, release tooling and packaging files to `main`.
   Wait for **Astraform Samples CI** on that exact revision. Publish the changed
   images through the separate image workflow as described below, then verify
   their actual SDK versions, immutable digests and source labels. The bank and
   advice images must share one image-build revision.
2. Prepare and review the refreshed archive from a clean committed packaging
   revision. Record its `archiveSha256`, `packagingRevision`, `sourceRevision`
   and exact `images` in `release.json`; set `publicationReady` to **true** only
   after that review. Merge this final selection and wait for exact-main CI.
3. Open Actions → **Publish Samples Release** (`samples-release.yml`). Select
   `main` and leave **publish** unchecked for a dry run.
4. Download the resulting workflow artifact. Review the banking archive,
   checksum, release manifest and repository release notes. `release.json` records
   the shared version, packaged starters, packaging source, image source and
   image digests.
   The workflow verifies public image access and the expected archive checksum.
5. Run the same workflow on the same reviewed `main` revision with **publish**
   checked. The workflow creates the shared **v0.1.0** release in
   `astraform/samples`, uploads the starter archive, checksum and release manifest,
   verifies their downloaded bytes, then publishes it as the latest stable
   samples release.
6. Confirm the public release and anonymous asset downloads before updating
   website download links.

A dry run creates workflow artifacts only: no tags, releases or images. The
publishing run refuses to overwrite an existing tag or release. If a publishing
attempt fails, inspect the existing draft/tag and workflow output before taking
recovery action; do not silently replace published assets.

The release assets are:

- `astraform-banking-starter-v0.1.0.tar.gz`
- `astraform-banking-starter-v0.1.0.tar.gz.sha256`
- `release-manifest.json`, recording the release and starter provenance

The [repository release notes](releases/v0.1.0.md) become the release description.
GitHub also supplies source archives of the public samples tag. Those contain
all source examples, including Java and marketing, not Astraform platform source.
Java and marketing do not yet have prepared starter archives in this release.

## Reproducible first release

The selected packaging files are available in the clean public repository at
the immutable packaging revision in `release.json`. The workflow uses that
public revision to reproduce the archive and checks its reviewed SHA256. It
does not use an uncommitted working tree or an older private checkout.

The repository release tag identifies the reviewed public release commit.
`bundle-manifest.json` identifies the banking images' actual source revision,
compatibility versions and exact images. These values serve different purposes;
do not relabel existing image metadata as a new build. Embedded notes come from
the selected packaging revision; the shared release description covers all
samples and the release's current verification results.

## Later releases and image changes

Update the shared version and starter entries in `release.json`, the
[changelog](../CHANGELOG.md) and `docs/releases/<version>.md`. Reuse unchanged
image digests. New languages or frameworks become source folders and, when
prepared, additional starter assets under the same release. Each starter entry
must preserve its actual packaging and image build revisions.

For a changed sample image:

1. Merge its reviewed source and intended packaging files, including
   `docs/releases/<version>.md`, to `main`. Wait for **Astraform Samples CI**.
2. Run **Astraform Samples CI** from that `main` revision with **Publish sample
   images to GHCR** and the required per-image options selected. Images are
   identified by `sha-<full-commit>` tags; this does not create a GitHub release.
   The current banking packager requires bank and advice images from the same
   `sourceRevision`, so publish that pair together when either needs rebuilding.
3. Keep the source commit, workflow run and emitted immutable digests together.
   Prepare and review the new archive as described below, then record its
   `archiveSha256`, the public `packagingRevision`, the image `sourceRevision`
   and `images` map in the affected `release.json` starter entry. Verify the
   installed SDK version in each actual published image, separately from its
   source label. Set `publicationReady` to **true** after this selection is reviewed.
4. Merge the release configuration and notes to `main`, wait for CI, then use
   **Publish Samples Release** to dry-run and publish the next shared version.

The image build revision, packaging revision and final release commit can differ.
Unchanged images retain their existing digests. The image workflow is separate
from release publication and never runs merely because **Publish Samples Release**
is selected.

### Prepare the checksum for a new starter archive

The release workflow checks `archiveSha256`; it does not choose the expected
checksum for you. From the clean public commit containing the intended packaging
files and release notes, use Python 3.12+ and Docker with Buildx. For example,
when preparing banking for **v0.1.1**, save its three selected digest references
as a JSON object named `banking-images.json` with `banking`, `advice` and `postgres`
keys, matching the `images` object in `release.json`. Then run:

```sh
python3 scripts/package-banking-starter.py \
  --version v0.1.1 \
  --source-revision FULL_IMAGE_BUILD_COMMIT \
  --image-lock banking-images.json \
  --output /tmp/astraform-banking-v0.1.1-review
```

Replace the version and full image-build commit with your actual selections.
Review the extracted files and manifest, run the relevant scenario verification,
and take `archiveSha256` from the generated `.tar.gz.sha256` file. Record the
exact public commit you packaged as `packagingRevision`. Commit the selected
values in `release.json` only after that review; the shared workflow must
reproduce those same bytes. Do not use `--local-verification` for public assets.
Keep this review output outside the repository.

Before writing to an existing GHCR package, grant this repository's Actions
access to that package. Workflow permissions alone do not grant package access;
see [GitHub package access guidance](https://docs.github.com/en/packages/learn-github-packages/configuring-a-packages-access-control-and-visibility).
The banking starter uses `ghcr.io/astraform/banking-starter-bank` and
`ghcr.io/astraform/banking-starter-advice`. Verify anonymous access to the locked
images. Repository visibility and package visibility are separate settings.

For changed runtime or packaged configuration, verify the affected starter
against its declared platform release. Record the actual models, customer goal,
conversations, tool activity and exported evidence. An unmet goal is an observed
model outcome, not something to hide. Save evidence privately and remove only
the trial's temporary resources. AMD64/ARM64 image availability is separate from
runtime verification on each architecture.

## Public content and licensing

Workflow artifacts and logs in this public repository are public. Include only
reviewed examples, schemas, operator instructions and license files. Keep
credentials, customer exports, service logs, private release records and platform
proof tooling out of the tree and release artifacts.

All Astraform-authored examples and documentation use [Apache License 2.0](../LICENSE).
Preserve [NOTICE](../NOTICE) and component copies in standalone distributions.
The separately distributed platform is excluded; dependencies retain their terms.
