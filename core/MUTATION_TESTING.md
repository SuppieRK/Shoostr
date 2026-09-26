# Diagnostic mutation testing

Run the full six-library diagnostic suite from the repository root with Java 25:

```sh
cmdshape ./gradlew mutationTest --console=plain
```

The root task delegates to `:core:pitest`, `:http:pitest`, `:micrometer:pitest`,
`:opentelemetry:pitest`, `:pac4j:pitest` and `:test-support:pitest`. The plugin generates
its standard HTML report (`index.html`) and XML (`mutations.xml`) under
`<module>/build/reports/pitest/<timestamp>/`. Open the HTML report to inspect surviving
changes and covering tests. Timestamped runs preserve earlier reports until `clean`;
copy the entire report directory outside `build` before cleaning when retaining a
baseline. Native reports from issue 48 are retained locally under
`.scratch/java25-web-framework/mutation/issue48/` alongside execution logs.

`mutationTest` is independent of `build`, `check`, and CI. Surviving mutations are
diagnostic findings: there is no mutation-score, coverage, test-strength, or surviving
mutation threshold. Invalid configuration, a failing unmutated test, or a tool error
must still fail execution. Existing Gradle `Test` tasks continue to use JUnit Platform;
PIT uses its maintained JUnit plugin to discover/run the same Jupiter test sources in
isolated child JVMs, without a project-defined runner or a second test programming model.

For a module-specific diagnostic rerun, use its task directly, for example
`cmdshape ./gradlew :http:pitest --console=plain`. This does not narrow the full task.

## Targets and exclusions

| Module | Production targets and tests | Reason |
| --- | --- | --- |
| `core` | All module-local production classes and all tests | Routing, requests/responses, streaming, transport/lifecycle, files, browser security and session contracts. |
| `http` | All module-local production classes and all tests | HTTP value validation, formatting, parsing and exception/status contracts. |
| `micrometer` | All module-local production classes and all tests | Metrics registration, request measurements and cleanup. |
| `opentelemetry` | All module-local production classes and all tests | Instrumentation scope, span completion and propagation. |
| `pac4j` | All module-local production classes and all tests | Authentication/authorization adaptation and request context. |
| `test-support` | All module-local production classes and all tests | Consumer test application/client and resource ownership. |

Both class and test selectors are `io.github.suppierk.*`, including nested classes;
PIT's mutable code paths are each module's own production output. Dependency classes
are not mutated through their consumers. No client-facing class or test is explicitly
excluded. Examples, benchmark fixtures and build logic are outside the six requested
library modules. Standard PIT bytecode filters still apply; generated/no-executable
code is not evidence of an untested user-visible contract.

Use PIT's `DEFAULTS` mutator set with two workers. No project-specific mutation
exclusions, hand-written mutators or score-driven test generation are added. The
existing tests include HTTP requests where the observable contract needs
them; add regression tests at the smallest established construction/routing/request
boundary that demonstrates a meaningful surviving fault.

## Tool versions and sources

Versions are pinned inline, following the repository's Gradle dependency convention:
Gradle plugin 1.19.0, PIT 1.30.0 and PIT JUnit plugin 1.2.3. Tests use the existing
repository Jupiter dependencies; do not downgrade them or introduce JUnit 4.

Sources checked on 2026-09-26:

