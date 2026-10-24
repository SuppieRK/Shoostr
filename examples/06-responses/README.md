# 6. Responses and HTTP primitives

Each response recipe has a compiled factory and a live HTTP test:

- Text, bytes and empty responses: [example](src/main/java/io/github/suppierk/shoostr/example/responses/ResponseBodyExample.java) and [test](src/test/java/io/github/suppierk/shoostr/example/responses/ResponseBodyExampleTest.java).
- Response headers: [example](src/main/java/io/github/suppierk/shoostr/example/responses/ResponseHeadersExample.java) and [test](src/test/java/io/github/suppierk/shoostr/example/responses/ResponseHeadersExampleTest.java).
- Cookies and deletion: [example](src/main/java/io/github/suppierk/shoostr/example/responses/ResponseCookiesExample.java) and [test](src/test/java/io/github/suppierk/shoostr/example/responses/ResponseCookiesExampleTest.java).
- Redirects: [example](src/main/java/io/github/suppierk/shoostr/example/responses/RedirectExample.java) and [test](src/test/java/io/github/suppierk/shoostr/example/responses/RedirectExampleTest.java).

Run `./gradlew :responses-example:test` from the repository root with Java 21.
