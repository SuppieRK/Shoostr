# Published-artifact verification

This is an internal packaging check, not an application example. It verifies that an
independent Java 25 consumer can use Shoostr's published artifacts and optional integrations,
and that the core runtime dependency graph remains free of optional integrations and codecs.

From the repository root, run `./gradlew consumerSmoke`. The task publishes to
`build/consumer-repository` and runs this separate Gradle build against those artifacts;
it does not publish to Maven Local or a remote repository.

The normal root `build` also runs this fixture's JUnit tests through `:consumer-smoke`,
using project dependencies. That does not replace the independent publication check.
