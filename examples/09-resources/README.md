# 9. Files and resources

Each recipe has a compiled factory and a live HTTP test:

- Static mounts and SPA fallback: [example](src/main/java/io/github/suppierk/shoostr/example/resources/StaticResourcesExample.java) and [test](src/test/java/io/github/suppierk/shoostr/example/resources/StaticResourcesExampleTest.java).
- File downloads: [example](src/main/java/io/github/suppierk/shoostr/example/resources/DownloadExample.java) and [test](src/test/java/io/github/suppierk/shoostr/example/resources/DownloadExampleTest.java).
- Ranges and validators: [example](src/main/java/io/github/suppierk/shoostr/example/resources/FileCachingExample.java) and [test](src/test/java/io/github/suppierk/shoostr/example/resources/FileCachingExampleTest.java).

Run `./gradlew :resources-example:test` from the repository root with Java 21.
Filesystem mount tests require secure directory operations and symbolic-link support.
The tests create their own files and classpath resources in temporary directories.
