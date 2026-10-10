#!/usr/bin/env bash

set -euo pipefail

IMAGE="${1:?image name is required}"
TAG="${2:?immutable release tag is required}"
shift 2
references=("$@")

if [[ ! "${IMAGE}" =~ ^[a-z0-9][a-z0-9._-]*$ ]]; then
  echo "Image name is invalid." >&2
  exit 1
fi
if [[ ! "${TAG}" =~ ^v[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z][0-9A-Za-z.-]*)?$ ]]; then
  echo "The immutable release tag must match vMAJOR.MINOR.PATCH." >&2
  exit 1
fi
if [[ "${#references[@]}" -eq 0 ]]; then
  echo "At least one immutable source reference is required." >&2
  exit 1
fi

temporary="$(mktemp -d)"
trap 'rm -rf "${temporary}"' EXIT
: > "${temporary}/expected-digests"
for index in "${!references[@]}"; do
  reference="${references[$index]}"
  expected_prefix="ghcr.io/astraform/${IMAGE}@sha256:"
  digest="${reference#"${expected_prefix}"}"
  if [[ "${reference}" != "${expected_prefix}${digest}" \
        || ! "${digest}" =~ ^[0-9a-f]{64}$ ]]; then
    echo "Source reference is not an immutable digest for ${IMAGE}: ${reference}" >&2
    exit 1
  fi
  docker buildx imagetools inspect --raw "${reference}" > "${temporary}/source-${index}"
  if jq -e '(.manifests | type) == "array"' "${temporary}/source-${index}" >/dev/null; then
    jq -r '.manifests[].digest' "${temporary}/source-${index}" \
      >> "${temporary}/expected-digests"
  else
    printf '%s\n' "${reference#*@}" >> "${temporary}/expected-digests"
  fi
done
sort -u -o "${temporary}/expected-digests" "${temporary}/expected-digests"

release_reference="ghcr.io/astraform/${IMAGE}:${TAG}"
existing_error="${temporary}/existing-error"
release_exists=false
if docker buildx imagetools inspect --raw "${release_reference}" \
    > "${temporary}/existing-manifest" 2> "${existing_error}"; then
  jq -r 'if (.manifests | type) == "array" then .manifests[].digest else empty end' \
    "${temporary}/existing-manifest" | sort -u > "${temporary}/existing-digests"
  if ! diff -u "${temporary}/expected-digests" "${temporary}/existing-digests"; then
    echo "Refusing to repoint immutable release tag ${release_reference}." >&2
    exit 1
  fi
  release_exists=true
elif ! grep -Eiq 'manifest unknown|not found|404' "${existing_error}"; then
  cat "${existing_error}" >&2
  echo "Could not prove whether ${release_reference} already exists." >&2
  exit 1
fi

if [[ "${release_exists}" != "true" ]]; then
  docker buildx imagetools create \
    --tag "${release_reference}" \
    "${references[@]}"
fi

docker buildx imagetools inspect --raw "${release_reference}" \
  | jq -r '.manifests[].digest' \
  | sort -u > "${temporary}/published-digests"
diff -u "${temporary}/expected-digests" "${temporary}/published-digests"
