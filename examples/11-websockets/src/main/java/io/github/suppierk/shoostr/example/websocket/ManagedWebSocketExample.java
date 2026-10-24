package io.github.suppierk.shoostr.example.websocket;

import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.WebSocketConnection;
import io.github.suppierk.shoostr.http.exceptions.ForbiddenException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Managed room messages with separate connection counters and strict Origin admission. */
public final class ManagedWebSocketExample {
  /** Prevents utility-class construction. */
  private ManagedWebSocketExample() {}

  /**
   * Creates a room endpoint whose connection counters are released when the application closes.
   *
   * @return unstarted, caller-owned application
   */
  public static Shoostr application() {
    // A separate counter per connection prevents clients in one room from sharing a count.
    var counts = new ConcurrentHashMap<WebSocketConnection, Long>();
    return new Shoostr()
        .afterClose(counts::clear)
        .routes(
            routes ->
                routes.websocket(
                    "/rooms/{room}",
                    socket ->
                        socket
                            .beforeUpgrade(
                                (request, ignored) -> {
                                  // Reject missing, repeated, or unexpected Origin values before
                                  // upgrading.
                                  if (!request
                                      .headers("Origin")
                                      .equals(List.of("https://app.example"))) {
                                    throw new ForbiddenException();
                                  }
                                })
                            .onOpen(connection -> counts.put(connection, 0L))
                            .onText(
                                (connection, text) -> {
                                  if (text.isBlank()) {
                                    connection.send("error: message required");
                                    return;
                                  }

                                  var count = counts.merge(connection, 1L, Long::sum);
                                  connection.send(
                                      connection.pathParam("room").orElseThrow()
                                          + ":"
                                          + count
                                          + ":"
                                          + text);
                                })
                            // The managed adapter handles native demand and buffer completion.
                            .onBinary(WebSocketConnection::send)
                            .onError((connection, ignored2) -> counts.remove(connection))
                            .onClose(
                                (connection, ignored3, ignored4) -> counts.remove(connection))));
  }
}
