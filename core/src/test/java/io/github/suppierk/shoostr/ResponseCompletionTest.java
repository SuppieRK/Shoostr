package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ResponseCompletionTest {
  @Test
  void waitsForObservationAfterTransportCompletesInline() throws Exception {
    var completed = new ArrayList<Object>();
    var response =
        response(Callback::succeeded, () -> assertTrue(completed.isEmpty()), completion(completed));

    response.complete();

    assertEquals(List.of("success"), completed);
  }

  @Test
  void waitsForTransportAfterObservationFinishes() throws Exception {
    var completed = new ArrayList<Object>();
    var transport = new ArrayList<Callback>();
    var response =
        response(transport::add, () -> assertTrue(completed.isEmpty()), completion(completed));

    response.complete();

    assertTrue(completed.isEmpty());
    transport.getFirst().succeeded();
    assertEquals(List.of("success"), completed);
  }

  @Test
  void retainsTransportCompletionReenteringFromObservation() throws Exception {
    var completed = new ArrayList<Object>();
    var transport = new ArrayList<Callback>();
    var response =
        response(
            transport::add,
            () -> completeTransportDuringObservation(transport, completed),
            completion(completed));

    response.complete();

    assertEquals(List.of("success"), completed);
  }

  @Test
  void waitsForObservationAfterDuplicateTransportArrivals() throws Exception {
    var completed = new ArrayList<Object>();
    var response =
        response(this::succeedTwice, () -> assertTrue(completed.isEmpty()), completion(completed));

    response.complete();

    assertEquals(List.of("success"), completed);
  }

  @Test
  void reportsObservationFailureAfterTransportSucceeds() throws Exception {
    var completed = new ArrayList<Object>();
    var transport = new ArrayList<Callback>();
    var failure = new IllegalStateException("Observation failed");
    var response = response(transport::add, failingHook(failure), completion(completed));

    response.complete();

    assertTrue(completed.isEmpty());
    assertSame(failure, response.afterFlushFailure());
    transport.getFirst().succeeded();
    assertSame(failure, completed.getFirst());
    assertEquals(1, completed.size());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void prioritizesTransportFailureOverObservationFailure(boolean transportFirst) throws Exception {
    var completed = new ArrayList<Object>();
    var transport = new ArrayList<Callback>();
    var transportFailure = new IOException("Transport failed");
    var observationFailure = new IllegalStateException("Observation failed");
    Consumer<Callback> write =
        transportFirst ? failDuringWrite(transport, transportFailure) : transport::add;
    var response = response(write, failingHook(observationFailure), completion(completed));

    response.complete();

    assertSame(observationFailure, response.afterFlushFailure());
    if (!transportFirst) {
      assertTrue(completed.isEmpty());
      transport.getFirst().failed(transportFailure);
    }

    assertSame(transportFailure, completed.getFirst());
    assertEquals(1, completed.size());
  }

  @Test
  void retainsTheLatestTransportFailureAcrossDuplicateSuccessAndLateCallbacks() throws Exception {
    var completed = new ArrayList<Object>();
    var transport = new ArrayList<Callback>();
    var first = new IOException("First transport failure");
    var latest = new IOException("Latest transport failure");
    var write = repeatedFailures(transport, first, latest);
    var response = response(write, () -> {}, completion(completed));

    response.complete();

    assertSame(latest, completed.getFirst());
    transport.getFirst().failed(first);
    transport.getFirst().succeeded();
    assertEquals(1, completed.size());
  }

  @Test
  void claimsCompletionBeforeReentrantTerminalCallbackThrows() throws Exception {
    var completed = new ArrayList<Object>();
    var transport = new ArrayList<Callback>();
    var failure = new IllegalStateException("Completion failed");
    var completion = reentrantCompletion(completed, transport, failure);
    var response = response(transport::add, () -> {}, completion);

    response.complete();

    assertSame(failure, assertThrows(IllegalStateException.class, transport.getFirst()::succeeded));
    transport.getFirst().succeeded();
    assertEquals(List.of("success"), completed);
  }

  @Test
  void leavesObservationPendingWhenItsHookThrowsAnError() {
    var completed = new ArrayList<Object>();
    var transport = new ArrayList<Callback>();
    var failure = new Error("Observation failed");
    var write = captureSuccessfulWrite(transport);
    var response = response(write, errorHook(failure), completion(completed));

    assertSame(failure, assertThrows(Error.class, response::complete));
    assertNull(response.afterFlushFailure());
    transport.getFirst().succeeded();
    transport.getFirst().failed(new IOException("Late transport failure"));
    assertTrue(completed.isEmpty());
  }

  private void completeTransportDuringObservation(
      List<Callback> transport, List<Object> completed) {
    transport.getFirst().succeeded();
    assertTrue(completed.isEmpty());
  }

  private void succeedTwice(Callback callback) {
    callback.succeeded();
    callback.succeeded();
  }

  private Runnable failingHook(RuntimeException failure) {
    return () -> {
      throw failure;
    };
  }

  private Runnable errorHook(Error failure) {
    return () -> {
      throw failure;
    };
  }

  private Consumer<Callback> failDuringWrite(List<Callback> transport, Throwable failure) {
    return callback -> {
      transport.add(callback);
      callback.failed(failure);
    };
  }

  private Consumer<Callback> repeatedFailures(
      List<Callback> transport, Throwable first, Throwable latest) {
    return callback -> {
      transport.add(callback);
      callback.failed(first);
      callback.failed(latest);
      callback.succeeded();
    };
  }

  private Consumer<Callback> captureSuccessfulWrite(List<Callback> transport) {
    return callback -> {
      transport.add(callback);
      callback.succeeded();
    };
  }

  private Callback reentrantCompletion(
      List<Object> completed, List<Callback> transport, RuntimeException failure) {
    return new Callback() {
      @Override
      public void succeeded() {
        completed.add("success");
        transport.getFirst().succeeded();
        throw failure;
      }

      @Override
      public void failed(Throwable failure) {
        completed.add(failure);
      }
    };
  }

  private Response response(Consumer<Callback> write, Runnable afterFlush, Callback completion) {
    var response =
        io.github.suppierk.shoostr.Request.create(
                nativeRequest(), nativeResponse(write), new Options(), completion)
            .response();
    response.flushHooks(null, afterFlush);
    response.text("payload");

    return response;
  }

  private Request nativeRequest() {
    var headers = HttpFields.build();

    return (Request)
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {Request.class},
            (ignored, method, arguments) ->
                switch (method.getName()) {
                  case "getMethod" -> "GET";
                  case "getHeaders" -> headers;
                  case "getLength" -> 0L;
                  case "consumeAvailable" -> true;
                  case "addHttpStreamWrapper" -> null;
                  default -> throw new UnsupportedOperationException(method.getName());
                });
  }

  private org.eclipse.jetty.server.Response nativeResponse(Consumer<Callback> write) {
    var headers = HttpFields.build();

    return (org.eclipse.jetty.server.Response)
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {org.eclipse.jetty.server.Response.class},
            (ignored, method, arguments) ->
                switch (method.getName()) {
                  case "getStatus" -> 200;
                  case "setStatus" -> null;
                  case "getHeaders" -> headers;
                  case "isCommitted" -> false;
                  case "write" -> {
                    write.accept((Callback) arguments[2]);
                    yield null;
                  }
                  default -> throw new UnsupportedOperationException(method.getName());
                });
  }

  private Callback completion(List<Object> completed) {
    return Callback.from(() -> completed.add("success"), completed::add);
  }
}
