# 11 — WebSockets

Consumer recipes: [managed messages](../../site/src/content/docs/websockets/managed.mdx),
[object messages](../../site/src/content/docs/websockets/objects.mdx),
and [native listeners](../../site/src/content/docs/websockets/native.mdx).

| Recipe | Example | Test |
| --- | --- | --- |
| Managed messages | [ManagedWebSocketExample](src/main/java/io/github/suppierk/shoostr/example/websocket/ManagedWebSocketExample.java) | [Test](src/test/java/io/github/suppierk/shoostr/example/websocket/ManagedWebSocketExampleTest.java) |
| Binary objects | [TypedWebSocketExample](src/main/java/io/github/suppierk/shoostr/example/websocket/TypedWebSocketExample.java) | [Test](src/test/java/io/github/suppierk/shoostr/example/websocket/TypedWebSocketExampleTest.java) |
| Native listeners | [NativeWebSocketExample](src/main/java/io/github/suppierk/shoostr/example/websocket/NativeWebSocketExample.java) | [Test](src/test/java/io/github/suppierk/shoostr/example/websocket/NativeWebSocketExampleTest.java) |

Gradle project: `websocket-example`. See the [maintainer fixture guide](../../docs/maintainers/websocket-fixture.md)
for the launcher, browser checks, container commands and opt-in session/Origin fixture.
