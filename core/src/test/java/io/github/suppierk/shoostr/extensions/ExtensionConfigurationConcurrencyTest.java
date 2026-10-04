package io.github.suppierk.shoostr.extensions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.suppierk.shoostr.Extension;
import io.github.suppierk.shoostr.Extensions;
import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Shoostr;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.eclipse.jetty.websocket.api.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ExtensionConfigurationConcurrencyTest {
  @Test
  void rejectsStartupDuringEndpointConfigurationAndPublishesAfterItFinishes() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .get(
              "/configured",
              (_, response) -> response.text("registered"),
              _ -> assertThrows(IllegalStateException.class, app::start));
      app.start();
      var result =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/configured"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals("registered", result.body());
    }
  }

  @Test
  void rejectsPublicationAfterConfigurationClosesRegistration() throws Exception {
    try (var app = new Shoostr()) {
      var routes = app.routes();
      assertThrows(
          IllegalStateException.class,
          () -> routes.get("/closed", (_, _) -> {}, _ -> routes.close()));
      assertThrows(IllegalStateException.class, app::start);
    }
  }

  @Test
  void reservesShapesDuringReentrantConfigurationAndReleasesFailedReservations() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      var routes = app.routes();
      assertThrows(
          IllegalArgumentException.class,
          () ->
              routes.get(
                  "/reserved/{id}",
                  (_, _) -> {},
                  _ -> routes.get("/reserved/{other}", (_, _) -> {})));
      app.routes().get("/reserved/{id}", (_, response) -> response.text("retry"));
      app.start();
      assertEquals(
          "retry",
          client
              .send(
                  HttpRequest.newBuilder(
                          URI.create("http://127.0.0.1:" + app.port() + "/reserved/42"))
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .body());
    }
  }

  @Test
  @Timeout(10)
  void configurationCanWaitForOtherThreadRegistrationAndImmediateStartupRejection()
      throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      app.routes()
          .get(
              "/first",
              (_, _) -> {},
              _ -> {
                try {
                  assertNotNull(
                      executor
                          .submit(() -> app.routes().get("/second", (_, _) -> {}))
                          .get(3, TimeUnit.SECONDS));
                  executor
                      .submit(() -> assertThrows(IllegalStateException.class, app::start))
                      .get(3, TimeUnit.SECONDS);
                } catch (Exception failure) {
                  throw new IllegalStateException(failure);
                }
              });
      app.start();
    }
  }

  @Test
  void webSocketConfigurationRejectsStartupAndReservesIndependentHandshakeShapes()
      throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0))) {
      var routes = app.routes();
      routes.websocket(
          "/socket",
          (_, _) -> new Session.Listener.AutoDemanding() {},
          _ -> {
            assertThrows(IllegalStateException.class, app::start);
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    routes.websocket("/socket", (_, _) -> new Session.Listener.AutoDemanding() {}));
          });
      app.routes().get("/socket", (_, _) -> {});
      app.start();
    }
  }

  @Test
  @Timeout(10)
  @SuppressWarnings("java:S9357") // Extension has no abstract methods and cannot be a lambda.
  void inheritedProviderConfigurationRunsOutsideTheLockWithStartupGuarded() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Extension<Object> provider =
          new Extension<>() {
            @Override
            public Object configure(Extensions endpoint, Object inherited) {
              if (inherited != null) {
                try {
                  assertNotNull(
                      executor
                          .submit(() -> app.routes().get("/parallel", (_, _) -> {}))
                          .get(3, TimeUnit.SECONDS));
                  executor
                      .submit(() -> assertThrows(IllegalStateException.class, app::start))
                      .get(3, TimeUnit.SECONDS);
                } catch (Exception failure) {
                  throw new IllegalStateException(failure);
                }
              }

              return new Object();
            }
          };
      app.extensions(provider);
      app.routes().path("/group", group -> group.get("/child", (_, _) -> {}), e -> e.get(provider));
      app.start();
    }
  }
}
