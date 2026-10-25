# Contribution workflow

Consumer documentation belongs in the [Starlight site](site/). Repository documentation is for maintainers.
See [documentation maintenance](docs/maintainers/documentation.md) for site commands and writing rules.
Keep upstream test attributions in [test provenance](docs/maintainers/test-provenance.md).
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

The required `build` check verifies successful validation of the proposed revision for
internal, fork and Dependabot pull requests. Failed, cancelled or skipped validation
cannot pass this check, and the validated revision must match the triggering event.
External contributions do not need a replacement pull request on a maintainer branch.

Trusted pull requests and CI runs in `SuppieRK/Shoostr` must pass the Sonar quality gate
and have zero open issues. A missing analysis token, scanner failure, failed quality gate,
API error or nonzero issue count fails validation and the required `build` check.
Fork and Dependabot pull requests run validation without Sonar; repository secrets remain
restricted to trusted analysis.

The aggregate `build` job runs even when validation fails or is skipped. Main's ruleset
requires `build` from GitHub Actions and an up-to-date branch; the existing administrator
bypass is retained.
