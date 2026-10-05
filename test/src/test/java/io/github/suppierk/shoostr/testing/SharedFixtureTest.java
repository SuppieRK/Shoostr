package io.github.suppierk.shoostr.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.suppierk.shoostr.Shoostr;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class SharedFixtureTest {
  private static TestServer test;
  private static AtomicReference<String> repository;

  @BeforeAll
  static void startSharedFixture() throws Exception {
    repository = new AtomicReference<>("caller-owned");
    var app = new Shoostr();
    app.routes().get("/seed", (_, response) -> response.cookie("token", "shared"));
    app.routes()
        .get(
            "/cookie",
            (request, response) -> response.text(request.cookie("token").orElse("absent")));
    app.routes().get("/repository", (_, response) -> response.text(repository.get()));
    test = TestServer.start(app);

    try {
      test.send(request -> request.path("/seed"));
    } catch (Exception failure) {
      try {
        test.close();
      } catch (Exception cleanup) {
        failure.addSuppressed(cleanup);
      }

      throw failure;
    }
  }

  @AfterAll
  static void closeSharedFixture() throws Exception {
    if (test != null) {
      test.close();
    }
  }

  @Test
  void preservesCookieStateSeededInBeforeAll() throws Exception {
    assertEquals(
        "shared",
        test.send(request -> request.path("/cookie"), HttpResponse.BodyHandlers.ofString()).body());
  }

  @Test
  void preservesCallerDependencyStateSeededInBeforeAll() throws Exception {
    assertEquals(
        "caller-owned",
        test.send(request -> request.path("/repository"), HttpResponse.BodyHandlers.ofString())
            .body());
  }
}
