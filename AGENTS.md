# Working on this repository

Use English for public documentation, skills, and code comments.
The Maven launcher/integration runner uses JDK 25; the legacy Keycloak server and the example unit tests use JDK 11.
Keep Framework source unmodified and dependencies out of Java 11 unit-test modules.
Run `mvn verify` with `KEYCLOAK_JAVA_HOME` pointing at the JDK required by the selected Keycloak runtime, and `JAVA11_HOME` set when building the legacy unit-test modules.
Do not describe process-managed execution as same-JVM embedded execution.
Database switching and lifecycle management belong to Java implementations, while the skill guides configuration and environment isolation.
This repository is prepared for public use, but creating remote repositories, publishing artifacts, and pushing commits require explicit user instructions.

Commit messages and PR descriptions must be in English.
Use Conventional Commits and the release rules in CONTRIBUTING.md: feat is minor, fix/perf is patch, and breaking changes are minor while the API is 0.x.
Keep all POMs at 0.1.0-SNAPSHOT until semantic-release manages a release; do not bump versions manually for feature commits.
Run `bash scripts/tests/release.sh` when changing release automation.
The user manages GitHub publication: do not create a GitHub repository, push commits/tags, trigger a live release, or publish packages during local implementation/validation.
