#!/usr/bin/env bash
# Every artifact the BOM manages must be built by the release profiles (-Ppublishing,all-runtimes);
# a module listed only in a test profile is otherwise left out of the published Maven repository.
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"
# modules of the default build and of the all-runtimes profile
modules=$(awk '
  /<profiles>/ { inprofiles = 1 }
  /<\/profiles>/ { inprofiles = 0 }
  /<id>all-runtimes<\/id>/ { release = 1 }
  /<\/profile>/ { release = 0 }
  /<module>/ && (!inprofiles || release) { gsub(/.*<module>|<\/module>.*/, ""); print }
' pom.xml)
built=$(for m in $modules; do
  perl -0777 -ne 's/<parent>.*?<\/parent>//s; print "$1\n" if /<artifactId>([^<]+)<\/artifactId>/' "$m/pom.xml"
done | sort -u)
missing=0
managed=$(sed -n -E '/<dependencyManagement>/,/<\/dependencyManagement>/s:.*<artifactId>(keycloak-test-(framework|runtime)-[^<]*)</artifactId>.*:\1:p' bom/pom.xml | grep -v '^keycloak-test-framework-bom$')
[ -n "$managed" ] || { echo 'FAIL: no BOM-managed artifacts found' >&2; exit 1; }
for a in $managed; do
  if ! grep -qx "$a" <<< "$built"; then
    echo "FAIL: $a is managed by the BOM but not built by -Ppublishing,all-runtimes" >&2
    missing=1
  fi
done
[ "$missing" = 0 ] && echo 'PASS: every BOM-managed artifact is published'
exit "$missing"
