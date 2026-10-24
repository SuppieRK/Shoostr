# 8. Exceptions, statuses and problem details

Each error-handling recipe has a compiled factory and a live HTTP test:

- Thrown exceptions and recovery: [example](src/main/java/io/github/suppierk/shoostr/example/errors/ExceptionHandlingExample.java) and [test](src/test/java/io/github/suppierk/shoostr/example/errors/ExceptionHandlingExampleTest.java).
- Generated status responses: [example](src/main/java/io/github/suppierk/shoostr/example/errors/GeneratedStatusExample.java) and [test](src/test/java/io/github/suppierk/shoostr/example/errors/GeneratedStatusExampleTest.java).
- Problem Details JSON: [example](src/main/java/io/github/suppierk/shoostr/example/errors/ProblemDetailsExample.java) and [test](src/test/java/io/github/suppierk/shoostr/example/errors/ProblemDetailsExampleTest.java).

Run `./gradlew :errors-example:test` from the repository root with Java 21.
