package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AccessLogTest {
  @Test
  void logsTerminalMetadataWithoutCredentialsIdentifiersBodiesOrFailureMessages() throws Exception {
    var lines = new LinkedBlockingQueue<String>();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.afterRequest(new AccessLog(lines::add));
      app.routes()
          .post(
              "/orders/{id}",
              (request, response) -> {
                throw new IllegalStateException("private failure details");
              });
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(
                      URI.create(
                          "http://127.0.0.1:" + app.port() + "/orders/private-id?secret=value"))
                  .header("Authorization", "Bearer private-token")
                  .header("Cookie", "session=private-cookie")
                  .POST(HttpRequest.BodyPublishers.ofString("private body"))
                  .build(),
              HttpResponse.BodyHandlers.discarding());
      assertEquals(500, result.statusCode());
      var line = lines.poll(5, TimeUnit.SECONDS);
      assertTrue(line.startsWith("method=POST route=/orders/{id} status=500 duration_ns="));
      assertTrue(line.endsWith(" application_failure=true transport_failure=false"));
      assertFalse(line.contains("private"));
      assertFalse(line.contains("secret"));
      assertTrue(lines.isEmpty());
    }
  }
}
