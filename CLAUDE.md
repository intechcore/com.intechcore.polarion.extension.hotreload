# CLAUDE.md

Guidance for working in this repository.

## What this is

`com.intechcore.polarion.extension.hotreload` - a Polarion ALM extension for hot reload,
in an early stage: the repository holds the skeleton only. It builds on the SBB
`ch.sbb.polarion.extension.generic` framework (parent POM) and targets
**Polarion 2606 / Tomcat 11 / Jakarta EE 11**.

The skeleton adds the **Hot Reload** section to the global Administration menu with three pages:
About, Disclaimer and REST API. It has no feature code and no settings yet.

## Build & verify

```bash
mvn clean package            # do NOT pass -s .mvn/settings.xml locally, it is for CI only
mvn clean install -P local-install-into-polarion   # deploy into $POLARION_HOME
```

- The React UI build is **inherited from the generic parent** (its `ui-build-react-app` profile,
  activated by the presence of `ui/package.json`): it installs Node, runs `npm ci` + `npm run build`
  in `ui/`, and copies `ui/dist` into `src/main/resources/webapp/hotreload-app/`. This pom
  carries no UI build of its own - do not add one back.
- **Tests** run on `mvn test`/`package`: Java via surefire (JUnit 5 + AssertJ + Mockito, inherited
  from the parent) and the frontend suite through the parent's test-phase execution.
  `-DskipJsTests=true` skips only the JS tests. Frontend tests live in `ui/test/**` and run in
  **Vitest browser mode** (a real Chromium via Playwright), with an istanbul **90%** gate on all four
  metrics. There are no Playwright e2e tests in this suite.
- **Visual regression**: `ui/test/*.visual.test.tsx` capture `ui/test/expected/**`. They are
  pixel-locked to the pinned Playwright Docker image and skip themselves anywhere else
  (`__PIXEL_REFERENCES__`). Regenerate only with `npm run test:update:docker`, never by hand.
- **After a failed `local-install-into-polarion`, check the jar landed.** A failure empties this
  extension's own plugins directory, and Polarion then starts without the extension.

UI dev loop (hot reload against a running Polarion):

```bash
cd ui
cp .env.local.template .env.local   # set VITE_BASE_URL and (optional) VITE_BEARER_TOKEN
npm run dev                          # http://localhost:5173/?feature=about (or ?feature=disclaimer)
npx tsc --noEmit                     # type-check (vite build does NOT type-check)
```

## CI

GitHub Actions runs four workflows:

- `ci.yml`, on every pull request and on `main`:
  - `lint`: actionlint and zizmor audit the workflows.
  - `build`: `mvn -s .mvn/settings.xml clean verify` with the Java and UI tests and the Polarion
    compatibility check, then the SonarCloud analysis. It fails when the build changes
    `docs/openapi.json`. The analysis is skipped while `SONAR_TOKEN` is missing.
  - `pre-commit`: all hooks, except `no-commit-to-branch`, the git identity check and the Docker UI
    tests.
  - `conventional-commits`: commitizen checks the pull request title and its commits.
- `scorecard.yml`: OpenSSF Scorecard, weekly and on `main`. Findings go to code scanning.
- `bump-version.yml`: dispatched by hand with `patch`, `minor` or `major`. It sets the release
  version in the pom, cuts the `## [Unreleased]` section of `CHANGELOG.md` into the release, commits
  it, tags it `v<version>` and pushes to `main`. It pushes with `PAT_TOKEN` through `GIT_ASKPASS`
  on purpose: a tag pushed with the default `GITHUB_TOKEN` starts no further workflow, so
  `release.yml` would never see it.
- `release.yml`: runs on a `v*` tag. It refuses a release that exists already, reads the notes from
  `CHANGELOG.md`, then runs `deploy` with the generic profiles `gpg-sign,central-publishing`: the
  tests run and the signed bundle goes to Maven Central. It attests the jars and the pom, and creates
  the GitHub release with `gh release create` in one call. Releases are immutable. A second job
  returns `main` to the next `-SNAPSHOT`. The Central credentials and the GPG key are the org
  secrets `SONATYPE_USERNAME`, `SONATYPE_TOKEN`, `GPG_PRIVATE_KEY` and `GPG_PASSPHRASE`, granted to
  this repository by name. `.mvn/settings.xml` maps them to the server `sonatype-central`.

pre-commit and commitizen are installed from `.github/requirements/*.txt`, pinned by hash. After a
version change in the `.in` file, recompile with `uv pip compile --generate-hashes --universal
--python-version=3.14 <in> --output-file=<txt>`. Renovate does that itself.

