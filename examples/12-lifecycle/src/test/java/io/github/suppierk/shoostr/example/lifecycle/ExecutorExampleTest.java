package io.github.suppierk.shoostr.example.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.suppierk.shoostr.RequestOutcome;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExecutorExampleTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void usesTheSelectedModeWhenNoEndpointExecutorIsSelected(boolean virtual) throws Exception {
    try (var workers = Executors.newSingleThreadExecutor();
        var test =
            TestServer.start(
                ExecutorExample.application(
                    virtual, workers, new ThreadLocal<>(), ignored -> {}))) {
      var response =
          test.send(request -> request.path("/default"), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals("virtual=" + virtual, response.body());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void reattachesRequestContextAndRestoresTheWorkersPreviousValue(boolean virtualApplication)
      throws Exception {
    var context = new ThreadLocal<String>();
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();

    try (var workers =
        Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("recipe-worker-", 0).factory())) {
      workers.submit(() -> context.set("worker-base")).get(5, TimeUnit.SECONDS);

      try (var test =
          TestServer.start(
              verifyExecutionMode(
                  ExecutorExample.application(
                      virtualApplication, workers, context, outcomes::offer),
                  virtualApplication))) {
        var response =
            test.send(
                request -> request.path("/selected").header("X-Request-Id", "request-7"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("request-7|recipe-worker-0", response.body());
        assertNotNull(outcomes.poll(5, TimeUnit.SECONDS));
        assertEquals("worker-base", workers.submit(context::get).get(5, TimeUnit.SECONDS));
      }

      assertFalse(workers.isShutdown());
      assertEquals(
          "still available", workers.submit(() -> "still available").get(5, TimeUnit.SECONDS));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rendersRejectedSubmissionsWithReattachedContextAndReportsTheirFailure(
      boolean virtualApplication) throws Exception {
    var context = new ThreadLocal<String>();
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();

    try (var workers = Executors.newSingleThreadExecutor();
        var test =
            TestServer.start(
                verifyExecutionMode(
                    ExecutorExample.application(
                        virtualApplication, workers, context, outcomes::offer),
                    virtualApplication))) {
      workers.shutdown();
      var response =
          test.send(
              request -> request.path("/selected").header("X-Request-Id", "request-8"),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(503, response.statusCode());
      assertEquals("busy:request-8", response.body());
      var outcome = outcomes.poll(5, TimeUnit.SECONDS);
      assertNotNull(outcome);
      assertEquals("/selected", outcome.routePattern());
      assertInstanceOf(RejectedExecutionException.class, outcome.applicationFailure());
    }
  }

  private static Shoostr verifyExecutionMode(Shoostr app, boolean virtualApplication) {
    return app.onRequestHeaders(
        (request, response) ->
            assertEquals(virtualApplication, Thread.currentThread().isVirtual()));
  }
}
