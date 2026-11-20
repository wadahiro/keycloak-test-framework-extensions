#!/usr/bin/env bash
set -euo pipefail
root=$(git rev-parse --show-toplevel)
target=${1:-"$root/_mvn-repo"}
if [[ -e "$target" ]]; then
  echo "Refusing to overwrite existing Maven repository checkout: $target" >&2
  exit 1
fi
# A failed/authenticated remote lookup must fail, not be mistaken for a missing branch.
remote_ref=$(git ls-remote --heads origin refs/heads/mvn-repo)
if [[ -n "$remote_ref" ]]; then
  git fetch origin refs/heads/mvn-repo
  git worktree add --detach "$target" FETCH_HEAD
else
  git worktree add --detach "$target" HEAD
  git -C "$target" switch --orphan mvn-repo
fi
