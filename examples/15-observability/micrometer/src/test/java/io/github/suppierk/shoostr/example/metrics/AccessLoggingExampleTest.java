package io.github.suppierk.shoostr.example.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.testing.TestServer;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class AccessLoggingExampleTest {
  @Test
  void rejectsANullLogSinkFromAReflectiveCaller() throws Exception {
    var factory = AccessLoggingExample.class.getMethod("application", Consumer.class);
    var failure =
        assertThrows(
            InvocationTargetException.class, () -> factory.invoke(null, new Object[] {null}));
    assertInstanceOf(NullPointerException.class, failure.getCause());
  }

  @Test
  void recordsTheConfiguredRouteWithoutLeakingRequestSecretsOrRawPaths() throws Exception {
    var lines = new LinkedBlockingQueue<String>();

    try (var test = TestServer.start(AccessLoggingExample.application(lines::offer))) {
      assertEquals(
          200,
          test.send(
                  request ->
                      request
                          .path("/orders/42?token=query-secret")
                          .header("Authorization", "Bearer header-secret")
                          .header("Cookie", "SESSION=cookie-secret"))
              .statusCode());
      var line = lines.poll(5, TimeUnit.SECONDS);
      assertNotNull(line);
      assertTrue(
          line.matches(
              "method=GET route=/orders/\\{id\\} status=200 duration_ns=\\d+ application_failure=false transport_failure=false"));
      assertFalse(line.contains("/orders/42"));
      assertFalse(line.contains("secret"));
    }
  }

  @Test
  void reportsFailureFlagsWithoutIncludingExceptionDetails() throws Exception {
    var lines = new LinkedBlockingQueue<String>();

    try (var test = TestServer.start(AccessLoggingExample.application(lines::offer))) {
      assertEquals(500, test.send(request -> request.path("/orders/failure")).statusCode());
      var line = lines.poll(5, TimeUnit.SECONDS);
      assertNotNull(line);
      assertTrue(
          line.matches(
              "method=GET route=/orders/failure status=500 duration_ns=\\d+ application_failure=true transport_failure=false"));
      assertFalse(line.contains("private"));
      assertFalse(line.contains("IllegalStateException"));
    }
  }

  @Test
  void recordsAnUnmatchedRequestWithoutItsRawPath() throws Exception {
    var lines = new LinkedBlockingQueue<String>();

    try (var test = TestServer.start(AccessLoggingExample.application(lines::offer))) {
      assertEquals(404, test.send(request -> request.path("/missing-private-path")).statusCode());
      var line = lines.poll(5, TimeUnit.SECONDS);
      assertNotNull(line);
      assertTrue(
          line.matches(
              "method=GET route=UNMATCHED status=404 duration_ns=\\d+ application_failure=false transport_failure=false"));
      assertFalse(line.contains("private"));
    }
  }
}
