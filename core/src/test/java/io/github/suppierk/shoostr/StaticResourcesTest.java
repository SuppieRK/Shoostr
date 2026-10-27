package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.suppierk.shoostr.extensions.AdmissionExtension;
import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.http.HttpMethods;
import io.github.suppierk.shoostr.http.exceptions.UnauthorizedException;
import io.github.suppierk.shoostr.testing.TestServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.attribute.FileTime;
import java.security.KeyStore;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class StaticResourcesTest {
  @TempDir Path temporaryDirectory;

  @ParameterizedTest
  @CsvSource({
    "data.json,application/json,false",
    "style.css,text/css,false",
    "image.png,image/png,false",
    "data.shoostr-unknown,application/octet-stream,false",
    "data.json,application/json,true",
    "style.css,text/css,true",
    "image.png,image/png,true",
    "data.shoostr-unknown,application/octet-stream,true"
  })
  @Timeout(10)
  void usesFilenameMimeGuessesAndBinaryFallbackForBothStaticSources(
      String fileName, String expectedType, boolean classpath) throws Exception {
    if (!classpath) {
      assumeSecureDirectoryOperations();
    }

    var directory = Files.createDirectory(temporaryDirectory.resolve("issue76-mime"));
    var content = new byte[] {0, 1, 2, 3, (byte) 0xFF};
    Files.write(directory.resolve(fileName), content);
    var previous = Thread.currentThread().getContextClassLoader();

    try (var loader = new URLClassLoader(new URL[] {temporaryDirectory.toUri().toURL()}, previous);
        var app = new Shoostr()) {
      Thread.currentThread().setContextClassLoader(loader);
      if (classpath) {
        app.routes().classpathResources("/assets", "/issue76-mime");
      } else {
        app.routes().staticFiles("/assets", directory);
      }

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request -> request.path("/assets/" + fileName).timeout(Duration.ofSeconds(3)));
        assertEquals(200, result.statusCode());
        assertEquals(expectedType, result.headers().firstValue("Content-Type").orElseThrow());
        assertArrayEquals(content, result.body());
      }
    } finally {
      Thread.currentThread().setContextClassLoader(previous);
    }
  }

  @Test
  void rejectsParameterizedFilesystemMounts() {
    var app = new Shoostr();
    var routes = app.routes();

    assertThrows(
        IllegalArgumentException.class,
        () -> routes.staticFiles("/assets/{tenant}", temporaryDirectory));
  }

  @Test
  void rejectsFilesystemProvidersWithoutSecureDirectoryOperations() throws Exception {
    var archive = temporaryDirectory.resolve("resources.zip");

    try (var filesystem =
        FileSystems.newFileSystem(URI.create("jar:" + archive.toUri()), Map.of("create", "true"))) {
      var directory = Files.createDirectory(filesystem.getPath("/assets"));

      assertThrows(IllegalArgumentException.class, () -> new StaticFiles("/assets", directory));
    }
  }

  @Test
  void rejectsClasspathMountsWhoseDirectoriesAreUnavailable() {
    var app = new Shoostr();
    var routes = app.routes();

    assertThrows(
        IllegalArgumentException.class,
        () -> routes.classpathResources("/assets", "/missing-static-directory"));
  }

  @Test
  void servesFilesystemFilesBelowTheMountedPath() throws Exception {
    assumeSecureDirectoryOperations();
    Files.writeString(temporaryDirectory.resolve("site.txt"), "filesystem resource");

    try (var app = new Shoostr()) {
      app.routes().staticFiles("/assets", temporaryDirectory);

      try (var test = TestServer.start(app)) {

        var result =
            test.send(
                request -> request.path("/assets/site.txt"), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, result.statusCode());
        assertEquals("filesystem resource", result.body());
        assertEquals("text/plain", result.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("no-cache", result.headers().firstValue("Cache-Control").orElseThrow());
      }
    }
  }

  @Test
  void runsMatchedHooksInOrderWithTheStaticMountPattern() throws Exception {
    assumeSecureDirectoryOperations();
    Files.writeString(temporaryDirectory.resolve("site.txt"), "filesystem resource");

    try (var app = new Shoostr()) {
      app.onRouteMatched(
          (request, response) ->
              response.addHeader("X-Stage", "matched:" + request.routePattern().orElseThrow()));
      app.afterRouteHandler(
          (request, response) ->
              response.addHeader("X-Stage", "after:" + request.routePattern().orElseThrow()));
      app.routes().staticFiles("/assets", temporaryDirectory);

      try (var test = TestServer.start(app)) {

        var result =
            test.send(
                request -> request.path("/assets/site.txt").timeout(Duration.ofSeconds(3)),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, result.statusCode());
        assertEquals("filesystem resource", result.body());
        assertEquals(
            List.of("matched:/assets", "after:/assets"), result.headers().allValues("X-Stage"));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"/assets/missing.txt", "/outside"})
  void skipsMatchedHooksWhenNoMountedResourceMatches(String path) throws Exception {
    assumeSecureDirectoryOperations();
    Files.writeString(temporaryDirectory.resolve("site.txt"), "filesystem resource");
    var stages = new CopyOnWriteArrayList<String>();

    try (var app = new Shoostr()) {
      app.onRouteMatched((ignored, ignored2) -> stages.add("matched"));
      app.afterRouteHandler((ignored3, ignored4) -> stages.add("after"));
      app.routes().staticFiles("/assets", temporaryDirectory);

      try (var test = TestServer.start(app)) {

        var hit =
            test.send(
                request -> request.path("/assets/site.txt").timeout(Duration.ofSeconds(3)),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, hit.statusCode());
        assertEquals("filesystem resource", hit.body());
        assertEquals(List.of("matched", "after"), stages);
        stages.clear();

        var miss =
            test.send(
                request -> request.path(path).timeout(Duration.ofSeconds(3)),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(404, miss.statusCode());
        assertTrue(stages.isEmpty());
      }
    }
  }

  @Test
  void servesConfiguredWelcomeFilesAtTheMountAndNestedDirectories() throws Exception {
    assumeSecureDirectoryOperations();
    Files.writeString(temporaryDirectory.resolve("index.html"), "<h1>root</h1>");
    var nested = Files.createDirectory(temporaryDirectory.resolve("nested"));
    Files.writeString(nested.resolve("index.html"), "<h1>nested</h1>");

    try (var app = new Shoostr()) {
      app.routes()
          .staticFiles(
              "/assets",
              temporaryDirectory,
              StaticOptions.defaults().withWelcomeFile("index.html"));

      try (var test = TestServer.start(app)) {

        for (String path : List.of("/assets", "/assets/")) {
          var result =
              test.send(request -> request.path(path), HttpResponse.BodyHandlers.ofString());
          assertEquals(200, result.statusCode());
          assertEquals("<h1>root</h1>", result.body());
          assertEquals("text/html", result.headers().firstValue("Content-Type").orElseThrow());
        }

        for (String path : List.of("/assets/nested", "/assets/nested/")) {
          assertEquals(
              "<h1>nested</h1>",
              test.send(request -> request.path(path), HttpResponse.BodyHandlers.ofString())
                  .body());
        }

        assertEquals(
            404,
            test.send(
                    request -> request.path("/assets/missing"),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode());
      }
    }
  }

  @Test
  void usesConfiguredSpaFallbackOnlyForMissingResourcesInsideTheMount() throws Exception {
    assumeSecureDirectoryOperations();
    Files.writeString(temporaryDirectory.resolve("index.html"), "<h1>app</h1>");
    Files.writeString(temporaryDirectory.resolve("app.js"), "console.log('app');");

    try (var app = new Shoostr()) {
      app.routes()
          .staticFiles(
              "/assets",
              temporaryDirectory,
              StaticOptions.defaults().withSpaFallback("index.html"));
      app.routes().get("/assets/health", (ignored5, response) -> response.text("endpoint"));

      try (var test = TestServer.start(app)) {

        assertEquals(
            "<h1>app</h1>",
            test.send(
                    request -> request.path("/assets/orders/42"),
                    HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(
            "<h1>app</h1>",
            test.send(
                    request -> request.path("/assets/missing.js"),
                    HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(
            "text/html",
            test.send(
                    request -> request.path("/assets/missing.js"),
                    HttpResponse.BodyHandlers.ofString())
                .headers()
                .firstValue("Content-Type")
                .orElseThrow());
        assertEquals(
            "console.log('app');",
            test.send(
                    request -> request.path("/assets/app.js"), HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(
            "endpoint",
            test.send(
                    request -> request.path("/assets/health"), HttpResponse.BodyHandlers.ofString())
                .body());
        assertEquals(
            404,
            test.send(request -> request.path("/outside"), HttpResponse.BodyHandlers.ofString())
                .statusCode());
      }
    }
  }

  @Test
  void runsMatchedHooksInOrderWithTheSpaFallbackMountPattern() throws Exception {
    assumeSecureDirectoryOperations();
    Files.writeString(temporaryDirectory.resolve("index.html"), "<h1>app</h1>");

    try (var app = new Shoostr()) {
      app.onRouteMatched(
          (request, response) ->
              response.addHeader("X-Stage", "matched:" + request.routePattern().orElseThrow()));
      app.afterRouteHandler(
          (request, response) ->
              response.addHeader("X-Stage", "after:" + request.routePattern().orElseThrow()));
      app.routes()
          .staticFiles(
              "/assets",
              temporaryDirectory,
              StaticOptions.defaults().withSpaFallback("index.html"));

      try (var test = TestServer.start(app)) {

        var result =
            test.send(
                request -> request.path("/assets/orders/42").timeout(Duration.ofSeconds(3)),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, result.statusCode());
        assertEquals("<h1>app</h1>", result.body());
        assertEquals(
            List.of("matched:/assets", "after:/assets"), result.headers().allValues("X-Stage"));
      }
    }
  }

  @Test
  void servesConfiguredClasspathWelcomeFileWithoutEnablingFallback() throws Exception {
    try (var app = new Shoostr()) {
      app.routes()
          .classpathResources(
              "/assets", "/", StaticOptions.defaults().withWelcomeFile("static-resource.txt"));

      try (var test = TestServer.start(app)) {

        assertEquals(
            "classpath resource",
            test.send(request -> request.path("/assets"), HttpResponse.BodyHandlers.ofString())
                .body()
                .trim());
        assertEquals(
            404,
            test.send(
                    request -> request.path("/assets/missing"),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode());
      }
    }
  }

  @Test
  void servesConfiguredClasspathSpaFallbackWithoutEnablingWelcomeFile() throws Exception {
    try (var app = new Shoostr()) {
      app.routes()
          .classpathResources(
              "/assets", "/", StaticOptions.defaults().withSpaFallback("static-resource.txt"));

      try (var test = TestServer.start(app)) {

        assertEquals(
            "classpath resource",
            test.send(request -> request.path("/assets"), HttpResponse.BodyHandlers.ofString())
                .body()
                .trim());
        assertEquals(
            "classpath resource",
            test.send(
                    request -> request.path("/assets/missing"),
                    HttpResponse.BodyHandlers.ofString())
                .body()
                .trim());
      }
    }
  }

  @Test
  void rejectsUnsafeConfiguredStaticFileNames() {
    var defaults = StaticOptions.defaults();
    assertThrows(IllegalArgumentException.class, () -> defaults.withWelcomeFile("../index.html"));
    assertThrows(IllegalArgumentException.class, () -> defaults.withSpaFallback("/index.html"));
    assertThrows(IllegalArgumentException.class, () -> defaults.withSpaFallback("index\\.html"));
  }

  @Test
  void appliesRouteAdmissionAndHeadSemanticsToSpaFallback() throws Exception {
    assumeSecureDirectoryOperations();
    Files.writeString(temporaryDirectory.resolve("index.html"), "<h1>app</h1>");

    try (var app = new Shoostr()) {
      app.beforeRouteHandler(
          (request, ignored6) -> {
            if ("/assets/blocked".equals(request.path())) {
              throw new UnauthorizedException();
            }
          });
      app.routes()
          .staticFiles(
              "/assets",
              temporaryDirectory,
              StaticOptions.defaults().withSpaFallback("index.html"));

      try (var test = TestServer.start(app)) {

        assertEquals(
            401,
            test.send(
                    request -> request.path("/assets/blocked"),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode());
        var head =
            test.send(
                request -> request.path("/assets/missing").method("HEAD"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, head.statusCode());
        assertEquals("", head.body());
        assertEquals("12", head.headers().firstValue("Content-Length").orElseThrow());
      }
    }
  }

  @Test
  void corsPreflightSkipsScopedAdmissionButActualSpaFallbackRequiresIt() throws Exception {
    assumeSecureDirectoryOperations();
    Files.writeString(temporaryDirectory.resolve("index.html"), "<h1>app</h1>");
    var admissions = new AtomicInteger();

    try (var app = new Shoostr()) {
      var cors =
          new Cors(
              new CorsPolicy(
                  Set.of("https://client.example"),
                  Set.of(HttpMethods.GET),
                  Set.of(HttpHeaders.AUTHORIZATION),
                  true,
                  Set.of(),
                  5));
      app.extensions(cors);
      var admission =
          new AdmissionExtension(
              (request, ignored7) -> {
                admissions.incrementAndGet();
                if (request.header("Authorization").filter("Bearer allowed"::equals).isEmpty()) {
                  throw new UnauthorizedException();
                }
              });
      app.extensions(admission)
          .routes()
          .path(
              "/",
              routes ->
                  routes.staticFiles(
                      "/assets",
                      temporaryDirectory,
                      StaticOptions.defaults().withSpaFallback("index.html")),
              e -> {
                e.with(admission);
                e.with(cors);
              });

      try (var test = TestServer.start(app)) {

        var preflight =
            test.send(
                request ->
                    request
                        .path("/assets/missing")
                        .header("Origin", "https://client.example")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "Authorization")
                        .method("OPTIONS"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(204, preflight.statusCode());
        assertEquals(0, admissions.get());
        assertEquals(
            "authorization",
            preflight.headers().firstValue("Access-Control-Allow-Headers").orElseThrow());

        var denied =
            test.send(
                request ->
                    request.path("/assets/missing").header("Origin", "https://client.example"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, denied.statusCode());
        assertEquals(1, admissions.get());
        assertEquals(
            "https://client.example",
            denied.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());

        var allowed =
            test.send(
                request ->
                    request
                        .path("/assets/missing")
                        .header("Origin", "https://client.example")
                        .header("Authorization", "Bearer allowed"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, allowed.statusCode());
        assertEquals("<h1>app</h1>", allowed.body());
        assertEquals(2, admissions.get());
        assertEquals(
            "https://client.example",
            allowed.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
      }
    }
  }

  @Test
  void doesNotSwitchToSpaFallbackWhenSelectedFileDisappearsAfterAdmission() throws Exception {
    assumeSecureDirectoryOperations();
    var selected = temporaryDirectory.resolve("app.js");
    Files.writeString(selected, "selected");
    Files.writeString(temporaryDirectory.resolve("index.html"), "fallback");

    try (var app = new Shoostr()) {
      app.beforeRouteHandler((ignored8, ignored9) -> Files.delete(selected));
      app.routes()
          .staticFiles(
              "/assets",
              temporaryDirectory,
              StaticOptions.defaults().withSpaFallback("index.html"));

      try (var test = TestServer.start(app)) {

        var result =
            test.send(
                request -> request.path("/assets/app.js"), HttpResponse.BodyHandlers.ofString());
        assertEquals(404, result.statusCode());
        assertFalse(result.body().contains("fallback"));
      }
    }
  }

  @Test
  void spaFallbackDoesNotExposeTraversalOrSymlinksOutsideMount() throws Exception {
    assumeSecureDirectoryOperations();
    var publicDirectory = Files.createDirectory(temporaryDirectory.resolve("public"));
    Files.writeString(publicDirectory.resolve("index.html"), "fallback");
    Files.writeString(temporaryDirectory.resolve("secret.txt"), "top-secret-content");
    Files.createSymbolicLink(
        publicDirectory.resolve("link.txt"), temporaryDirectory.resolve("secret.txt"));

    try (var app = new Shoostr()) {
      app.routes()
          .staticFiles(
              "/assets", publicDirectory, StaticOptions.defaults().withSpaFallback("index.html"));

      try (var test = TestServer.start(app)) {

        var traversal =
            test.send(
                request -> request.path("/assets/%2e%2e/secret.txt"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, traversal.statusCode());
        assertFalse(traversal.body().contains("top-secret-content"));
        assertEquals(
            "fallback",
            test.send(
                    request -> request.path("/assets/link.txt"),
                    HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }

  @Test
  void redirectAfterMountedFileDiscardsTheSelectedResource() throws Exception {
    assumeSecureDirectoryOperations();
    Files.writeString(temporaryDirectory.resolve("site.txt"), "filesystem resource");

    try (var app = new Shoostr()) {
      app.routes().staticFiles("/assets", temporaryDirectory);
      app.afterRouteHandler((ignored10, response) -> response.redirect("/next"));

      try (var test = TestServer.start(app)) {

        var result =
            test.send(
                request -> request.path("/assets/site.txt").header("Range", "bytes=0-3"),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(302, result.statusCode());
        assertEquals("/next", result.headers().firstValue("Location").orElseThrow());
        assertEquals("", result.body());
        assertEquals(List.of(), result.headers().allValues("Content-Range"));
        assertEquals(List.of(), result.headers().allValues("ETag"));
        assertEquals(List.of(), result.headers().allValues("Accept-Ranges"));
        assertEquals(List.of(), result.headers().allValues("Content-Type"));
      }
    }
  }

  @Test
  @Timeout(10)
  void servesUtf8NamedClasspathFilesThroughEncodedPathsWithoutEscapingTheMount() throws Exception {
    var publicDirectory = Files.createDirectory(temporaryDirectory.resolve("issue76-utf8-public"));
    var content = "UTF-8 classpath resource: café €\n".getBytes(StandardCharsets.UTF_8);
    Files.write(publicDirectory.resolve("tést.txt"), content);
    Files.writeString(
        temporaryDirectory.resolve("privé.txt"), "outside-mount-secret", StandardCharsets.UTF_8);
    var previous = Thread.currentThread().getContextClassLoader();

    try (var loader = new URLClassLoader(new URL[] {temporaryDirectory.toUri().toURL()}, previous);
        var app = new Shoostr()) {
      Thread.currentThread().setContextClassLoader(loader);
      app.routes().classpathResources("/assets", "/issue76-utf8-public");

      try (var test = TestServer.start(app)) {

        var result =
            test.send(
                request -> request.path("/assets/t%C3%A9st.txt").timeout(Duration.ofSeconds(3)));

        assertEquals(200, result.statusCode());
        assertArrayEquals(content, result.body());

        var outside =
            test.send(
                request -> request.path("/assets/priv%C3%A9.txt").timeout(Duration.ofSeconds(3)),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, outside.statusCode());
        assertFalse(outside.body().contains("outside-mount-secret"));
      }
    } finally {
      Thread.currentThread().setContextClassLoader(previous);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @Timeout(15)
  void validatesTheFallbackResourceSelectedFromComposedStaticSources(boolean filesystemFirst)
      throws Exception {
    assumeSecureDirectoryOperations();
    var filesystem = Files.createDirectory(temporaryDirectory.resolve("filesystem"));
    var classpath = Files.createDirectory(temporaryDirectory.resolve("issue76-composed"));
    var fallback = filesystemFirst ? classpath : filesystem;
    Files.writeString(fallback.resolve("fallback.txt"), "selected fallback resource");
    var previous = Thread.currentThread().getContextClassLoader();

    try (var loader = new URLClassLoader(new URL[] {temporaryDirectory.toUri().toURL()}, previous);
        var app = new Shoostr()) {
      Thread.currentThread().setContextClassLoader(loader);
      registerComposedStaticSources(app.routes(), filesystem, filesystemFirst);

      try (var test = TestServer.start(app)) {

        var selected =
            test.send(
                request -> request.path("/assets/fallback.txt").timeout(Duration.ofSeconds(3)),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, selected.statusCode());
        assertEquals("selected fallback resource", selected.body());
        var etag = selected.headers().firstValue("ETag").orElseThrow();
        assertFalse(etag.isBlank());

        var result =
            test.send(
                request ->
                    request
                        .path("/assets/fallback.txt")
                        .timeout(Duration.ofSeconds(3))
                        .header("If-None-Match", etag),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(304, result.statusCode());
        assertEquals("", result.body());
        assertEquals(etag, result.headers().firstValue("ETag").orElseThrow());
      }
    } finally {
      Thread.currentThread().setContextClassLoader(previous);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @Timeout(15)
  void returnsNotFoundWhenNeitherComposedStaticSourceContainsThePath(boolean filesystemFirst)
      throws Exception {
    assumeSecureDirectoryOperations();
    var filesystem = Files.createDirectory(temporaryDirectory.resolve("filesystem"));
    var classpath = Files.createDirectory(temporaryDirectory.resolve("issue76-composed"));
    Files.writeString(filesystem.resolve("shared.txt"), "filesystem content");
    Files.writeString(classpath.resolve("shared.txt"), "classpath content");
    var previous = Thread.currentThread().getContextClassLoader();

    try (var loader = new URLClassLoader(new URL[] {temporaryDirectory.toUri().toURL()}, previous);
        var app = new Shoostr()) {
      Thread.currentThread().setContextClassLoader(loader);
      registerComposedStaticSources(app.routes(), filesystem, filesystemFirst);

      try (var test = TestServer.start(app)) {

        var control =
            test.send(
                request -> request.path("/assets/shared.txt").timeout(Duration.ofSeconds(3)),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, control.statusCode());
        assertEquals(filesystemFirst ? "filesystem content" : "classpath content", control.body());

        var result =
            test.send(
                request -> request.path("/assets/missing.txt").timeout(Duration.ofSeconds(3)),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(404, result.statusCode());
        assertEquals("Not found", result.body());
        assertTrue(result.headers().allValues("ETag").isEmpty());
      }
    } finally {
      Thread.currentThread().setContextClassLoader(previous);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @Timeout(15)
  void prefersTheFirstRegisteredStaticSourceWhenBothContainTheSamePath(boolean filesystemFirst)
      throws Exception {
    assumeSecureDirectoryOperations();
    var filesystem = Files.createDirectory(temporaryDirectory.resolve("filesystem"));
    var classpath = Files.createDirectory(temporaryDirectory.resolve("issue76-composed"));
    Files.writeString(filesystem.resolve("shared.txt"), "filesystem winner");
    Files.writeString(classpath.resolve("shared.txt"), "classpath candidate");
    var previous = Thread.currentThread().getContextClassLoader();

    try (var loader = new URLClassLoader(new URL[] {temporaryDirectory.toUri().toURL()}, previous);
        var app = new Shoostr()) {
      Thread.currentThread().setContextClassLoader(loader);
      registerComposedStaticSources(app.routes(), filesystem, filesystemFirst);

      try (var test = TestServer.start(app)) {

        var result =
            test.send(
                request -> request.path("/assets/shared.txt").timeout(Duration.ofSeconds(3)),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, result.statusCode());
        assertEquals(filesystemFirst ? "filesystem winner" : "classpath candidate", result.body());
      }
    } finally {
      Thread.currentThread().setContextClassLoader(previous);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @Timeout(15)
  void fallsBackBetweenFilesystemAndClasspathSourcesAtTheSamePrefix(boolean filesystemFirst)
      throws Exception {
    assumeSecureDirectoryOperations();
    var filesystem = Files.createDirectory(temporaryDirectory.resolve("filesystem"));
    var classpath = Files.createDirectory(temporaryDirectory.resolve("issue76-composed"));
    Files.writeString(filesystem.resolve("filesystem-only.txt"), "filesystem content");
    Files.writeString(classpath.resolve("classpath-only.txt"), "classpath content");
    var previous = Thread.currentThread().getContextClassLoader();

    try (var loader = new URLClassLoader(new URL[] {temporaryDirectory.toUri().toURL()}, previous);
        var app = new Shoostr()) {
      Thread.currentThread().setContextClassLoader(loader);
      registerComposedStaticSources(app.routes(), filesystem, filesystemFirst);

      try (var test = TestServer.start(app)) {

        var filesystemResult =
            test.send(
                request ->
                    request.path("/assets/filesystem-only.txt").timeout(Duration.ofSeconds(3)),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, filesystemResult.statusCode());
        assertEquals("filesystem content", filesystemResult.body());

        var classpathResult =
            test.send(
                request ->
                    request.path("/assets/classpath-only.txt").timeout(Duration.ofSeconds(3)),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, classpathResult.statusCode());
        assertEquals("classpath content", classpathResult.body());
      }
    } finally {
      Thread.currentThread().setContextClassLoader(previous);
    }
  }

  @Test
  void servesClasspathFilesBelowTheMountedPath() throws Exception {
    try (var app = new Shoostr()) {
      app.routes().classpathResources("/assets", "/");

      try (var test = TestServer.start(app)) {

        var result =
            test.send(
                request -> request.path("/assets/static-resource.txt"),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, result.statusCode());
        assertEquals("classpath resource", result.body().trim());
        assertEquals("text/plain", result.headers().firstValue("Content-Type").orElseThrow());
      }
    }
  }

  @Test
  void closesTheMountedArchiveFilesystem() throws Exception {
    var archive = temporaryDirectory.resolve("resources.jar");
    writeArchive(archive);
    var previous = Thread.currentThread().getContextClassLoader();

    try (var loader = new URLClassLoader(new URL[] {archive.toUri().toURL()}, previous)) {
      Thread.currentThread().setContextClassLoader(loader);
      var files = new StaticFiles("/assets", "/archive");
      var filesystem = Objects.requireNonNull(files.archiveFileSystem());
      assertTrue(filesystem.isOpen());
      files.close();
      assertFalse(filesystem.isOpen());
    } finally {
      Thread.currentThread().setContextClassLoader(previous);
    }
  }

  @Test
  void closesArchiveResourcesWhenRegistrationIsAbandoned() throws Exception {
    var archive = temporaryDirectory.resolve("resources.jar");
    writeArchive(archive);
    var previous = Thread.currentThread().getContextClassLoader();

    try (var loader = new URLClassLoader(new URL[] {archive.toUri().toURL()}, previous)) {
      Thread.currentThread().setContextClassLoader(loader);
      var routes = new Routes();
      routes.classpathResources("/assets", "/archive");
      var filesystem = registeredArchiveFilesystem(routes);
      assertTrue(filesystem.isOpen());
      routes.close();

      assertFalse(filesystem.isOpen());
      assertThrows(
          IllegalStateException.class, () -> routes.classpathResources("/again", "/archive"));
    } finally {
      Thread.currentThread().setContextClassLoader(previous);
    }
  }

  @Test
  void closesArchiveResourcesWhenStartupFails() throws Exception {
    var archive = temporaryDirectory.resolve("resources.jar");
    writeArchive(archive);
    var previous = Thread.currentThread().getContextClassLoader();

    try (var loader = new URLClassLoader(new URL[] {archive.toUri().toURL()}, previous);
        var occupied = new ServerSocket(0);
        var app =
            new Shoostr()
                .modifyServer(
                    nativeServer ->
                        ((ServerConnector) nativeServer.getConnectors()[0])
                            .setPort(occupied.getLocalPort()))) {
      Thread.currentThread().setContextClassLoader(loader);
      var routes = app.routes();
      routes.classpathResources("/assets", "/archive");
      var filesystem = registeredArchiveFilesystem(routes);
      assertTrue(filesystem.isOpen());

      assertThrows(Exception.class, app::start);
      assertFalse(filesystem.isOpen());
      assertThrows(IllegalStateException.class, app::port);
    } finally {
      Thread.currentThread().setContextClassLoader(previous);
    }
  }

  @Test
  void servesClasspathFilesFromAnArchive() throws Exception {
    var archive = temporaryDirectory.resolve("resources.jar");
    writeArchive(archive);
    var previous = Thread.currentThread().getContextClassLoader();

    try (var loader = new URLClassLoader(new URL[] {archive.toUri().toURL()}, previous);
        var app = new Shoostr()) {
      Thread.currentThread().setContextClassLoader(loader);
      var routes = app.routes();
      routes.classpathResources("/assets", "/archive");
      var filesystem = registeredArchiveFilesystem(routes);
      assertTrue(filesystem.isOpen());

      try (var test = TestServer.start(app)) {

        var result =
            test.send(
                request -> request.path("/assets/resource.txt"),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, result.statusCode());
        assertEquals("archived resource", result.body());
        test.close();
        assertFalse(filesystem.isOpen());
      }
    } finally {
      Thread.currentThread().setContextClassLoader(previous);
    }
  }

  @Test
  void supportsHeadRangesAndValidatorsForMountedFiles() throws Exception {
    assumeSecureDirectoryOperations();
    var file = temporaryDirectory.resolve("site.txt");
    Files.writeString(file, "filesystem resource");

    try (var app = new Shoostr()) {
      app.routes().staticFiles("/assets", temporaryDirectory);

      try (var test = TestServer.start(app)) {

        var full =
            test.send(
                request -> request.path("/assets/site.txt"), HttpResponse.BodyHandlers.ofString());
        var range =
            test.send(
                request -> request.path("/assets/site.txt").header("Range", "bytes=5-8"),
                HttpResponse.BodyHandlers.ofString());
        var conditional =
            test.send(
                request ->
                    request
                        .path("/assets/site.txt")
                        .header("If-None-Match", full.headers().firstValue("ETag").orElseThrow()),
                HttpResponse.BodyHandlers.ofString());
        var head =
            test.send(
                request -> request.path("/assets/site.txt").method("HEAD"),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(206, range.statusCode());
        assertEquals("yste", range.body());
        assertEquals(304, conditional.statusCode());
        assertEquals(200, head.statusCode());
        assertEquals("19", head.headers().firstValue("Content-Length").orElseThrow());
      }
    }
  }

  @ParameterizedTest
  @CsvSource({"false, true, HTTP_2", "true, false, HTTP_1_1", "true, true, HTTP_2"})
  @Timeout(10)
  void servesExactMountedFileBytesOverTheConfiguredProtocol(
      boolean tls, boolean http2, HttpClient.Version expectedVersion) throws Exception {
    assumeSecureDirectoryOperations();
    Files.writeString(temporaryDirectory.resolve("site.txt"), "static over transport: €\n");

    try (var app =
            new Shoostr()
                .modifyServer(
                    nativeServer ->
                        ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));
        var client = staticTransportClient(app, tls, http2)) {
      app.routes().staticFiles("/assets", temporaryDirectory);
      app.start();
      var target =
          URI.create((tls ? "https" : "http") + "://127.0.0.1:" + app.port() + "/assets/site.txt");
      var result =
          client.send(
              HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(3)).GET().build(),
              HttpResponse.BodyHandlers.ofByteArray());

      assertEquals(expectedVersion, result.version());
      assertEquals(200, result.statusCode());
      assertEquals("text/plain", result.headers().firstValue("Content-Type").orElseThrow());
      assertArrayEquals(
          "static over transport: €\n".getBytes(StandardCharsets.UTF_8), result.body());
      assertEquals(tls, result.sslSession().isPresent());
    }
  }

  @ParameterizedTest
  @CsvSource({"false, true, HTTP_2", "true, false, HTTP_1_1", "true, true, HTTP_2"})
  @Timeout(10)
  void returnsMountedFileHeadMetadataWithoutBytesOverTheConfiguredProtocol(
      boolean tls, boolean http2, HttpClient.Version expectedVersion) throws Exception {
    assumeSecureDirectoryOperations();
    Files.writeString(temporaryDirectory.resolve("site.txt"), "static over transport: €\n");

    try (var app =
            new Shoostr()
                .modifyServer(
                    nativeServer ->
                        ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));
        var client = staticTransportClient(app, tls, http2)) {
      app.routes().staticFiles("/assets", temporaryDirectory);
      app.start();
      var target =
          URI.create((tls ? "https" : "http") + "://127.0.0.1:" + app.port() + "/assets/site.txt");
      var result =
          client.send(
              HttpRequest.newBuilder(target)
                  .timeout(Duration.ofSeconds(3))
                  .method("HEAD", HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());

      assertEquals(expectedVersion, result.version());
      assertEquals(200, result.statusCode());
      assertEquals("text/plain", result.headers().firstValue("Content-Type").orElseThrow());
      assertEquals("27", result.headers().firstValue("Content-Length").orElseThrow());
      assertEquals(0, result.body().length);
      assertEquals(tls, result.sslSession().isPresent());
    }
  }

  @ParameterizedTest
  @CsvSource({"false, true, HTTP_2", "true, false, HTTP_1_1", "true, true, HTTP_2"})
  @Timeout(10)
  void returnsNotModifiedForAnUnchangedMountedFileAcrossResponseDates(
      boolean tls, boolean http2, HttpClient.Version expectedVersion) throws Exception {
    assumeSecureDirectoryOperations();
    Files.writeString(temporaryDirectory.resolve("site.txt"), "static over transport: €\n");
    Files.setLastModifiedTime(
        temporaryDirectory.resolve("site.txt"),
        FileTime.from(Instant.parse("2020-01-01T00:00:00.500Z")));

    try (var app =
            new Shoostr()
                .modifyServer(
                    nativeServer ->
                        ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0));
        var client = staticTransportClient(app, tls, http2)) {
      app.beforeRouteHandler(
          (request, response) ->
              response.setHeader(
                  "Date",
                  request.header("If-None-Match").isPresent()
                      ? "Wed, 01 Jan 2020 00:00:01 GMT"
                      : "Wed, 01 Jan 2020 00:00:00 GMT"));
      app.routes().staticFiles("/assets", temporaryDirectory);
      app.start();
      var target =
          URI.create((tls ? "https" : "http") + "://127.0.0.1:" + app.port() + "/assets/site.txt");
      var full =
          client.send(
              HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(3)).GET().build(),
              HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(expectedVersion, full.version());
      assertEquals(200, full.statusCode());
      assertArrayEquals("static over transport: €\n".getBytes(StandardCharsets.UTF_8), full.body());
      assertEquals(
          "Wed, 01 Jan 2020 00:00:00 GMT", full.headers().firstValue("Date").orElseThrow());
      assertEquals(
          Instant.parse("2020-01-01T00:00:00Z"),
          ZonedDateTime.parse(
                  full.headers().firstValue("Last-Modified").orElseThrow(),
                  DateTimeFormatter.RFC_1123_DATE_TIME)
              .toInstant());
      var etag = full.headers().firstValue("ETag").orElseThrow();

      var conditional =
          client.send(
              HttpRequest.newBuilder(target)
                  .timeout(Duration.ofSeconds(3))
                  .header("If-None-Match", etag)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());

      assertEquals(expectedVersion, conditional.version());
      assertEquals(
          "Wed, 01 Jan 2020 00:00:01 GMT", conditional.headers().firstValue("Date").orElseThrow());
      assertEquals(
          304,
          conditional.statusCode(),
          () -> "Requested ETag " + etag + "; returned headers " + conditional.headers().map());
      assertEquals(etag, conditional.headers().firstValue("ETag").orElseThrow());
      assertEquals(0, conditional.body().length);
      assertEquals(tls, conditional.sslSession().isPresent());
    }
  }

  @Test
  void compressesMountedFilesWhenGzipIsAccepted() throws Exception {
    assumeSecureDirectoryOperations();
    var payload = "mounted-compressible-data-€\n".repeat(1024);
    Files.writeString(temporaryDirectory.resolve("site.txt"), payload);

    try (var app = new Shoostr()) {
      app.compression();
      app.routes().staticFiles("/assets", temporaryDirectory);

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request ->
                    request
                        .path("/assets/site.txt")
                        .timeout(Duration.ofSeconds(3))
                        .header("Accept-Encoding", "gzip"));
        assertEquals(200, result.statusCode());
        assertEquals("gzip", result.headers().firstValue("Content-Encoding").orElseThrow());
        assertEquals("text/plain", result.headers().firstValue("Content-Type").orElseThrow());
        assertTrue(result.headers().allValues("Vary").contains("Accept-Encoding"));

        try (var decoded = new GZIPInputStream(new ByteArrayInputStream(result.body()))) {
          assertArrayEquals(payload.getBytes(StandardCharsets.UTF_8), decoded.readAllBytes());
        }
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"identity", "gzip;q=0"})
  void servesMountedFilesUnencodedWhenGzipIsNotAccepted(String acceptEncoding) throws Exception {
    assumeSecureDirectoryOperations();
    var payload = "mounted-compressible-data-€\n".repeat(1024);
    Files.writeString(temporaryDirectory.resolve("site.txt"), payload);

    try (var app = new Shoostr()) {
      app.compression();
      app.routes().staticFiles("/assets", temporaryDirectory);

      try (var test = TestServer.start(app)) {
        var result =
            test.send(
                request ->
                    request
                        .path("/assets/site.txt")
                        .timeout(Duration.ofSeconds(3))
                        .header("Accept-Encoding", acceptEncoding));
        assertEquals(200, result.statusCode());
        assertTrue(result.headers().firstValue("Content-Encoding").isEmpty());
        assertTrue(result.headers().allValues("Vary").contains("Accept-Encoding"));
        assertArrayEquals(payload.getBytes(StandardCharsets.UTF_8), result.body());
      }
    }
  }

  @ParameterizedTest
  @CsvSource({
    "gzip, If-None-Match, ETag",
    "identity, If-None-Match, ETag",
    "gzip, If-Modified-Since, Last-Modified",
    "identity, If-Modified-Since, Last-Modified"
  })
  void returnsNotModifiedForMountedFileValidatorsWithCompressionEnabled(
      String acceptEncoding, String requestHeader, String responseHeader) throws Exception {
    assumeSecureDirectoryOperations();
    var payload = "mounted-compressible-data-€\n".repeat(1024);
    Files.writeString(temporaryDirectory.resolve("site.txt"), payload);

    try (var app = new Shoostr()) {
      app.compression();
      app.routes().staticFiles("/assets", temporaryDirectory);

      try (var test = TestServer.start(app)) {

        var full =
            test.send(
                request ->
                    request
                        .path("/assets/site.txt")
                        .timeout(Duration.ofSeconds(3))
                        .header("Accept-Encoding", acceptEncoding));
        assertEquals(200, full.statusCode());
        assertEquals(
            acceptEncoding, full.headers().firstValue("Content-Encoding").orElse("identity"));
        var validator = full.headers().firstValue(responseHeader).orElseThrow();

        var conditional =
            test.send(
                request ->
                    request
                        .path("/assets/site.txt")
                        .timeout(Duration.ofSeconds(3))
                        .header("Accept-Encoding", acceptEncoding)
                        .header(requestHeader, validator));
        assertEquals(304, conditional.statusCode());
        assertEquals(0, conditional.body().length);
        assertEquals(validator, conditional.headers().firstValue(responseHeader).orElseThrow());
        assertTrue(conditional.headers().allValues("Vary").contains("Accept-Encoding"));
      }
    }
  }

  @Test
  void givesExplicitEndpointsPrecedenceOverMountedFiles() throws Exception {
    assumeSecureDirectoryOperations();
    Files.writeString(temporaryDirectory.resolve("site.txt"), "filesystem resource");

    try (var app = new Shoostr()) {
      app.routes().staticFiles("/assets", temporaryDirectory);
      app.routes().get("/assets/site.txt", (ignored11, response) -> response.text("endpoint"));

      try (var test = TestServer.start(app)) {

        var result =
            test.send(
                request -> request.path("/assets/site.txt"), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, result.statusCode());
        assertEquals("endpoint", result.body());
      }
    }
  }

  @Test
  void composesFilesystemMountsInsidePathGroups() throws Exception {
    assumeSecureDirectoryOperations();
    Files.writeString(temporaryDirectory.resolve("site.txt"), "composed resource");

    try (var app = new Shoostr()) {
      app.routes().path("/api", routes -> routes.staticFiles("assets", temporaryDirectory));

      try (var test = TestServer.start(app)) {

        var result =
            test.send(
                request -> request.path("/api/assets/site.txt"),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, result.statusCode());
        assertEquals("composed resource", result.body());
      }
    }
  }

  @Test
  void doesNotExposeAnEncodedTraversalOutsideTheMountedDirectory() throws Exception {
    assumeSecureDirectoryOperations();
    var publicDirectory = Files.createDirectory(temporaryDirectory.resolve("public"));
    Files.writeString(temporaryDirectory.resolve("secret.txt"), "top-secret-content");

    try (var app = new Shoostr()) {
      app.routes().staticFiles("/assets", publicDirectory);

      try (var test = TestServer.start(app)) {

        var result =
            test.send(
                request -> request.path("/assets/%2e%2e/secret.txt"),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(400, result.statusCode());
        assertFalse(result.body().contains("top-secret-content"));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"/%252e%252e/secret.txt", "/..%5csecret.txt", "/site.txt%00"})
  void rejectsUnsafeEncodedPathsUnderFilesystemMount(String path) throws Exception {
    assumeSecureDirectoryOperations();
    var publicDirectory = Files.createDirectory(temporaryDirectory.resolve("public"));
    Files.writeString(publicDirectory.resolve("site.txt"), "public resource");
    Files.writeString(temporaryDirectory.resolve("secret.txt"), "outside-mount-secret");

    try (var app = new Shoostr()) {
      app.routes().staticFiles("/assets", publicDirectory);

      try (var test = TestServer.start(app)) {
        var allowed =
            test.send(
                request -> request.path("/assets/site.txt"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, allowed.statusCode());
        assertEquals("public resource", allowed.body());

        String rejected;

        try (var socket = new Socket(InetAddress.getAllByName("127.0.0.1")[0], app.port())) {
          socket.setSoTimeout(3000);
          var outgoing =
              "GET "
                  + ("/assets" + path)
                  + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
          socket.getOutputStream().write(outgoing.getBytes(StandardCharsets.US_ASCII));
          rejected = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(rejected.startsWith("HTTP/1.1 400 "), rejected);
        assertFalse(rejected.contains("outside-mount-secret"));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"/%252e%252e/secret.txt", "/..%5csecret.txt", "/site.txt%00"})
  void rejectsUnsafeEncodedPathsUnderClasspathMount(String path) throws Exception {
    var publicDirectory = Files.createDirectory(temporaryDirectory.resolve("issue76-public"));
    Files.writeString(publicDirectory.resolve("site.txt"), "public resource");
    Files.writeString(temporaryDirectory.resolve("secret.txt"), "outside-mount-secret");
    var previous = Thread.currentThread().getContextClassLoader();

    try (var loader = new URLClassLoader(new URL[] {temporaryDirectory.toUri().toURL()}, previous);
        var app = new Shoostr()) {
      Thread.currentThread().setContextClassLoader(loader);
      app.routes().classpathResources("/assets", "/issue76-public");

      try (var test = TestServer.start(app)) {
        var allowed =
            test.send(
                request -> request.path("/assets/site.txt"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, allowed.statusCode());
        assertEquals("public resource", allowed.body());

        String rejected;

        try (var socket = new Socket(InetAddress.getAllByName("127.0.0.1")[0], app.port())) {
          socket.setSoTimeout(3000);
          var outgoing =
              "GET "
                  + ("/assets" + path)
                  + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
          socket.getOutputStream().write(outgoing.getBytes(StandardCharsets.US_ASCII));
          rejected = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(rejected.startsWith("HTTP/1.1 400 "), rejected);
        assertFalse(rejected.contains("outside-mount-secret"));
      }
    } finally {
      Thread.currentThread().setContextClassLoader(previous);
    }
  }

  @Test
  void returnsNotFoundForMissingFilesDirectoriesAndNonRegularResources() throws Exception {
    assumeSecureDirectoryOperations();
    Files.createDirectory(temporaryDirectory.resolve("directory"));
    var nonRegular = temporaryDirectory.resolve("named-pipe");
    var process = new ProcessBuilder("mkfifo", nonRegular.toString()).start();
    assertTrue(process.waitFor(5, TimeUnit.SECONDS));
    assertEquals(0, process.exitValue());

    try (var app = new Shoostr()) {
      app.routes().staticFiles("/assets", temporaryDirectory);

      try (var test = TestServer.start(app)) {

        var missing =
            test.send(
                request -> request.path("/assets/missing.txt"),
                HttpResponse.BodyHandlers.ofString());
        var directory =
            test.send(
                request -> request.path("/assets/directory"), HttpResponse.BodyHandlers.ofString());
        var pipe =
            test.send(
                request -> request.path("/assets/named-pipe").timeout(Duration.ofSeconds(2)),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(404, missing.statusCode());
        assertEquals(404, directory.statusCode());
        assertEquals(404, pipe.statusCode());
      }
    }
  }

  @Test
  void appliesMatchedRouteGatesToMountedFiles() throws Exception {
    assumeSecureDirectoryOperations();
    Files.writeString(temporaryDirectory.resolve("site.txt"), "filesystem resource");

    try (var app = new Shoostr()) {
      app.beforeRouteHandler(
          (ignored12, ignored13) -> {
            throw new UnauthorizedException();
          });
      app.routes().staticFiles("/assets", temporaryDirectory);

      try (var test = TestServer.start(app)) {

        var result =
            test.send(
                request -> request.path("/assets/site.txt"), HttpResponse.BodyHandlers.ofString());

        assertEquals(401, result.statusCode());
      }
    }
  }

  @Test
  void reportsMountedFileRequestsToTerminalObservers() throws Exception {
    assumeSecureDirectoryOperations();
    Files.writeString(temporaryDirectory.resolve("site.txt"), "filesystem resource");
    var outcome = new AtomicReference<RequestOutcome>();
    var completed = new CountDownLatch(1);

    try (var app = new Shoostr()) {
      app.afterRequest(
          value -> {
            outcome.set(value);
            completed.countDown();
          });
      app.routes().staticFiles("/assets", temporaryDirectory);

      try (var test = TestServer.start(app)) {

        assertEquals(
            200,
            test.send(
                    request -> request.path("/assets/site.txt"),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode());
        assertTrue(completed.await(5, TimeUnit.SECONDS));
        assertEquals(200, outcome.get().statusCode());
        assertEquals("/assets", outcome.get().routePattern());
      }
    }
  }

  @Test
  void resolvesTheCurrentFilesystemFileForEachRequest() throws Exception {
    assumeSecureDirectoryOperations();
    var file = temporaryDirectory.resolve("site.txt");
    Files.writeString(file, "first");

    try (var app = new Shoostr()) {
      app.routes().staticFiles("/assets", temporaryDirectory);

      try (var test = TestServer.start(app)) {

        assertEquals(
            "first",
            test.send(
                    request -> request.path("/assets/site.txt"),
                    HttpResponse.BodyHandlers.ofString())
                .body());
        Files.writeString(file, "second");
        assertEquals(
            "second",
            test.send(
                    request -> request.path("/assets/site.txt"),
                    HttpResponse.BodyHandlers.ofString())
                .body());
      }
    }
  }

  @Test
  void servesTheOriginalMountAfterAGateReplacesItsPathWithAnExternalSymlink() throws Exception {
    assumeSecureDirectoryOperations();
    var publicDirectory = Files.createDirectory(temporaryDirectory.resolve("public"));
    var relocatedDirectory = temporaryDirectory.resolve("relocated");
    var externalDirectory = Files.createDirectory(temporaryDirectory.resolve("external"));
    Files.writeString(publicDirectory.resolve("site.txt"), "mounted content");
    Files.writeString(externalDirectory.resolve("site.txt"), "external content");

    try (var app = new Shoostr()) {
      app.beforeRouteHandler(
          (ignored14, ignored15) -> {
            Files.move(publicDirectory, relocatedDirectory);
            Files.createSymbolicLink(publicDirectory, externalDirectory);
          });
      app.routes().staticFiles("/assets", publicDirectory);

      try (var test = TestServer.start(app)) {

        var result =
            test.send(
                request -> request.path("/assets/site.txt"), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, result.statusCode());
        assertEquals("mounted content", result.body());
      }
    }
  }

  @Test
  @Timeout(20)
  void servesConcurrentShallowAndNestedRequestsFromTheAnchoredMountAfterItsPathIsReplaced()
      throws Exception {
    assumeSecureDirectoryOperations();
    var mounted = Files.createDirectories(temporaryDirectory.resolve("mounted"));
    var outside = Files.createDirectories(temporaryDirectory.resolve("outside"));
    for (var base : List.of(mounted, outside)) {
      Files.createDirectories(base.resolve("nested/deeper"));
      for (int index = 0; index < 4; index++) {
        for (var prefix : List.of("", "nested/deeper/")) {
          Files.writeString(
              base.resolve(prefix + "file-" + index + ".txt"),
              (base.equals(mounted) ? "mounted:" : "outside:") + prefix + index);
        }
      }
    }

    try (var app = new Shoostr()) {
      app.routes().staticFiles("/assets", mounted);
      Files.move(mounted, temporaryDirectory.resolve("relocated"));
      Files.createSymbolicLink(mounted, outside);

      try (var test = TestServer.start(app);
          var clients = Executors.newVirtualThreadPerTaskExecutor()) {
        var ready = new CountDownLatch(8);
        var start = new CountDownLatch(1);
        var tasks = new ArrayList<Future<?>>();
        for (int worker = 0; worker < 8; worker++) {
          final int id = worker;
          tasks.add(
              clients.submit(
                  () -> {
                    ready.countDown();
                    assertTrue(start.await(3, TimeUnit.SECONDS));
                    for (int turn = 0; turn < 16; turn++) {
                      var prefix = (turn & 1) == 0 ? "" : "nested/deeper/";
                      int index = (id + turn) % 4;
                      var response =
                          test.send(
                              request ->
                                  request
                                      .path("/assets/" + prefix + "file-" + index + ".txt")
                                      .timeout(Duration.ofSeconds(3)),
                              HttpResponse.BodyHandlers.ofString());
                      assertEquals(200, response.statusCode());
                      assertEquals("mounted:" + prefix + index, response.body());
                    }
                    return null;
                  }));
        }

        try {
          assertTrue(ready.await(3, TimeUnit.SECONDS));
        } finally {
          start.countDown();
        }

        for (var task : tasks) task.get(10, TimeUnit.SECONDS);
      }
    }
  }

  @ParameterizedTest
  @CsvSource({"directory,500", "leaf,404", "missing,404"})
  void rejectsNestedSymlinkSubstitutionAndMissingParentsAfterRouteSelection(
      String change, int expectedStatus) throws Exception {
    assumeSecureDirectoryOperations();
    var mounted = Files.createDirectories(temporaryDirectory.resolve("mounted/nested/deeper"));
    var outside = Files.createDirectories(temporaryDirectory.resolve("outside/deeper"));
    Files.writeString(mounted.resolve("file.txt"), "mounted content");
    Files.writeString(outside.resolve("file.txt"), "outside secret");
    var root = temporaryDirectory.resolve("mounted");

    try (var app = new Shoostr()) {
      app.beforeRouteHandler(
          (request, response) -> {
            if ("directory".equals(change)) {
              Files.move(root.resolve("nested"), temporaryDirectory.resolve("relocated"));
              Files.createSymbolicLink(
                  root.resolve("nested"), temporaryDirectory.resolve("outside"));
            } else {
              Files.delete(mounted.resolve("file.txt"));
              if ("leaf".equals(change)) {
                Files.createSymbolicLink(mounted.resolve("file.txt"), outside.resolve("file.txt"));
              } else {
                Files.delete(mounted);
              }
            }
          });
      app.routes().staticFiles("/assets", root);

      try (var server = TestServer.start(app)) {
        var result =
            server.send(
                request -> request.path("/assets/nested/deeper/file.txt"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(expectedStatus, result.statusCode());
        assertFalse(result.body().contains("outside secret"));
      }
    }
  }

  @Test
  @Timeout(15)
  void completesShutdownWhileConcurrentShallowAndNestedRequestsAreAdmitted() throws Exception {
    assumeSecureDirectoryOperations();
    Files.createDirectories(temporaryDirectory.resolve("nested/deeper"));
    Files.writeString(temporaryDirectory.resolve("file.txt"), "mounted content");
    Files.writeString(temporaryDirectory.resolve("nested/deeper/file.txt"), "mounted content");
    var entered = new CountDownLatch(2);
    var release = new CountDownLatch(1);

    try (var app = new Shoostr();
        var client = HttpClient.newHttpClient()) {
      app.modifyServer(
              nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0))
          .beforeClose(release::countDown)
          .beforeRouteHandler(
              (request, response) -> {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) {
                  throw new IOException("Request gate timed out");
                }
              });
      app.routes().staticFiles("/assets", temporaryDirectory);
      app.start();
      var origin = "http://127.0.0.1:" + app.port();
      var pending =
          List.of("/assets/file.txt", "/assets/nested/deeper/file.txt").stream()
              .map(
                  path ->
                      client.sendAsync(
                          HttpRequest.newBuilder(URI.create(origin + path))
                              .timeout(Duration.ofSeconds(5))
                              .build(),
                          HttpResponse.BodyHandlers.ofString()))
              .toList();

      try {
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        app.close();
      } finally {
        release.countDown();
      }

      assertThrows(IllegalStateException.class, app::port);
      for (var exchange : pending) {
        try {
          var response = exchange.get(3, TimeUnit.SECONDS);
          if (response.statusCode() == 200) {
            assertEquals("mounted content", response.body());
          } else {
            assertTrue(response.statusCode() >= 400 && response.statusCode() < 600);
          }
        } catch (ExecutionException failure) {
          assertInstanceOf(IOException.class, failure.getCause());
        }
      }
    }
  }

  @Test
  void doesNotFollowFilesystemSymlinksOutsideTheMountedDirectory() throws Exception {
    assumeSecureDirectoryOperations();
    var publicDirectory = Files.createDirectory(temporaryDirectory.resolve("public"));
    var secret = temporaryDirectory.resolve("secret.txt");
    Files.writeString(secret, "top-secret-content");
    Files.createSymbolicLink(publicDirectory.resolve("link.txt"), secret);

    try (var app = new Shoostr()) {
      app.routes().staticFiles("/assets", publicDirectory);

      try (var test = TestServer.start(app)) {

        var result =
            test.send(
                request -> request.path("/assets/link.txt"), HttpResponse.BodyHandlers.ofString());

        assertEquals(404, result.statusCode());
        assertFalse(result.body().contains("top-secret-content"));
      }
    }
  }

  @Test
  void rejectsUnsafeDownloadFilenames() throws Exception {
    var file = temporaryDirectory.resolve("report.txt");
    Files.writeString(file, "download");

    try (var app = new Shoostr()) {
      String[] filenames = {"", "line\nbreak.txt", "folder/report.txt", "folder\\report.txt"};
      for (int index = 0; index < filenames.length; index++) {
        var filename = filenames[index];
        app.routes()
            .get(
                "/download-" + index,
                (ignored16, response) -> response.attachment(file, "text/plain", filename));
      }

      try (var test = TestServer.start(app)) {

        for (int index = 0; index < filenames.length; index++) {
          var path = "/download-" + index;
          assertEquals(
              500,
              test.send(request -> request.path(path), HttpResponse.BodyHandlers.ofString())
                  .statusCode());
        }
      }
    }
  }

  @Test
  void encodesDownloadFilenamesForModernAndLegacyClients() throws Exception {
    var file = temporaryDirectory.resolve("report.txt");
    Files.writeString(file, "download");

    try (var app = new Shoostr()) {
      app.routes()
          .get(
              "/download",
              (ignored17, response) -> response.attachment(file, "text/plain", "résumé 2026.txt"));

      try (var test = TestServer.start(app)) {

        var result =
            test.send(request -> request.path("/download"), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, result.statusCode());
        assertEquals("download", result.body());
        assertEquals(
            "attachment; filename=\"r_sum_ 2026.txt\"; filename*=UTF-8''r%C3%A9sum%C3%A9%202026.txt",
            result.headers().firstValue("Content-Disposition").orElseThrow());
      }
    }
  }

  private static void registerComposedStaticSources(
      Routes routes, Path filesystem, boolean filesystemFirst) {
    if (filesystemFirst) {
      routes.staticFiles("/assets", filesystem).classpathResources("/assets", "/issue76-composed");
    } else {
      routes.classpathResources("/assets", "/issue76-composed").staticFiles("/assets", filesystem);
    }
  }

  private void assumeSecureDirectoryOperations() throws IOException {
    try (var stream = Files.newDirectoryStream(temporaryDirectory)) {
      assumeTrue(
          stream instanceof SecureDirectoryStream<?>,
          "Filesystem static mounts require SecureDirectoryStream support");
    }
  }

  private static HttpClient staticTransportClient(Shoostr app, boolean tls, boolean http2)
      throws Exception {
    var client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .version(http2 ? HttpClient.Version.HTTP_2 : HttpClient.Version.HTTP_1_1);
    if (http2) {
      app.http2();
    }

    if (tls) {
      var store = KeyStore.getInstance("PKCS12");

      try (var input =
          Objects.requireNonNull(
              StaticResourcesTest.class.getResourceAsStream("/localhost-test.p12"))) {
        store.load(input, "changeit".toCharArray());
      }

      app.tls(
          context -> {
            context.setKeyStore(store);
            context.setKeyStorePassword("changeit");
          });
      var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      trust.init(store);
      var context = SSLContext.getInstance("TLS");
      context.init(null, trust.getTrustManagers(), null);
      client.sslContext(context);
    }

    return client.build();
  }

  private static FileSystem registeredArchiveFilesystem(Routes routes)
      throws ReflectiveOperationException {
    Field field = Routes.class.getDeclaredField("staticFiles");
    field.setAccessible(true);
    var staticFiles = (List<?>) field.get(routes);
    var files = (StaticFiles) staticFiles.getFirst();
    return Objects.requireNonNull(files.archiveFileSystem());
  }

  private static void writeArchive(Path archive) throws Exception {
    try (var output = new JarOutputStream(Files.newOutputStream(archive))) {
      output.putNextEntry(new JarEntry("archive/"));
      output.closeEntry();
      output.putNextEntry(new JarEntry("archive/resource.txt"));
      output.write("archived resource".getBytes(StandardCharsets.UTF_8));
      output.closeEntry();
    }
  }
}
