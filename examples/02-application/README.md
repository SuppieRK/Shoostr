# 2. Application configuration

Compiled source fixtures for the consumer documentation:

- Shoostr-owned resource limits: [example](src/main/java/io/github/suppierk/shoostr/example/application/ServerOptionsExample.java) and [test](src/test/java/io/github/suppierk/shoostr/example/application/ServerOptionsExampleTest.java).
- Native Jetty listener, pool and HTTP settings: [example](src/main/java/io/github/suppierk/shoostr/example/application/NativeConfigurationExample.java) and [test](src/test/java/io/github/suppierk/shoostr/example/application/NativeConfigurationExampleTest.java).

Use Java 21. From the repository root, run `./gradlew :application-example:test`.
The factories return unstarted applications; the caller must close them.
