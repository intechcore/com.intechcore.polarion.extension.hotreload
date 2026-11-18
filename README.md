# Hot Reload for Polarion ALM

[![CI](https://github.com/intechcore/com.intechcore.polarion.extension.hotreload/actions/workflows/ci.yml/badge.svg)](https://github.com/intechcore/com.intechcore.polarion.extension.hotreload/actions/workflows/ci.yml)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/intechcore/com.intechcore.polarion.extension.hotreload/badge)](https://scorecard.dev/viewer/?uri=github.com/intechcore/com.intechcore.polarion.extension.hotreload)
[![Release](https://img.shields.io/github/v/release/intechcore/com.intechcore.polarion.extension.hotreload)](https://github.com/intechcore/com.intechcore.polarion.extension.hotreload/releases)
[![Maven Central](https://img.shields.io/maven-central/v/com.intechcore.polarion.extensions/com.intechcore.polarion.extension.hotreload)](https://central.sonatype.com/artifact/com.intechcore.polarion.extensions/com.intechcore.polarion.extension.hotreload)
[![Java 21+](https://img.shields.io/badge/java-21+-blue.svg)](https://openjdk.org/)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=intechcore_com.intechcore.polarion.extension.hotreload&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=intechcore_com.intechcore.polarion.extension.hotreload)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=intechcore_com.intechcore.polarion.extension.hotreload&metric=coverage)](https://sonarcloud.io/summary/new_code?id=intechcore_com.intechcore.polarion.extension.hotreload)
[![Duplicated Lines (%)](https://sonarcloud.io/api/project_badges/measure?project=intechcore_com.intechcore.polarion.extension.hotreload&metric=duplicated_lines_density)](https://sonarcloud.io/summary/new_code?id=intechcore_com.intechcore.polarion.extension.hotreload)
[![Lines of Code](https://sonarcloud.io/api/project_badges/measure?project=intechcore_com.intechcore.polarion.extension.hotreload&metric=ncloc)](https://sonarcloud.io/summary/new_code?id=intechcore_com.intechcore.polarion.extension.hotreload)
[![Reliability Rating](https://sonarcloud.io/api/project_badges/measure?project=intechcore_com.intechcore.polarion.extension.hotreload&metric=reliability_rating)](https://sonarcloud.io/summary/new_code?id=intechcore_com.intechcore.polarion.extension.hotreload)
[![Maintainability Rating](https://sonarcloud.io/api/project_badges/measure?project=intechcore_com.intechcore.polarion.extension.hotreload&metric=sqale_rating)](https://sonarcloud.io/summary/new_code?id=intechcore_com.intechcore.polarion.extension.hotreload)
[![Security Rating](https://sonarcloud.io/api/project_badges/measure?project=intechcore_com.intechcore.polarion.extension.hotreload&metric=security_rating)](https://sonarcloud.io/summary/new_code?id=intechcore_com.intechcore.polarion.extension.hotreload)
[![Bugs](https://sonarcloud.io/api/project_badges/measure?project=intechcore_com.intechcore.polarion.extension.hotreload&metric=bugs)](https://sonarcloud.io/summary/new_code?id=intechcore_com.intechcore.polarion.extension.hotreload)
[![Vulnerabilities](https://sonarcloud.io/api/project_badges/measure?project=intechcore_com.intechcore.polarion.extension.hotreload&metric=vulnerabilities)](https://sonarcloud.io/summary/new_code?id=intechcore_com.intechcore.polarion.extension.hotreload)
[![Code Smells](https://sonarcloud.io/api/project_badges/measure?project=intechcore_com.intechcore.polarion.extension.hotreload&metric=code_smells)](https://sonarcloud.io/summary/new_code?id=intechcore_com.intechcore.polarion.extension.hotreload)
[![Technical Debt](https://sonarcloud.io/api/project_badges/measure?project=intechcore_com.intechcore.polarion.extension.hotreload&metric=sqale_index)](https://sonarcloud.io/summary/new_code?id=intechcore_com.intechcore.polarion.extension.hotreload)

> [!IMPORTANT]
> Read the [Disclaimer](DISCLAIMER.md) before you use this extension. Use it on development servers only.

This Polarion extension installs, updates and uninstalls other Polarion extensions without a restart of Polarion.

Polarion 2606 reads its extensions only at startup. A new or changed extension jar becomes active only after a restart. This extension applies the changes in the extensions folder to the running server.

## Build

This extension can be produced using Maven:

```bash
mvn clean package
```

The build also compiles the React user interface in `ui/` and bundles it into the extension under `webapp/hotreload-app`. No separate Node installation is required.

## Installation to Polarion

The released jar is published to
[Maven Central](https://central.sonatype.com/artifact/com.intechcore.polarion.extensions/com.intechcore.polarion.extension.hotreload)
and attached to every [GitHub release](https://github.com/intechcore/com.intechcore.polarion.extension.hotreload/releases).

To install this extension, copy `com.intechcore.polarion.extension.hotreload-<version>.jar` to `<polarion_home>/polarion/extensions/com.intechcore.polarion.extension.hotreload/eclipse/plugins`. The Maven build can do it for you:

```bash
mvn clean install -P local-install-into-polarion
```

The `POLARION_HOME` environment variable must point to the Polarion installation folder.

This extension takes effect only after a restart of Polarion. It cannot redeploy itself: a rescan lists a new jar of it under "Restart required".

GitHub releases carry a signed provenance bundle. Verify a downloaded jar with
`gh attestation verify <file> --repo intechcore/com.intechcore.polarion.extension.hotreload`.

## Polarion configuration

The extension adds the **Hot Reload** section to the Administration menu of the global scope. The section holds the **About**, **Disclaimer**, **REST API** and **Rescan Extensions** pages. Only global administrators can run a rescan.

## Usage

1. Copy, replace or delete extension jars in `<polarion_home>/polarion/extensions/<extension>/eclipse/plugins`.
2. Open **Administration > Hot Reload > Rescan Extensions**.
3. Click **Show changes** to see what the extension found.
4. Click **Rescan and apply** to apply the changes.
5. Reload the Polarion page.

The extension compares the jars in the extensions folder with the running extensions:

| Change | When |
|---|---|
| Install | A jar for an extension that does not run yet. |
| Update | The content of the jar of a running extension differs from the content at startup or at the last rescan. |
| Replace | The jar of a running extension has a new file name, for example after a version change. |
| Uninstall | The jar of a running extension is gone. |
| Cleanup | An extension failed to install, and its jar is gone. The workflow contributions and job factories it left are removed. |

Extensions that depend on a changed extension are refreshed too. The report lists every step.

When two jars have the same `Bundle-SymbolicName`, the jar with the higher `Bundle-Version` is used. The report shows a warning. Remove the other jar.

When a step fails, the extension puts back every extension that still resolves. The report lists the errors. An extension that did not come back stays a change, so the next rescan tries it again.

## What is refreshed

| Contribution | Refreshed |
|---|---|
| Web applications (`com.polarion.portal.tomcat.webapps` in `plugin.xml`) | Yes |
| Scripts on every page (`com.polarion.portal.tomcat.externalJsFile` in `plugin.xml`) | Yes, Polarion serves them from the new revision. Browsers cache them for the Polarion build, so a changed file needs a cleared browser cache, also after a restart. |
| Administration pages (`com.polarion.xray.webui.administrationPageExtenders`) | Yes |
| Workflow functions (`com.polarion.alm.tracker.workflow.functions`) | Yes |
| Workflow conditions, validators and signature policies | Existing ones use the new class. New ones need a restart. |
| LiveReport page widgets (`com.polarion.alm.richpages.widgets`) | Yes |
| Velocity context of LiveReport pages (`com.polarion.alm.richpages.velocityContext`) | `instance:` values only. Other values keep the startup object. |
| Velocity context of wiki pages (`com.polarion.alm.wiki.renderingContext`) | `instance:` values only. Other values keep the startup object. |
| Navigation extenders (`com.polarion.xray.webui.customNavigationExtenders`) | Yes |
| Job unit factories (`com.polarion.platform.jobs.configuration`) | Yes, for a factory service built by a plain `<construct class>`. A running job finishes with the old code. |
| HiveMind service implementations and interceptors (`<implementation>`) | No, restart required |
| Guice modules (`Guice-Modules` manifest header) | No, restart required |
| Other HiveMind configuration points | No, restart required |

The report lists every contribution that needs a restart.

When the Admin Menu Organizer extension is installed, the extension applies its menu order again.

## REST API

This extension provides a REST API. Its OpenAPI specification can be obtained [here](docs/openapi.json).

Scripts and build pipelines can run a rescan through it. The base URL is `<polarion_url>/polarion/hotreload/rest/api`.

| Method | Path | Action |
|---|---|---|
| `GET` | `/changes` | Lists the changes in the extensions folder. Applies nothing. |
| `POST` | `/rescan` | Applies the changes in the extensions folder. |

Authenticate with a personal access token of a global administrator. Other users get HTTP 403.

```bash
curl -X POST -H "Authorization: Bearer <token>" http://localhost/polarion/hotreload/rest/api/rescan
```

Both endpoints return the same report as the administration page:

| Field | Content |
|---|---|
| `applied` | `false` for `GET /changes`, `true` for `POST /rescan`. |
| `changes` | One line for each change found: `INSTALL`, `UPDATE`, `REPLACE`, `UNINSTALL` or `CLEANUP`. |
| `log` | The steps done, in order. |
| `restartRequired` | The contributions that only a restart applies. |
| `errors` | The failures. If this list is not empty, restart Polarion. |

A typical development cycle:

1. Build the changed extension with `mvn clean install -P local-install-into-polarion`.
2. Call `POST /rescan`.
3. Reload the Polarion page.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Report vulnerabilities privately, see [SECURITY.md](SECURITY.md).

## Disclaimer

This extension is provided "as is", without warranty of any kind, as the [LICENSE](LICENSE) states.
Use it at your own risk. Intechcore GmbH is not liable for damage from its use, as far as the law
allows. It is published free of charge, outside of any commercial offering, with no obligation to
support it. Security reports are welcome, see [SECURITY.md](SECURITY.md).

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