- [Gradle plugin release](https://plugins.gradle.org/plugin/info.solidsoft.pitest).
- [Gradle plugin configuration and JUnit support](https://github.com/szpak/gradle-pitest-plugin).
- [PIT releases](https://github.com/hcoles/pitest/releases) and
  [change history](https://github.com/hcoles/pitest/blob/master/README.md).
- [PIT JUnit plugin requirements](https://github.com/pitest/pitest-junit5-plugin).
- [PIT options](https://pitest.org/quickstart/commandline/).

## Diagnostic results

The initial pass collected findings before a focused regression plan. Follow-up
tests use agreed public boundaries: value construction, route registration and
handler request/response APIs, plus application-configured integration providers.
They protect meaningful behavior rather than targeting a mutation-score threshold.

The 2026-09-26 full baseline completed successfully in 54 minutes: 2,339 mutations,
1,540 killed, 599 survived, 144 without module-local coverage and 56 timed out.
PIT's detected score includes timeouts (68.2%); mutated-class line coverage is
3,746/4,046 (92.6%). Detailed issue evidence is retained locally under
`.scratch/java25-web-framework/research/issue48-full-pitest-baseline.md`.
Module-local attribution must be distinguished from coverage supplied by consumer
modules. Native reports and the full execution log are archived under
`.scratch/java25-web-framework/mutation/issue48/full-baseline/` before any future clean.

| Module | Baseline line coverage | Mutations | Killed | Survived | No coverage | Timed out |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| core | 3,034/3,276 | 2,075 | 1,385 | 544 | 91 | 55 |
| http | 563/579 | 183 | 117 | 39 | 27 | 0 |
| micrometer | 26/27 | 6 | 4 | 2 | 0 | 0 |
| opentelemetry | 39/44 | 17 | 8 | 5 | 3 | 1 |
| pac4j | 69/103 | 52 | 21 | 9 | 22 | 0 |
| test-support | 15/17 | 6 | 5 | 0 | 1 | 0 |

The full candidate run completed in **54m21s** with unchanged production Java and
the same 2,339 mutation identities. It killed 39 additional mutations. Inspection
then identified a missing valid authentication-challenge example containing `~`.
After adding that case, `clean spotlessApply build` passed in **5m36s**, including
1,110 library tests with no failures, errors or skips. Only the affected `:http:pitest`
task was rerun (**32s**), killing the remaining printable-character boundary mutation.
The validated results below combine that HTTP rerun with the unchanged five modules
from the full candidate run; they are not a second full-suite execution.

| Module | Validated line coverage | Mutations | Killed | Survived | No coverage | Timed out |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| core | 3,035/3,276 | 2,075 | 1,399 | 530 | 91 | 55 |
| http | 572/579 | 183 | 129 | 35 | 19 | 0 |
| micrometer | 27/27 | 6 | 6 | 0 | 0 | 0 |
| opentelemetry | 39/44 | 17 | 11 | 2 | 3 | 1 |
| pac4j | 74/103 | 52 | 30 | 6 | 16 | 0 |
| test-support | 15/17 | 6 | 5 | 0 | 1 | 0 |
| **Total** | **3,762/4,046** | **2,339** | **1,580** | **573** | **130** | **56** |

This is **40 additional assertion/exception kills**: 27 former survivors and 13
formerly uncovered mutations. No mutation changed to or from `TIMED_OUT`. One further
uncovered pac4j mutation became covered but survived. The detected score is 69.9%;
mutated-class line coverage is 93.0%. These percentages are diagnostics, not gates.

The focused plan selected 38 baseline mutations; 37 are now `KILLED`:

| Protected public behavior | Baseline status | Validated status | Selected mutations |
| --- | --- | --- | ---: |
| Reject oversized finite byte responses without changing the staged body | Survived | Killed | 1 |
| Reject leading CR, LF and NUL in SSE event/ID metadata | Survived | Killed | 3 |
| Preserve CORS port identity, port 65535 and exact port spelling | Survived | Killed | 3 |
| Keep lists inside response header snapshots immutable | Survived | Killed | 1 |
| Classify explicit status-500 responses in metrics and tracing; preserve transport error attributes | Survived | Killed | 5 |
| Preserve provider query/form merging, request method and response-header operations | Survived or no coverage | Killed | 9 |
| Reject distinct-header equality and unsafe cookie values | Survived | Killed | 2 |
| Validate and preserve authentication challenges, including printable `~` | No coverage | Killed | 8 |
| Reject closed protection callbacks and isolated malformed route symbols | Survived | Killed | 5 |
| Split a content type whose first character is `;` | Survived | Survived; equivalent | 1 |

For the equivalent pac4j boundary mutation, changing `separator < 0` to
`separator <= 0` differs only when `;` occurs at index zero. The original produces
an empty media type, while the mutant retains a leading semicolon. Neither can
equal either supported form media type, so both preserve query parameters and
leave the body unparsed. No artificial test or exclusion was added to eliminate it.

Remaining results are retained rather than treated as defects. Constant-hash
mutations still satisfy the equality contract; defensive/private states may be
unreachable through public construction. Other survivors require individual
triage, particularly resource ownership, compression overrides, multipart provider
adaptation and exceptional tracing setup. Ordinary startup cleanup does not reach
test-support's uncovered suppression branch. This pass establishes no current
production defect in the selected contracts and does not claim complete mutation
classification or a 100% kill rate.

Native evidence is retained locally under the issue 48 mutation directory:
`full-baseline/` contains the original six reports, `candidate/` the full follow-up
run, and `http-followup/` the affected-module rerun. `validated-comparison.json`
matches mutations using class, method descriptor, line, mutator, bytecode indexes
and blocks; it retains exact statuses and killing tests. Archive these before
cleaning; their locations are local evidence, not Git-tracked artifacts.

`Killed` means the native `KILLED` status; PIT's detected score also includes
`TIMED_OUT`. Keep timeouts separate when assessing whether assertions protect a
contract. The scope remains module-local: focused HTTP tests protect important
value invariants directly; core retains its HTTP integration tests.

A surviving mutation is not automatically a bug: record the changed
behavior, the input that would expose it, and whether it is a missing regression,
equivalent behavior, unreachable through the chosen public contract, or outside
this ticket's selected paths. Do not weaken production contracts to improve a score.
