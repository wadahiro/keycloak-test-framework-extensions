#!/usr/bin/env bash
set -euo pipefail
: "${RELEASE_VERSION:?Set RELEASE_VERSION to the published semantic version}"
root=$(git rev-parse --show-toplevel)
target=${1:-"$root/_mvn-repo"}
if [[ ! -f "$target/.git" ]]; then
  echo "Not a prepared Maven repository worktree: $target" >&2
  exit 1
fi
git -C "$target" config user.name 'github-actions[bot]'
git -C "$target" config user.email '41898282+github-actions[bot]@users.noreply.github.com'
git -C "$target" add --all
if git -C "$target" diff --cached --quiet; then
  echo 'No new Maven artifacts to push.'
  exit 0
fi
git -C "$target" commit -m "chore(release): deploy Maven artifacts for v$RELEASE_VERSION"
git -C "$target" push origin HEAD:refs/heads/mvn-repo
