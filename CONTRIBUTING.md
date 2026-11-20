# Contributing

Use English for code comments, documentation, pull requests, and commit messages.
Keep the server/runtime matrix and the existing Java 11 unit-test boundary intact.
Run the backend verification commands in the README for affected implementations.

## Commit messages and releases

Use [Conventional Commits](https://www.conventionalcommits.org/):

```text
feat(snapshot): restore baseline sessions
fix(runtime): stop the child after startup failure
docs(skill): explain JDK separation
ci: verify the runtime before release
```

`feat:` releases a minor version; `fix:` and `perf:` release a patch.
During 0.x development, a breaking change releases a **minor**, not 1.0.0.
Mark it with `!` and explain it in a `BREAKING CHANGE:` footer:

```text
feat(api)!: rename the server configuration prefix

BREAKING CHANGE: replace kc.test.old-prefix with kc.test.new-prefix.
```

Ordinary `build:`, `ci:`, `docs:`, `chore:`, `refactor:`, `style:`, and `test:` commits do not release.
A breaking change takes precedence even when the commit type normally does not release.
Review commit messages for the Conventional Commits structure and English wording.
If changes are squash-merged, the final squash message must carry the intended release type and breaking-change footer.
Do not hide a user-facing feature or fix in a `chore:` squash commit.

Local development requires Java and Maven.
Release automation runs through `cycjimmy/semantic-release-action`; no local Node installation or commit hook is required.
Run `bash scripts/tests/release.sh` when changing the Maven repository scripts.

The repository starts at **0.1.0-SNAPSHOT**; semantic-release manages every module version after releases.
Do not manually bump versions for a feature or fix.
Before promoting to 1.0.0, deliberately remove the 0.x breaking-change override and review the public API.

Pushes to `main` run the test workflow and then semantic-release; Maven artifacts are published to the `mvn-repo` branch.
The maintainer manages GitHub repository publication and release tags.
