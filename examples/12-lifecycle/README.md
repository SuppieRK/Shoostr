# 12. Lifecycle

These factories demonstrate application shutdown, request hooks and executor ownership.
Each factory has a complete test class beside the other tests in this module:

- [ShutdownExample](src/main/java/io/github/suppierk/shoostr/example/lifecycle/ShutdownExample.java)
- [RequestHooksExample](src/main/java/io/github/suppierk/shoostr/example/lifecycle/RequestHooksExample.java)
- [ExecutorExample](src/main/java/io/github/suppierk/shoostr/example/lifecycle/ExecutorExample.java)

Run `./gradlew :lifecycle-example:test` from the repository root with Java 21.
Tests use real HTTP requests and public lifecycle interfaces without external services.