The Polarion artifacts come from the Intechcore Nexus through the repository secrets `NEXUS_URL`,
`NEXUS_USERNAME` and `NEXUS_PASSWORD`. `.mvn/settings.xml` declares Central first, so Nexus only
serves the Polarion artifacts. **Do not reintroduce a groupId remote-repository filter** (`.mvn/rrf`):
the Maven 3.9 resolver matches a groupId exactly, without sub-groups, and it rejected
`com.polarion.alm.projects`. The Maven cache leaves `com/polarion` and `com/siemens` out, because
pull requests from forks can restore the caches of `main`.

## Architecture

Two layers: a Java/Polarion backend and a React frontend.

**Java** (`src/main/java/.../hotreload/`)

- `rest/HotReloadRestApplication` - a plain `GenericRestApplication`. It registers no settings
  and no controller of its own, so generic serves only extension info, OpenAPI and Swagger UI. Its
  settings endpoints appear once a setting is registered with `NamedSettingsRegistry`.
- `rest/controller/OpenAPIInfo` - the annotation the swagger plugin turns into `docs/openapi.json`.
- `HotReloadAppServlet` - a `GenericUiServlet` subclass serving the
  `hotreload-app` webapp context.

**React** (`ui/`) - Vite + React 19 + TypeScript SPA on the shared
`@sbb-polarion/react-sbb-polarion` (RSP) library. One app, page selected by `?feature=`:

- `pages/About.tsx` - RSP's shared `About` component, fed this extension's endpoints.
- `pages/Disclaimer.tsx` - the build-generated DISCLAIMER article.
- `pages/Landing.tsx` - a dev-only stub listing every feature, so the app can be exercised in
  `vite dev` without Polarion. An unknown or missing `?feature=` falls back to it.
- `services/useRemote.ts` - REST hook. **Convention: the UI always calls `/internal/*` (in-session);
  a `VITE_BEARER_TOKEN` in `vite dev` switches it to `/api/*`.**

`src/main/resources/plugin.xml` declares **two webapp contexts** (extension point
`com.polarion.portal.tomcat.webapps`): `hotreload` for the REST API and
`hotreload-app` for the React app and the menu icons. Adding a
`webapp/<name>/WEB-INF/web.xml` alone is not enough. The URLs in `hivemodule.xml` must point at a
real file (`.../ui/app/index.html?...`), not at a directory - `GenericUiServlet` rejects
extension-less paths.

## Conventions & gotchas

- **Jakarta, not javax.** All code is on `jakarta.*`, and the
  `polarion-compatibility-maven-plugin` fails the build when the bundle or a nested jar refers to a
  package Polarion 2606 rejects. `web.xml` descriptors use the Jakarta `web-app_6_1.xsd`.
- **Don't hardcode `Require-Bundle`** in `META-INF/MANIFEST.MF`. The list comes from the `generic`
  parent. Source keeps only `Bundle-Name`; the rest of the manifest is generated from the
  `maven-jar-plugin.*` properties in the pom.
- **Icons.** The administration section icon is
  `webapp/hotreload-app/images/menu/30x30/_parent.svg` and the page icons live in
  `images/menu/16x16/`. `ui/src/assets/app-icon.svg` is a separate file: Vite inlines it into the
  bundle for the About page, and the About reference screenshot pins it.
- **`DISCLAIMER.md` is a build input.** `markdown2html-maven-plugin` converts it to
  `webapp/hotreload-app/html/disclaimer.html` at `generate-resources`, which the
  Disclaimer page fetches. Editing the markdown changes the page.
- **Two parent plugin blocks are deliberately overridden**: `copy-vite-build` (restated with
  `changeDetection`, the replacement of the deprecated `overwrite` flag) and the compiler args
  (the parent's `-Alog4j.graalvm.*` options are unrecognized here). Maven merges plugin
  configuration, so both had to be overridden whole - keep them in step on every generic bump.
- **A visual test that resizes the viewport or parks the pointer must run last in its file.** Both
  outlive a test, because one browser page runs the whole file, and every reference captured after
  such a case would be taken under a layout it was not generated with.
- **No tests that assert log output.** Assert behavior through its effect, not through the
  logger. `src/test/resources/log4j2-test.xml` still switches the extension's own loggers to debug,
  with a Null appender: Polarion's `logger.debug(() -> ...)` never evaluates its lambda otherwise, so
  a failure inside one would surface only on a server with debug logging on.
- **OpenAPI**: `docs/openapi.json` is regenerated by the swagger-jakarta plugin on build, and CI
  fails when the build changes it. The file is formatted by a pre-commit hook, so CI installs
  pre-commit before building.
- **Generated files end with a newline.** A file written without one is rewritten by the
  end-of-file pre-commit hook, which aborts the commit.
- **Commits**: a pre-commit hook requires `user.email` to match
  `firstname.lastname@intechcore.com`. Breaking changes use `feat!: ...` or a `BREAKING CHANGE:`
  footer.
