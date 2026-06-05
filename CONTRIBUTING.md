# Contributing to quack-on-demand

Thanks for thinking about contributing! This document is the short version
of how we work. Most things are picked to keep the project low-friction
for new contributors and operational for adopters.

## Project status

`quack-on-demand` is a multi-tenant FlightSQL gateway in front of DuckDB
Quack + DuckLake. The [README](README.md) is the elevator pitch;
.[RUNNING.md](guides/RUNNING.md) is the deployment guide. Issues labelled
`roadmap` are tracked work; `good first issue` is what to start with if
you want a small, well-scoped task.

## Before you start

- Check existing issues + PRs. If a tracked item (e.g. labelled
  `roadmap`) interests you, comment to claim it before you start
  writing code.
- For larger work, open a discussion or draft PR early so we can align
  on direction before you sink hours into it.
- All contributions are licensed under [Apache License 2.0](LICENSE),
  same as the project.

## Development setup

You need:

- JDK 17+
- `sbt` 1.x and `npm` 18+ (the UI builds as part of `sbt assembly`)
- The `duckdb` CLI on `$PATH` (each Quack node is a duckdb process)
- A reachable **PostgreSQL 16+** (default `localhost:5432`, user `postgres`,
  password `azizam` - all overridable; `run-jar.sh` gates the server version)

Quick loop:

```bash
sbt test                      # unit + integration tests (~714 of them)
sbt assembly                  # build distrib/quack-on-demand-assembly-*.jar
sbt scalafmtAll               # format (scalafmt 3.10, scala3 dialect, max 100 cols)
BUILD=1 ./scripts/run-jar.sh  # boot the manager from your local build
cd ui && npm run dev          # React dev server, proxies /api/* to :20900
```

See [CLAUDE.md](CLAUDE.md) for the architecture cheat sheet (which
parts span multiple files, what not to break).

## Commit conventions

We follow [Conventional Commits](https://www.conventionalcommits.org/).
Subject under 70 chars, scope optional, body explains the "why":

```
feat(scope): short imperative description

Why this change matters, what subtle decision was made, and how it
plays with adjacent code.
```

Common types: `feat`, `fix`, `docs`, `refactor`, `chore`, `ci`,
`style`, `test`, `perf`. Mix is fine if a change cuts across (e.g.
`feat(release):` for a build + workflow + docs change).

Prefer one commit per logical unit. Squash before merge if a PR drifts.

## Pull request flow

1. Fork + branch from `main`.
2. Make your changes; keep them focused on one concern per PR.
3. `sbt scalafmtAll && sbt test` locally before pushing.
4. Open the PR. Link the related issue with `Fixes #N` (or `Refs #N`).
5. The CI must be green: snapshot build + tests + docker multi-arch
   build all pass.
6. A maintainer reviews. We aim for a first response within a few days.

If you're working on a tracked `roadmap` issue, also mark the bullet
done on the issue itself (close it with `Fixes #N` from your PR).

## What we look for in a PR

- Behaviour is covered by tests (we live or die by the test suite).
- The change is justified in the PR description, not just in the diff.
- Public surface changes (REST, FlightSQL, `qodstate_*` schema) come
  with migration notes; backward-compat is a goal until 1.0.
- Documentation (README, guides/RUNNING.md, scripts' header comments) is
  updated when behaviour changes.
- No comments restating what the code obviously does; comments only
  for the *why* (constraints, surprising decisions, workarounds).

## Releasing

Releases are cut by maintainers via `./scripts/release.sh` (see the
script's header for the full flow). Contributors don't need to worry
about versioning; just merge to `main` and the snapshot CI takes care
of publishing `:latest-snapshot` and the matching Maven snapshot.

## Reporting bugs / asking questions

- **Bug** -> open an issue using the *Bug report* template, include
  the failing command + the manager logs.
- **Feature idea** -> open an issue using the *Feature request*
  template, describe the use case before the design.
- **Question** -> use [GitHub Discussions](https://github.com/starlake-ai/quack-on-demand/discussions)
  (linked from the README). Not an issue; we don't want the tracker to
  fill up with how-to threads.

## Code of conduct

This project follows the [Contributor Covenant](CODE_OF_CONDUCT.md).
By participating you agree to abide by its terms.