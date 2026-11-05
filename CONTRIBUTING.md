# Contribution workflow

Consumer documentation belongs in the [Starlight site](site). Repository documentation is for maintainers.
Shoostr uses [Apache License 2.0](LICENSE). Retain third-party license and notice files.

For every tracked repository change, including documentation and benchmark fixtures:

1. Create a separate branch from the current `main`.
2. Commit and push the branch to the remote, then open a pull request targeting `main`.
3. Fix failures on that branch and push again until CI passes for the pull request's latest commit. The Gradle build and test validation must succeed. Trusted runs also require a passing Sonar quality gate and zero open issues.
4. Leave the pull request unmerged for review unless the user authorizes merging it.

Check available Gradle dependency and plugin updates with
`./gradlew dependencyUpdates --reject-pre-releases --output-formatter plain,json`.
The root task covers this build's projects and writes `build/dependencyUpdates/report.txt`
and `build/dependencyUpdates/report.json`; the separate `buildSrc` build is excluded. Review proposed upgrades
and verify vulnerability fixes separately. Use `./gradlew buildEnvironment` to inspect
transitive buildscript dependencies when investigating build-tool findings.

## CI and external contributions

The required `build` check requires Java validation, documentation and successful example
selection. Pull requests run container checks for changed example folders; shared library,
build or CI tooling changes select all examples. An empty selection explicitly skips
containers. Failed selection or an unexpected skipped job fails the gate. Pushes to `main`
and manual runs check every example. All example Java tests run in the ordinary build.
External contributions do not need a replacement pull request on a maintainer branch.

Trusted pull requests and CI runs in `SuppieRK/Shoostr` must pass the Sonar quality gate
and have zero open issues. A missing analysis token, scanner failure, failed quality gate,
API error or nonzero issue count fails validation and the required `build` check.
Fork and Dependabot pull requests run validation without Sonar; repository secrets remain
restricted to trusted analysis.

The `validation-results` artifact contains JUnit reports, published-consumer and MCP
conformance evidence, CI-script coverage and scanner task metadata, including after failure.

The aggregate `build` job runs even when another required job fails or is skipped. Main's ruleset
requires `build` from GitHub Actions and an up-to-date branch; the existing administrator
bypass is retained.

# Publish library artifacts

