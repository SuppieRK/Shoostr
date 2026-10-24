package io.github.suppierk.shoostr.example.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.suppierk.shoostr.RequestOutcome;
import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RequestHooksExampleTest {
  @Test
  void runsLocalHooksBeforeApplicationHooksAndReportsTheComposedRoute() throws Exception {
    var phases = new LinkedBlockingQueue<String>();
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();

    try (var test =
        TestServer.start(RequestHooksExample.application(phases::offer, outcomes::offer))) {
      var response =
          test.send(request -> request.path("/jobs/42"), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals("job 42", response.body());
      var outcome = outcomes.poll(5, TimeUnit.SECONDS);
      assertNotNull(outcome);
      assertEquals("GET", outcome.method());
      assertEquals("/jobs/{id}", outcome.routePattern());
      assertEquals(200, outcome.statusCode());
      assertNull(outcome.applicationFailure());
      assertNull(outcome.transportFailure());
      assertEquals(
          List.of(
              "headers:unmatched",
              "local:matched",
              "app:matched:/jobs/{id}",
              "local:before",
              "app:before",
              "handler",
              "local:after",
              "app:after",
              "local:before-flush",
              "app:before-flush",
              "local:after-flush",
              "app:after-flush",
              "local:completed",
              "app:completed"),
          List.copyOf(phases));
    }
  }

  @Test
  void reportsFailedHandlersWithoutRunningSuccessfulReturnHooks() throws Exception {
    var phases = new LinkedBlockingQueue<String>();
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();

    try (var test =
        TestServer.start(RequestHooksExample.application(phases::offer, outcomes::offer))) {
      assertEquals(500, test.send(request -> request.path("/jobs/failure")).statusCode());
      var outcome = outcomes.poll(5, TimeUnit.SECONDS);
      assertNotNull(outcome);
      assertEquals("/jobs/failure", outcome.routePattern());
      assertEquals(500, outcome.statusCode());
      assertInstanceOf(IllegalStateException.class, outcome.applicationFailure());
      assertFalse(phases.contains("local:after"));
      assertFalse(phases.contains("app:after"));
      assertEquals(1, phases.stream().filter("local:completed"::equals).count());
      assertEquals(1, phases.stream().filter("app:completed"::equals).count());
    }
  }

  @Test
  void observesUnmatchedRequestsWithoutInventingRouteFactsOrLocalHooks() throws Exception {
    var phases = new LinkedBlockingQueue<String>();
    var outcomes = new LinkedBlockingQueue<RequestOutcome>();

    try (var test =
        TestServer.start(RequestHooksExample.application(phases::offer, outcomes::offer))) {
      assertEquals(404, test.send(request -> request.path("/missing")).statusCode());
      var outcome = outcomes.poll(5, TimeUnit.SECONDS);
      assertNotNull(outcome);
      assertNull(outcome.routePattern());
      assertEquals(404, outcome.statusCode());
      assertEquals(
          List.of("headers:unmatched", "app:before-flush", "app:after-flush", "app:completed"),
          List.copyOf(phases));
    }
  }
}
