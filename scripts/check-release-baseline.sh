#!/usr/bin/env bash
set -euo pipefail
# semantic-release otherwise starts at 1.0.0 regardless of the POM's 0.1.0-SNAPSHOT.
if ! git tag --merged HEAD --list 'v[0-9]*' | grep -Eq '^v[0-9]+\.[0-9]+\.[0-9]+$'; then
  echo 'No reachable semantic-release baseline tag was found; create and push the initial v0.0.0 baseline tag before the first release.' >&2
  exit 1
fi