Publish a GitHub Release to start automated Maven Central publication. The workflow uses
[`com.vanniktech.maven.publish.base`](https://vanniktech.github.io/gradle-maven-publish-plugin/central/)
to sign and publish the selected libraries in one deployment and wait for `PUBLISHED`.
Each artifact has its own version; a batch can contain different versions.

Select a library by setting a new stable `version` in its existing `build.gradle`.
The workflow selects stable coordinates that are not already on Central. Leave deferred
libraries on a snapshot or an already-published version. There is no separate release-plan
file or automatic version allocator. A new stable version marks a library ready for the
next release, even if another library is the reason for creating that GitHub Release.

Use `YYYY.MM.D.PATCH`, for example `2026.11.4.0`. Use a two-digit month and an unpadded
day. Start the counter at `0` for each artifact and release date; increment it for further
publications of that artifact on the same date. Maintain only the latest release of each
artifact initially. Describe deliberate breaking changes and migration steps in its notes.
GitHub prereleases and snapshot publication are not supported by this workflow.

Production internal dependencies declare their own versions through `shoostrDependency`.
For example, MCP can advance while retaining an earlier core version:

```groovy
version = '2026.11.4.1'

dependencies {
    api shoostrDependency('core', '2026.11.3.0')
}
```

Ordinary development builds use workspace projects. Publication builds compile against
the declared binaries, using locally staged dependencies selected in the same batch.
Changing core's version does not change MCP's declared dependency. The first publication
of MCP also needs compatible core and HTTP publications. Include an unpublished dependency
by giving it a stable version and using that version in the dependent library.

The [installation reference](site/src/content/docs/start/libraries.md) shows published
core and test coordinates.

1. Compare each library with its own last successful publication. Review production
   inputs, dependency changes and shared build changes. Set selected artifact versions
   and change internal dependency versions only deliberately.
2. Commit through the contribution workflow and merge the reviewed changes. Wait for
   successful [main CI](#ci-and-external-contributions) on that exact revision, including
   Sonar. The publication workflow requires that result and main-branch ancestry.
3. Draft a GitHub Release with one tag for that verified revision. The tag identifies
   the source batch; it does not need to encode every artifact version. List selected
   coordinates, dependency changes and any breaking changes or migration instructions.
4. Publish the release. The workflow checks the source and stages selected libraries in
   dependency order. Each compiles against its pinned dependency binaries before the
   plugin signs and publishes the batch.
5. Check the workflow result and its `central-publication-<run-id>` artifact. The plugin
   logs the deployment ID and waits for `PUBLISHED`. Verify the selected coordinates resolve
   from Central before updating installation examples. Public consumer checks and release
   notes are maintainer tasks.

Publication runs are serialized. Do not rerun a failed publication workflow as an upload
retry. Inspect the plugin logs and deployment in the Central Portal first: a lost response
can leave an upload active even when repository lookups still return 404. After reconciling
the deployment, use a new GitHub Release for a verified revision if another upload is needed.
If artifacts are already public, verify them and record the result on the existing release.
Published versions are immutable; corrections require new versions. A GitHub Release alone
does not prove Central publication succeeded.

References: [GitHub release events](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#release)
and [Central requirements](https://central.sonatype.org/publish/requirements/).

# Documentation

The Starlight site in `site/` is for library consumers.
The root README provides a short product overview. Other repository documents are for maintainers.
Keep contribution procedures, build diagnostics, provenance, and agent instructions out of the consumer site.
Show focused library composition through public Shoostr interfaces, with caller-owned
configuration and resources. Keep dependency-wiring, dependency-injection and general service
architecture guides out of the consumer site.
Library dependency declarations are separate from application dependency wiring.

### Build and preview

Use Node.js 22.22.0 or later. Run these commands from the repository root:

```sh
npm --prefix site ci
npm --prefix site run build
npm --prefix site run preview
```

Open the local address printed by the preview command. Press Ctrl+C to stop it.
The preview serves the production build, including the search index.
`npm --prefix site run dev` supports editing, but its search box does not run Pagefind.

Test repository-path hosting with the same commands and these build inputs:

```sh
SITE_URL=https://example.invalid BASE_PATH=/Shoostr/ npm --prefix site run build
SITE_URL=https://example.invalid BASE_PATH=/Shoostr/ npm --prefix site run preview
```

Open `/Shoostr/` on the printed local origin. Check navigation, assets, copy buttons, and search results in both themes.
The example origin is not a published site. Set the real origin and repository path before publication.
The output in `site/dist/` is the static GitHub Pages artifact. Keep the complete repository
checkout when building: topic pages import their example and test classes from `examples/`.

Install Firefox and run the production browser checks after building, using the same
`SITE_URL` and `BASE_PATH` values for the build and checks:

```sh
npm --prefix site exec -- playwright install firefox
SITE_URL=https://suppierk.github.io BASE_PATH=/Shoostr/ npm --prefix site run build
SITE_URL=https://suppierk.github.io BASE_PATH=/Shoostr/ npm --prefix site run test:publication
```

Playwright starts and stops its own preview on port 4322. Stop any other server on that
port before running the checks. The checks cover generated links and assets, the first-request
journey, both themes, mobile navigation, code-copy controls, production search and the 404 page.

### Publish the site

Consumer documentation is hosted at <https://suppierk.github.io/Shoostr/>. The
[CI workflow](.github/workflows/ci.yml) builds and checks it in the `documentation` job
on pull requests and main-branch runs, using `SITE_URL=https://suppierk.github.io` and
`BASE_PATH=/Shoostr/`. Changes to example classes also update the rendered documentation,
so the job runs for every change.

After a merge to `main`, publication waits for the complete CI gate: Java validation,
example containers, documentation checks and the applicable Sonar quality gate with zero open
issues. The deployment job publishes that run's `site/dist/` artifact. Deployments are
serialized, and a run skips publication if `main` has advanced. Pull requests and non-main
manual runs cannot publish. To retry publication, rerun CI for the current `main` revision or
dispatch the CI workflow on `main`; its validation requirements remain the same.

In [Pages settings](https://github.com/SuppieRK/Shoostr/settings/pages), select **GitHub Actions**
as the publishing source. Keep the `github-pages` environment restricted to the `main` branch
in [environment settings](https://github.com/SuppieRK/Shoostr/settings/environments).
Only the deployment job receives Pages-write and identity-token permissions.

For a failed publication, inspect the `documentation`, `build` and `publish-documentation`
jobs in [Actions](https://github.com/SuppieRK/Shoostr/actions/workflows/ci.yml).
Failed browser checks retain traces in the `documentation-browser-results` artifact.
After the first deployment, verify the landing page, a nested topic and search on the public URL.

Build the library and verify examples separately:

```sh
./gradlew clean spotlessApply build
```

Node tooling is independent of the Gradle build and the library runtime.

### Add a consumer topic

Give each task one owner under `site/src/content/docs/`. Add its completed page to the sidebar in `site/astro.config.mjs`.
Keep navigation shallow. A reader must reach a task from the landing page within three clicks, including mobile menus.
Check search with task phrases, not only exact page titles. Do not publish empty topic pages or planned-content navigation.

Search indexes each page's frontmatter description alongside its title and full content.
Write the description as a concise summary of the task, making request and response scope clear where needed.
Keep that summary in one place; `SearchHead.astro` supplies it to Pagefind without changing Starlight's ranking defaults.
The same adapter replaces ASCII hyphens with spaces in search-title metadata to prevent compound titles from outranking the task pages in the checked queries.
Search-result labels therefore use spaces, while page headings and browser titles retain their original punctuation.

Verify ranking through the production search dialog, because a bare Pagefind import does not use Starlight's configured ranking.
Use task phrases such as “request header limit” or “response charset” and check that the topic comes first.
For shared identifiers or broad terms such as “cookies”, check the relevant topics among the first three distinct pages instead of assigning an artificial single owner.
Keep complete examples and tests searchable, and repeat the checks at both hosting paths in light and dark themes.
Do not add page-specific weights or keyword lists to compensate for a ranking failure.

The final build hook indexes `/` and `+` as technical token characters, so searches
such as `application/problem+json` retain the same punctuation as the indexed code.
It rebuilds the index after Starlight while retaining the native search dialog and
ranking defaults; indexing errors fail the build rather than keeping a stale index.

Use this page sequence:

1. Description: result, any additional library the task requires, essential limits, and ownership.
2. Example: complete classes, including package, imports, and all required supporting classes.
3. Test: complete test classes, including package, imports, fixtures, and assertions for the documented behavior.

Assume readers already have Shoostr, their own source code, and their own build system.
After getting started, assume the base libraries and Java version are already configured.
Do not repeat setup prerequisites or repository example numbering in recipe prose.
Mention an additional library only when the task requires it.
Consumer recipes provide code for a task and code to test it. Omit cURL, build commands,
repository-checkout instructions, and assumptions about the reader's project layout.
Build and preview commands belong in maintainer documentation only.

The library-selection reference may show Maven and Gradle dependency declarations.
Label tentative coordinates and version placeholders clearly. Keep test libraries in test scope.
This exception does not permit build commands, checkout instructions, or build-system setup in consumer recipes.

Import complete compiled Java files with `?raw` and render them with Starlight's `Code` component.
Keep the source and its tests under the example project. Do not maintain a second Java copy in documentation.
Adjust or split examples when the topic requires it. Existing examples do not determine the documentation scope.
Use JDK annotations only in application examples. Tests may also use JUnit 5 annotations.

Add short in-code comments at the steps that need explanation: scope selection, ordering,
ownership transfers, protocol choices, and failure handling. Explain why the example takes a
step and what changes for the caller; do not narrate obvious Java syntax or add a comment quota.
Use the fluent `Routes` and `TypedRoutes` methods for adjacent registrations on the same scope.
Keep child callbacks and setup boundaries clear, and preserve registration order. A chain returns
the current scope, not a builder for the last endpoint; a scope-wide setting can affect earlier routes.

Show `new Shoostr()` and the application's fluent configuration in the example. Do not wrap
that definition in setup `try`/`catch` blocks, cleanup flags, or equivalent scaffolding, and do
not replace it with a route-only definition. Remove catches that only rethrow an exception or
duplicate lifecycle cleanup. Keep task-specific error handling and ordinary test
try-with-resources where they belong.

Verify the rendered classes match their source files. Run their Gradle tests and the static site build.
Check desktop and mobile navigation, keyboard focus, code copying, and a production search result.
Repeat hosting checks for `/` and the repository base path. Keep private research and maintainer files out of the site output.

### STE-based writing profile

This project uses a profile based on Simplified Technical English.
It does not claim formal ASD-STE100 compliance or enforce its complete dictionary.

| Rule                       | Requirement                                                                                                                                                                                             |
| -------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| W01 — Reader task          | Use task-focused titles. State the result before background.                                                                                                                                            |
| W02 — Direct steps         | Use active voice and imperative verbs. Give each instruction one main action.                                                                                                                           |
| W03 — Natural flow         | Use concise, connected prose with varied sentence lengths. Keep related ideas together rather than splitting paragraphs into abrupt statements.                                                         |
| W04 — Stable terms         | Use one term for one concept. Preserve exact identifiers, commands, protocol fields, and errors.                                                                                                        |
| W05 — Complete recipe      | Show the task and its test as complete classes. State additional library requirements, essential constraints, and ownership. Do not repeat initial setup.                                               |
| W06 — Essential limits     | State ownership, lifetime, security, and protocol limits where the reader acts.                                                                                                                         |
| W07 — Evidence             | Display compiled source files. Link primary references for deeper concepts. Label uncertainty and historical evidence.                                                                                  |
| W08 — Audience             | Keep consumer tasks in Starlight and maintainer procedures on GitHub. Keep READMEs short.                                                                                                               |
| W09 — Clean examples       | Keep Sonar-specific suppressions, rule IDs, and analyzer comments out of example source. Explain resource ownership in ordinary terms.                                                                  |
| W10 — Java fluency         | Explain task-specific choices and non-obvious contracts, not assertions, standard Java syntax, or automatic resource closure.                                                                           |
| W11 — In-code explanations | Comment meaningful choices, scope, ordering, ownership, and failure paths where the reader encounters them. Keep already-clear code concise.                                                            |
| W12 — Fluent registration  | Chain adjacent registrations on the same route scope, preserving call order and child boundaries. Keep setup and unrelated operations separate.                                                         |
| W13 — Application setup    | Show application construction and fluent configuration directly, without setup catch blocks, cleanup scaffolding, or route-only substitutes.                                                            |
| W14 — Concept order        | Explain framework-specific concepts before relying on them in claims or examples. Introduce an example's purpose and needed concepts before its code; a forward link does not replace that explanation. |

Review prose in context instead of treating sentence length or automated word counts as proof of clarity.
Use short sentences where they help, such as a clear warning, without making every paragraph read like a checklist.

### Documentation review

Use the knowledge-base architect before changing structure or canonical ownership.
Use the writing reviewer against this profile and the technical reviewer before documentation completion.
Record each review's scope, findings and outcome before delivery.

References: [Starlight setup](https://starlight.astro.build/manual-setup/),
[whole-file code imports](https://starlight.astro.build/components/code/),
[site search](https://starlight.astro.build/guides/site-search/), and
[Astro GitHub Pages configuration](https://docs.astro.build/en/guides/deploy/github/).

## Testing and provenance

Use JUnit 5 tests at public behavior boundaries. Preserve applicable source references and license notices when reusing third-party code.

## Diagnostic mutation testing

Run the full eight-library diagnostic suite from the repository root with Java 21:

```sh
./gradlew mutationTest --console=plain
```

The root task delegates to `:core:pitest`, `:http:pitest`, `:micrometer:pitest`,
`:opentelemetry:pitest`, `:pac4j:pitest`, `:test:pitest`, `:openapi:pitest`, and
`:mcp:pitest`. The plugin generates
its standard HTML report (`index.html`) and XML (`mutations.xml`) under
`<module>/build/reports/pitest/<timestamp>/`. Open the HTML report to inspect surviving
changes and covering tests. Timestamped runs preserve earlier reports until `clean`;
copy the entire report directory outside `build` before cleaning when retaining a
baseline.

`mutationTest` is independent of `build`, `check`, and CI. Surviving mutations are
diagnostic findings: there is no mutation-score, coverage, test-strength, or surviving
mutation threshold. Invalid configuration, a failing unmutated test, or a tool error
must still fail execution. Existing Gradle `Test` tasks continue to use JUnit Platform;
PIT uses its maintained JUnit plugin to discover/run the same Jupiter test sources in
isolated child JVMs, without a project-defined runner or a second test programming model.

For a module-specific diagnostic rerun, use its task directly, for example
`./gradlew :http:pitest --console=plain`. This does not narrow the full task.

### Targets and exclusions

| Module          | Production targets and tests                      | Reason                                                                                                      |
| --------------- | ------------------------------------------------- | ----------------------------------------------------------------------------------------------------------- |
| `core`          | All module-local production classes and all tests | Routing, requests/responses, streaming, transport/lifecycle, files, browser security and session contracts. |
| `http`          | All module-local production classes and all tests | HTTP value validation, formatting, parsing and exception/status contracts.                                  |
| `micrometer`    | All module-local production classes and all tests | Metrics registration, request measurements and cleanup.                                                     |
| `opentelemetry` | All module-local production classes and all tests | Instrumentation scope, span completion and propagation.                                                     |
| `openapi`       | All module-local production classes and all tests | OpenAPI generation, document delivery, and UI configuration.                                                |
| `mcp`           | All module-local production classes and all tests | MCP transport, protocol dispatch, and session ownership.                                                    |
| `pac4j`         | All module-local production classes and all tests | Authentication/authorization adaptation and request context.                                                |
| `test`          | All module-local production classes and all tests | Consumer test application/client and resource ownership.                                                    |

Both class and test selectors are `io.github.suppierk.*`, including nested classes;
PIT's mutable code paths are each module's own production output. Dependency classes
are not mutated through their consumers. No client-facing class or test is explicitly
excluded. Examples, benchmark fixtures and build logic are outside the eight
library modules. Standard PIT bytecode filters still apply; generated/no-executable
code is not evidence of an untested user-visible contract.

Use PIT's `DEFAULTS` mutator set with two workers. No project-specific mutation
exclusions, hand-written mutators or score-driven test generation are added. The
existing tests include HTTP requests where the observable contract needs
them; add regression tests at the smallest established construction/routing/request
boundary that demonstrates a meaningful surviving fault.

### Tool versions and sources

Versions are pinned inline, following the repository's Gradle dependency convention:
Gradle plugin 1.19.0, PIT 1.30.1 and PIT JUnit plugin 1.2.3. Tests use the existing
repository Jupiter dependencies; do not downgrade them or introduce JUnit 4.

Sources checked on 2026-09-26:

- [Gradle plugin release](https://plugins.gradle.org/plugin/info.solidsoft.pitest).
- [Gradle plugin configuration and JUnit support](https://github.com/szpak/gradle-pitest-plugin).
- [PIT releases](https://github.com/hcoles/pitest/releases) and
  [change history](https://github.com/hcoles/pitest/blob/master/README.md).
- [PIT JUnit plugin requirements](https://github.com/pitest/pitest-junit5-plugin).
- [PIT options](https://pitest.org/quickstart/commandline/).


## Packaged OpenAPI UI provenance

Generation uses Swagger Core 3.0.0 models and its Jackson 3 OpenAPI 3.1 `Json31` serializer.
UI CSS and bundle are extracted at build time from
[`org.webjars:swagger-ui:5.34.0`](https://repo.maven.apache.org/maven2/org/webjars/swagger-ui/5.34.0/).
The SHA-256 `a439f1bd0d0c69667e97fac5a8cdfb3cc599ed9bdb31fbf40602c648dd2e1a63`
is enforced before packaging. The WebJar is build-only; it is not published as a runtime dependency.
No CDN or runtime download is needed. Preserve the upstream Apache LICENSE/NOTICE and
bundle third-party notices under `META-INF/licenses/swagger-ui-5.34.0/`.

## Example container validation

The standalone `ExampleApplication.java` launchers execute in the selected Docker matrix,
outside the JUnit JVM measured by JaCoCo. Their exact paths are excluded only from Sonar coverage
attribution. All source-analysis rules and the zero-open-issues check still apply to them;
factory and framework coverage remain subject to the existing quality gate. The Dockerfile and
Jib paths require observable startup, resource-configuration, HTTP/protocol and graceful-exit
checks. These are behavioral checks, not measured JaCoCo line or branch coverage.

Every runnable Gradle example has its own Dockerfile and Compose file. Jib is configured
only for example applications through `config/gradle/example-container.gradle`.
Dockerfiles build with a pinned Java 21 JDK and run with a pinned Java 21 JRE;
the default `runtime` target installs the Gradle application distribution. The optional
`jib` target exports the native `jibBuildTar` output for `docker load`, without a host JDK,
Docker socket mount or registry push. Each example README gives exact commands and credentials.

Run `node --test .github/ci/example-containers.test.mjs` from the root for the inventory check.
Set `EXAMPLE` to a registered example project and `BASE_URL` to its running listener for
bounded public HTTP/protocol checks. An empty or unknown selection fails. These checks
complement ordinary JUnit tests; they do not replace the observability backend tests,
MCP Inspector checks or official MCP conformance suite.

Pushes to `main` and manual CI runs build all 22 examples through both distribution and
Jib images. Pull requests select examples from changed files. Changes under an example
folder select that example; shared library, build and CI changes select the full matrix.
Unrelated site, root documentation and benchmark changes skip containers. Nested example
folders can also select their parent example.

Each selected example is checked through both image paths for image identity, nonroot/read-only
configuration, public behavior and graceful exit. Resource launchers also check external-file
configuration failures. The metrics and tracing backend suites run once per example; MCP
Inspector runs with both images. App logs and backend test diagnostics are retained.
The required `build` check waits for the selected matrix, Java validation, documentation
and applicable Sonar checks.

References: [Jib image tarballs](https://github.com/GoogleContainerTools/jib/tree/master/jib-gradle-plugin#build-an-image-tarball),
[Docker multi-stage builds](https://docs.docker.com/build/building/multi-stage/),
and [local artifact export](https://docs.docker.com/build/exporters/local-tar/).
