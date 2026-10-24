# 13. Extensions

The [extension recipe](../../site/src/content/docs/extensions/route-metadata.mdx)
builds a route-description catalog with independent settings for each route or group.
Its complete [example](src/main/java/io/github/suppierk/shoostr/example/extensions/RouteCatalogExample.java)
and [test](src/test/java/io/github/suppierk/shoostr/example/extensions/RouteCatalogExampleTest.java)
cover registration facts, inherited overrides, application ownership and cleanup.

Run `./gradlew :extensions-example:test` from the repository root with Java 21.
The tests use real HTTP requests and public extension interfaces without external services.
