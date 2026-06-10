# Quack on Demand documentation site

Docusaurus 3 site. The Configuration reference and the REST API spec are generated from
the Scala code, so they cannot drift from the implementation.

## Local development

From the repo root:

```bash
sbt genConfigDocs genOpenApi   # write docs/reference/configuration.md + static/openapi.yaml
cd website
npm install                    # first time only
npm run start                  # dev server at http://localhost:3000/quack-on-demand/
```

`npm run start` does not emit Google Analytics (the gtag is added only when
`QOD_DOCS_GA_ID` is set, which CI does for the production build).

Note: Node 18, 20, or 22 is required (Docusaurus does not support Node 24+ yet).

## How the reference pages are generated

- `static/openapi.yaml` comes from `sbt genOpenApi`, which renders the manager's Tapir
  endpoint definitions to OpenAPI 3. It is displayed by `static/api/index.html`, a plain
  Redoc page served at `/quack-on-demand/api/` (linked from the navbar and the Reference
  sidebar). Redoc runs outside Docusaurus, which keeps the build robust.
- `docs/reference/configuration.md` comes from `sbt genConfigDocs`, which reflects every
  `@ConfigField` in the config case classes and joins it with the `application.conf`
  defaults. Sensitive defaults are masked.

## Generated, git-ignored (never edit by hand)

- `static/openapi.yaml`             <- `sbt genOpenApi`
- `docs/reference/configuration.md` <- `sbt genConfigDocs`

## Production build

```bash
sbt genConfigDocs genOpenApi
cd website && npm ci && npm run build
```

## Known constraints

- `package.json` pins `webpack` to `5.97.1` via `overrides`. Webpack 5.100+ tightened the
  `ProgressPlugin` options schema, which the `webpackbar` progress reporter bundled by
  Docusaurus 3.7 violates, breaking the build. Remove the pin once Docusaurus ships a
  `webpackbar` that is compatible with newer webpack.
- Node 18, 20, or 22 only (`engines` enforces `<24`); Docusaurus 3.7 does not build on
  Node 24+ yet.
- The REST API page loads Redoc from `cdn.redoc.ly` at a pinned version. It is the only
  externally-hosted runtime asset; vendor the bundle under `static/` if an offline build
  is required.
