# 5. Bodies, forms and uploads

These compiled factories support request-body and upload recipes:

- Buffered input: [example](src/main/java/io/github/suppierk/shoostr/example/bodies/BufferedBodyExample.java) and [test](src/test/java/io/github/suppierk/shoostr/example/bodies/BufferedBodyExampleTest.java).
- Streamed input: [example](src/main/java/io/github/suppierk/shoostr/example/bodies/StreamingInputExample.java) and [test](src/test/java/io/github/suppierk/shoostr/example/bodies/StreamingInputExampleTest.java).
- Multipart fields and files: [example](src/main/java/io/github/suppierk/shoostr/example/bodies/MultipartExample.java) and [test](src/test/java/io/github/suppierk/shoostr/example/bodies/MultipartExampleTest.java).
- Saved uploads: [example](src/main/java/io/github/suppierk/shoostr/example/bodies/PersistUploadExample.java) and [test](src/test/java/io/github/suppierk/shoostr/example/bodies/PersistUploadExampleTest.java).

Run `./gradlew :bodies-example:test` from the repository root with Java 21.
