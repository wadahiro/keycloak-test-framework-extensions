#!/usr/bin/env bash
set -euo pipefail
scripts=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
fixture=$(mktemp -d "${TMPDIR:-/tmp}/keycloak-release-test.XXXXXX")
trap 'rm -rf "$fixture"' EXIT
origin="$fixture/origin.git"
checkout="$fixture/checkout"
git init --bare --initial-branch=main "$origin" >/dev/null
git clone "$origin" "$checkout" >/dev/null 2>&1
git -C "$checkout" config user.name 'Release Test'
git -C "$checkout" config user.email 'release-test@example.invalid'
git -C "$checkout" config core.hooksPath /dev/null
printf 'fixture\n' > "$checkout/README.md"
printf '_mvn-repo/\n_mvn-repo-next/\n' > "$checkout/.gitignore"
git -C "$checkout" add .
git -C "$checkout" commit -m 'chore: initialize release fixture' >/dev/null
git -C "$checkout" push origin main >/dev/null 2>&1
cd "$checkout"
if bash "$scripts/check-release-baseline.sh" >/dev/null 2>&1; then
  echo 'FAIL: missing release baseline was accepted' >&2
  exit 1
fi
git tag v0.0.0
bash "$scripts/check-release-baseline.sh"
echo 'PASS: release baseline is required'
bash "$scripts/prepare-maven-repo.sh" >/dev/null 2>&1
first='_mvn-repo/io/github/wadahiro/example/0.1.0/example-0.1.0.pom'
mkdir -p "$(dirname "$first")"
printf '<project/>\n' > "$first"
RELEASE_VERSION=0.1.0 bash "$scripts/push-maven-repo.sh" >/dev/null 2>&1
git --git-dir="$origin" show mvn-repo:io/github/wadahiro/example/0.1.0/example-0.1.0.pom | cmp - "$first"
test "$(git branch --show-current)" = main
if bash "$scripts/prepare-maven-repo.sh" >/dev/null 2>&1; then
  echo 'FAIL: an existing checkout was overwritten' >&2
  exit 1
fi
bash "$scripts/prepare-maven-repo.sh" "$checkout/_mvn-repo-next" >/dev/null 2>&1
second='_mvn-repo-next/io/github/wadahiro/example/0.2.0/example-0.2.0.pom'
mkdir -p "$(dirname "$second")"
printf '<project version="0.2.0"/>\n' > "$second"
RELEASE_VERSION=0.2.0 bash "$scripts/push-maven-repo.sh" "$checkout/_mvn-repo-next" >/dev/null 2>&1
git --git-dir="$origin" show mvn-repo:io/github/wadahiro/example/0.1.0/example-0.1.0.pom | cmp - "$first"
git --git-dir="$origin" show mvn-repo:io/github/wadahiro/example/0.2.0/example-0.2.0.pom | cmp - "$second"
RELEASE_VERSION=0.2.0 bash "$scripts/push-maven-repo.sh" "$checkout/_mvn-repo-next" >/dev/null 2>&1
echo 'PASS: Maven branch creation, updates, preservation, overwrite rejection and no-op push'
