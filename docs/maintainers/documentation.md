# Maintain the documentation

The Starlight site in `site/` is for library consumers.
The root README provides a short product overview. Other repository documents are for maintainers.
Keep contribution procedures, build diagnostics, provenance, and agent instructions out of the consumer site.
Do not add dependency-wiring, dependency-injection, or service-composition guides to the consumer site.
Library dependency declarations are separate from application dependency wiring.

## Build and preview

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
The output in `site/dist/` is a static GitHub Pages artifact. Publishing requires separate authorization and repository configuration.
There is no deployment workflow in this foundation.

Build the library and verify examples separately:

```sh
./gradlew clean spotlessApply build
```

Node tooling is independent of the Gradle build and the library runtime.

## Add a consumer topic

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

## STE-based writing profile

This project uses a profile based on Simplified Technical English.
It does not claim formal ASD-STE100 compliance or enforce its complete dictionary.

| Rule | Requirement |
| --- | --- |
| W01 — Reader task | Use task-focused titles. State the result before background. |
| W02 — Direct steps | Use active voice and imperative verbs. Give each instruction one main action. |
| W03 — Natural flow | Use concise, connected prose with varied sentence lengths. Keep related ideas together rather than splitting paragraphs into abrupt statements. |
| W04 — Stable terms | Use one term for one concept. Preserve exact identifiers, commands, protocol fields, and errors. |
| W05 — Complete recipe | Show the task and its test as complete classes. State additional library requirements, essential constraints, and ownership. Do not repeat initial setup. |
| W06 — Essential limits | State ownership, lifetime, security, and protocol limits where the reader acts. |
| W07 — Evidence | Display compiled source files. Link primary references for deeper concepts. Label uncertainty and historical evidence. |
| W08 — Audience | Keep consumer tasks in Starlight and maintainer procedures on GitHub. Keep READMEs short. |
| W09 — Clean examples | Keep Sonar-specific suppressions, rule IDs, and analyzer comments out of example source. Explain resource ownership in ordinary terms. |
| W10 — Java fluency | Explain task-specific choices and non-obvious contracts, not assertions, standard Java syntax, or automatic resource closure. |
| W11 — In-code explanations | Comment meaningful choices, scope, ordering, ownership, and failure paths where the reader encounters them. Keep already-clear code concise. |
| W12 — Fluent registration | Chain adjacent registrations on the same route scope, preserving call order and child boundaries. Keep setup and unrelated operations separate. |
| W13 — Application setup | Show application construction and fluent configuration directly, without setup catch blocks, cleanup scaffolding, or route-only substitutes. |

Review prose in context instead of treating sentence length or automated word counts as proof of clarity.
Use short sentences where they help, such as a clear warning, without making every paragraph read like a checklist.

## Documentation review

Use the knowledge-base architect before changing structure or canonical ownership.
Use the writing reviewer against this profile and the technical reviewer before documentation completion.
Record each review's scope, findings and outcome before delivery.

References: [Starlight setup](https://starlight.astro.build/manual-setup/),
[whole-file code imports](https://starlight.astro.build/components/code/),
[site search](https://starlight.astro.build/guides/site-search/), and
[Astro GitHub Pages configuration](https://docs.astro.build/en/guides/deploy/github/).
