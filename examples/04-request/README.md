# 4. Request metadata and state

Each request recipe has a compiled factory and a live HTTP test:

- Parameters: [example](src/main/java/io/github/suppierk/shoostr/example/request/ParametersExample.java) and [test](src/test/java/io/github/suppierk/shoostr/example/request/ParametersExampleTest.java).
- Headers and cookies: [example](src/main/java/io/github/suppierk/shoostr/example/request/HeadersCookiesExample.java) and [test](src/test/java/io/github/suppierk/shoostr/example/request/HeadersCookiesExampleTest.java).
- Request metadata: [example](src/main/java/io/github/suppierk/shoostr/example/request/MetadataExample.java) and [test](src/test/java/io/github/suppierk/shoostr/example/request/MetadataExampleTest.java).
- Request-local state: [example](src/main/java/io/github/suppierk/shoostr/example/request/RequestStateExample.java) and [test](src/test/java/io/github/suppierk/shoostr/example/request/RequestStateExampleTest.java).

Run `./gradlew :request-example:test` from the repository root with Java 21.
