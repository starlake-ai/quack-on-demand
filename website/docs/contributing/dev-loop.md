---
id: dev-loop
title: Development loop
---

This page is the day-to-day developer workflow: building, testing, running, and regenerating the docs. For how the code is laid out, see the [Architecture map](/contributing/architecture-map).

## Build and test

```bash
sbt run                 # run the manager (forks the JVM, see below)
sbt test                # full Scala test suite
sbt assembly            # build the uber-jar (the UI is bundled in)
sbt scalafmtAll         # format (scalafmt, scala3 dialect, maxColumn 100)
```

Run a single test class or one test by name:

```bash
sbt "testOnly ai.starlake.quack.route.RouterSpec"
sbt "testOnly *RouterSpec -- -z 'picks the least-loaded'"
```

## JVM forking (do not disable)

The build sets `Compile / run / fork := true` and `Test / fork := true` and adds `--add-opens=java.base/java.nio=ALL-UNNAMED` and `--add-opens=java.base/sun.nio.ch=ALL-UNNAMED`. Arrow Flight's unsafe allocator reflects into `java.nio` internals; without the forked JVM and these opens, Arrow crashes at class initialization on Java 17+. The assembly jar carries an `Add-Opens` manifest attribute so `java -jar` works without extra flags, and the startup script passes `-Darrow.allocation.manager.type=Unsafe`.

## Running

Boot from the uber-jar with TLS cert auto-generation and a Postgres reachability probe:

```bash
./scripts/run-jar.sh                     # foreground
QOD_VERSION=BUILD ./scripts/run-jar.sh   # sbt assembly first
QOD_VERSION=LOCAL ./scripts/run-jar.sh   # newest distrib/ jar, no rebuild
./scripts/stop-jar.sh
```

Or run the whole stack (manager + Postgres, plus optional profiles) via Docker Compose; see [Docker deployment](/operating/deploy-docker).

## UI dev loop

The React admin UI lives in `ui/`. For a hot-reload loop that proxies `/api/*` to a running manager on `:20900`:

```bash
cd ui && npm install && npm run dev
```

For a regular Scala build there is no manual UI step: `project/UiBuild.scala` runs `npm ci` + `npm run build` as a resource generator, so `sbt compile` / `sbt assembly` bundle the built UI into `src/main/resources/ui` automatically.

## Regenerating the docs reference

The Configuration and REST API reference pages are generated from code, so they cannot drift. Regenerate them, then build the site:

```bash
sbt genConfigDocs genOpenApi
cd website && npm ci && npm run build
```

`genConfigDocs` writes `website/docs/reference/configuration.md` from the config registry; `genOpenApi` writes `website/static/openapi.yaml` from the Tapir endpoints. Both are git-ignored and produced in CI before the site build. The Docusaurus build runs with `onBrokenLinks: throw`, so a broken internal link fails the build, which is the project's dead-link gate in CI.

## Configuration

Every scalar in `src/main/resources/application.conf` accepts a `QOD_*` env-var override (or `PROXY_*` for the FlightSQL edge). The conf is bundled into the jar at build time, so prefer env vars over editing it for local tweaks. See the [Configuration reference](/reference/configuration).
