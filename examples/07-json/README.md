# 7. Serialization

Each recipe has a compiled factory and a live HTTP test:

- Codec-backed JSON conversion: [example](src/main/java/io/github/suppierk/shoostr/example/json/JsonCodecExample.java) and [test](src/test/java/io/github/suppierk/shoostr/example/json/JsonCodecExampleTest.java).
- Response representation selection: [example](src/main/java/io/github/suppierk/shoostr/example/json/NegotiationExample.java) and [test](src/test/java/io/github/suppierk/shoostr/example/json/NegotiationExampleTest.java).

Run `./gradlew :json-example:test` from the repository root with Java 21.
