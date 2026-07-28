#!/bin/bash

set -euo pipefail

ROOT_DIR=$(git rev-parse --show-toplevel)
OUTPUT_PATH=${1:?Usage: package_release_source.sh <output.tar.gz> <archive-prefix>}
ARCHIVE_PREFIX=${2:?Usage: package_release_source.sh <output.tar.gz> <archive-prefix>}
OUTPUT_PATH=$(realpath -m "$OUTPUT_PATH")
STAGING_DIR=$(mktemp -d)
TAR_PATH="$STAGING_DIR/source.tar"
PART_INDEX=0

trap 'rm -rf "$STAGING_DIR"' EXIT

git -C "$ROOT_DIR" archive \
  --format=tar \
  --prefix="$ARCHIVE_PREFIX/" \
  HEAD > "$TAR_PATH"

append_submodules() {
  local repository="$1"
  local archive_path="$2"
  local gitmodules="$repository/.gitmodules"

  [[ -f "$gitmodules" ]] || return 0

  while IFS= read -r submodule_path; do
    [[ -n "$submodule_path" ]] || continue

    local submodule_repository="$repository/$submodule_path"
    local expected_revision
    local actual_revision
    local part_path

    expected_revision=$(git -C "$repository" ls-tree HEAD -- "$submodule_path" | awk '{ print $3 }')
    actual_revision=$(git -C "$submodule_repository" rev-parse HEAD)
    if [[ "$actual_revision" != "$expected_revision" ]]; then
      echo "Submodule $archive_path/$submodule_path is not at $expected_revision" >&2
      exit 1
    fi

    PART_INDEX=$((PART_INDEX + 1))
    part_path="$STAGING_DIR/submodule-$PART_INDEX.tar"
    git -C "$submodule_repository" archive \
      --format=tar \
      --prefix="$archive_path/$submodule_path/" \
      HEAD > "$part_path"
    tar --concatenate --file="$TAR_PATH" "$part_path"
    append_submodules "$submodule_repository" "$archive_path/$submodule_path"
  done < <(
    git config --file "$gitmodules" --get-regexp '^submodule\..*\.path$' |
      cut -d ' ' -f 2-
  )
}

append_submodules "$ROOT_DIR" "$ARCHIVE_PREFIX"
gzip -n < "$TAR_PATH" > "$OUTPUT_PATH"
sha256sum "$OUTPUT_PATH"
