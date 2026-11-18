# CLAUDE.md

Guidance for working in this repository.

## What this is

`com.intechcore.polarion.extension.hotreload` - a Polarion ALM extension that installs, updates
and uninstalls other extensions in a running Polarion. It builds on the SBB
`ch.sbb.polarion.extension.generic` framework (parent POM) and targets
**Polarion 2606 / Tomcat 11 / Jakarta EE 11**.

It adds the **Hot Reload** section to the global Administration menu with four pages:
About, Disclaimer, REST API and Rescan Extensions. It has no settings yet.

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
- **This extension cannot redeploy itself.** A new build of it needs a Polarion restart. A rescan
  lists the new jar under "Restart required".
- **The only real check is a rescan on a running Polarion.** `touch` does not make a jar count as
  updated: the scanner compares SHA-256 hashes of the content. It falls back to the file time only for
  a bundle whose jar could not be hashed at startup. Change the content, for example add a
  line to a file in the jar. An applied rescan also writes its report steps to the Polarion main log,
  with the prefix `hotreload:`. A dry run writes only to the report.

UI dev loop (hot reload against a running Polarion):

```bash
cd ui
cp .env.local.template .env.local   # set VITE_BASE_URL and (optional) VITE_BEARER_TOKEN
npm run dev                          # http://localhost:5173/?feature=rescan (or about, disclaimer)
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

- `rest/HotReloadRestApplication` - a `GenericRestApplication` with the two rescan controllers. It
  registers no settings, so generic serves no settings endpoints.
- `rest/controller/HotReloadInternalController` - session-authenticated `/internal/changes` and
  `/internal/rescan` for the Rescan page, `@Hidden`, global administrators only. The rescan requires
  the header `X-Requested-With: XMLHttpRequest` against cross-site requests.
- `rest/controller/HotReloadApiController` - the PAT-authenticated `/api` twin. It runs the internal
  controller as the token user through `ISecurityService.doAsUser`.
- `rest/controller/OpenAPIInfo` - the annotation the swagger plugin turns into `docs/openapi.json`.
- `service/RedeployService` - the rescan: finds changes (`ExtensionScanner`), takes old contexts and
  contributions out, changes and refreshes the bundles, then puts the new ones back. After a failed bundle
  change it still refreshes and puts back every bundle that resolves. A bundle that did not come back
  stays a change. A refresh timeout or an interrupt stops the rescan before the put-back: then restart.
- `service/ExtensionScanner` - compares the jars with the bundles: `INSTALL`, `UPDATE`, `REPLACE`,
  `UNINSTALL`. The higher `Bundle-Version` wins between two jars of one name. An unreadable jar is
  skipped and never uninstalls or replaces its bundle.
- `service/JarFingerprints` - SHA-256 of each bundle's jar, taken at startup and after each applied
  change. With a hash, `UPDATE` means the content differs.
- `service/DescriptorCache` - the descriptors of the running revisions, for an uninstall after the jar
  is gone.
- `inject/TomcatDeployer`, `inject/ContributionInjector` - swap Tomcat contexts and HiveMind
  contributions inside Polarion's singletons. Shared maps are replaced by a changed copy, never changed
  in place. `internal/Reflect` does every reflective call.
- `inject/WorkflowContributions` - the internal maps of `ContributionsManager`. Conditions, validators
  and signature policies keep their holder and get the class of the new revision. Workflow functions
  are created again from the descriptor.
- `inject/PendingWorkflowHolders` - workflow holders of a bundle that did not come back. They wait
  across rescans. A rescan reports `CLEANUP` when their jar is gone too.
- `inject/JobUnitFactories` - replaces the job unit factories through the public `IJobUnitRepository`
  API. The scheduler looks a factory up by name for each run. `Import-Package` lists
  `com.polarion.platform.jobs` for it.
- `descriptor/DescriptorParser` - reads `plugin.xml`, `hivemodule.xml` and `Guice-Modules`.
- `DescriptorSnapshotListener` - fills `DescriptorCache` and builds lazy services at server start.
- `HotReloadAppServlet` - a `GenericUiServlet` subclass serving the
  `hotreload-app` webapp context.

**React** (`ui/`) - Vite + React 19 + TypeScript SPA on the shared
`@sbb-polarion/react-sbb-polarion` (RSP) library. One app, page selected by `?feature=`:

- `pages/Rescan.tsx` - the Rescan Extensions page: shows and applies the rescan report.
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

Rescan internals:

- **No public API.** Polarion has no API for most of this. Every internal goes through
  `internal/Reflect`, which loads classes from their owning bundle. Job unit factories are the
  exception: they use the public jobs API. A Polarion upgrade breaks it at
  runtime, not at build.
- **HiveMind proxies.** Services from `hivemodule.xml` are wrapped in up to three proxies (`_inner`,
  `_serviceModel`, `_service`). Compare class loaders only after `Reflect.unwrapHiveMindProxy`.
- **Lazy services.** HiveMind builds `ContributionsManager` on first use. Built after a jar was
  deleted, it stops half way and loses Polarion's own workflow functions.
  `ContributionInjector.buildLazyServices()` must run at server start.
- **Deleted jar.** `Bundle.getEntry()` of a bundle whose jar is gone returns `null` without an
  error. Uninstall reads descriptors from `DescriptorCache`, filled at server start and after each
  rescan.
- **Admin page keys.** `AdministrationPageExtender.getId()` and `getParentNodeId()` derive missing
  values from the names. Build keys through these getters, never from the XML attributes.
- **Menu registry.** admin-menu-organizer reads the menu order from the HiveMind configuration point,
  not from the live menu. Every menu change must also update that configuration point.
- **Refresh cascade.** `refreshBundles` also refreshes every bundle that uses `Require-Bundle` on a
  changed one. Their contributions must be redone too.
- **Class singletons.** All consumers reached through Guice are bound `in(Singleton.class)`, so
  `getInstance` returns the live object. Check the binding before adding a new consumer.

General:

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
