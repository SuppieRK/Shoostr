package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.http.HttpMethods;
import io.github.suppierk.shoostr.http.exceptions.UnauthorizedException;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.ServerSocket;
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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StaticResourcesTest {
  @TempDir Path temporaryDirectory;

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

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes().staticFiles("/assets", temporaryDirectory);
      app.start();

      var result = send(client, app, "/assets/site.txt");

      assertEquals(200, result.statusCode());
      assertEquals("filesystem resource", result.body());
      assertEquals("text/plain", result.headers().firstValue("Content-Type").orElseThrow());
      assertEquals("no-cache", result.headers().firstValue("Cache-Control").orElseThrow());
    }
  }

  @Test
  void servesConfiguredWelcomeFilesAtTheMountAndNestedDirectories() throws Exception {
    assumeSecureDirectoryOperations();
    Files.writeString(temporaryDirectory.resolve("index.html"), "<h1>root</h1>");
    var nested = Files.createDirectory(temporaryDirectory.resolve("nested"));
    Files.writeString(nested.resolve("index.html"), "<h1>nested</h1>");

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .staticFiles(
              "/assets",
              temporaryDirectory,
              StaticOptions.defaults().withWelcomeFile("index.html"));
      app.start();

      for (String path : List.of("/assets", "/assets/")) {
        var result = send(client, app, path);
        assertEquals(200, result.statusCode());
        assertEquals("<h1>root</h1>", result.body());
        assertEquals("text/html", result.headers().firstValue("Content-Type").orElseThrow());
      }

      for (String path : List.of("/assets/nested", "/assets/nested/")) {
        assertEquals("<h1>nested</h1>", send(client, app, path).body());
      }

      assertEquals(404, send(client, app, "/assets/missing").statusCode());
    }
  }

  @Test
  void usesConfiguredSpaFallbackOnlyForMissingResourcesInsideTheMount() throws Exception {
    assumeSecureDirectoryOperations();
    Files.writeString(temporaryDirectory.resolve("index.html"), "<h1>app</h1>");
    Files.writeString(temporaryDirectory.resolve("app.js"), "console.log('app');");

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .staticFiles(
              "/assets",
              temporaryDirectory,
              StaticOptions.defaults().withSpaFallback("index.html"));
      app.routes().get("/assets/health", (request, response) -> response.text("endpoint"));
      app.start();

      assertEquals("<h1>app</h1>", send(client, app, "/assets/orders/42").body());
      assertEquals("<h1>app</h1>", send(client, app, "/assets/missing.js").body());
      assertEquals(
          "text/html",
          send(client, app, "/assets/missing.js")
              .headers()
              .firstValue("Content-Type")
              .orElseThrow());
      assertEquals("console.log('app');", send(client, app, "/assets/app.js").body());
      assertEquals("endpoint", send(client, app, "/assets/health").body());
      assertEquals(404, send(client, app, "/outside").statusCode());
    }
  }

  @Test
  void servesConfiguredClasspathWelcomeFileWithoutEnablingFallback() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .classpathResources(
              "/assets", "/", StaticOptions.defaults().withWelcomeFile("static-resource.txt"));
      app.start();

      assertEquals("classpath resource", send(client, app, "/assets").body().trim());
      assertEquals(404, send(client, app, "/assets/missing").statusCode());
    }
  }

  @Test
  void servesConfiguredClasspathSpaFallbackWithoutEnablingWelcomeFile() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .classpathResources(
              "/assets", "/", StaticOptions.defaults().withSpaFallback("static-resource.txt"));
      app.start();

      assertEquals("classpath resource", send(client, app, "/assets").body().trim());
      assertEquals("classpath resource", send(client, app, "/assets/missing").body().trim());
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

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.beforeRouteHandler(
          (request, response) -> {
            if ("/assets/blocked".equals(request.path())) {
              throw new UnauthorizedException();
            }
          });
      app.routes()
          .staticFiles(
              "/assets",
              temporaryDirectory,
              StaticOptions.defaults().withSpaFallback("index.html"));
      app.start();

      assertEquals(401, send(client, app, "/assets/blocked").statusCode());
      var head =
          client.send(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + app.port() + "/assets/missing"))
                  .method("HEAD", HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, head.statusCode());
      assertEquals("", head.body());
      assertEquals("12", head.headers().firstValue("Content-Length").orElseThrow());
    }
  }

  @Test
  void corsPreflightSkipsScopedAdmissionButActualSpaFallbackRequiresIt() throws Exception {
    assumeSecureDirectoryOperations();
    Files.writeString(temporaryDirectory.resolve("index.html"), "<h1>app</h1>");
    var admissions = new AtomicInteger();

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.cors(
          new CorsPolicy(
              Set.of("https://client.example"),
              Set.of(HttpMethods.GET),
              Set.of(HttpHeaders.AUTHORIZATION),
              true,
              Set.of(),
              5));
      app.routes()
          .protect(
              (request, response) -> {
                admissions.incrementAndGet();
                if (!"Bearer allowed".equals(request.header("Authorization"))) {
                  throw new UnauthorizedException();
                }
              },
              routes ->
                  routes.staticFiles(
                      "/assets",
                      temporaryDirectory,
                      StaticOptions.defaults().withSpaFallback("index.html")));
      app.start();

      var preflight =
          client.send(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + app.port() + "/assets/missing"))
                  .header("Origin", "https://client.example")
                  .header("Access-Control-Request-Method", "GET")
                  .header("Access-Control-Request-Headers", "Authorization")
                  .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(204, preflight.statusCode());
      assertEquals(0, admissions.get());
      assertEquals(
          "authorization",
          preflight.headers().firstValue("Access-Control-Allow-Headers").orElseThrow());

      var denied =
          client.send(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + app.port() + "/assets/missing"))
                  .header("Origin", "https://client.example")
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(401, denied.statusCode());
      assertEquals(1, admissions.get());
      assertEquals(
          "https://client.example",
          denied.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());

      var allowed =
          client.send(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + app.port() + "/assets/missing"))
                  .header("Origin", "https://client.example")
                  .header("Authorization", "Bearer allowed")
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, allowed.statusCode());
      assertEquals("<h1>app</h1>", allowed.body());
      assertEquals(2, admissions.get());
      assertEquals(
          "https://client.example",
          allowed.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
    }
  }

  @Test
  void doesNotSwitchToSpaFallbackWhenSelectedFileDisappearsAfterAdmission() throws Exception {
    assumeSecureDirectoryOperations();
    var selected = temporaryDirectory.resolve("app.js");
    Files.writeString(selected, "selected");
    Files.writeString(temporaryDirectory.resolve("index.html"), "fallback");

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.beforeRouteHandler((request, response) -> Files.delete(selected));
      app.routes()
          .staticFiles(
              "/assets",
              temporaryDirectory,
              StaticOptions.defaults().withSpaFallback("index.html"));
      app.start();

      var result = send(client, app, "/assets/app.js");
      assertEquals(404, result.statusCode());
      assertFalse(result.body().contains("fallback"));
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

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .staticFiles(
              "/assets", publicDirectory, StaticOptions.defaults().withSpaFallback("index.html"));
      app.start();

      var traversal = send(client, app, "/assets/%2e%2e/secret.txt");
      assertEquals(400, traversal.statusCode());
      assertFalse(traversal.body().contains("top-secret-content"));
      assertEquals("fallback", send(client, app, "/assets/link.txt").body());
    }
  }

  @Test
  void redirectAfterMountedFileDiscardsTheSelectedResource() throws Exception {
    assumeSecureDirectoryOperations();
    Files.writeString(temporaryDirectory.resolve("site.txt"), "filesystem resource");

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes().staticFiles("/assets", temporaryDirectory);
      app.afterRouteHandler((request, response) -> response.redirect("/next"));
      app.start();

      var result = send(client, app, "/assets/site.txt", "Range", "bytes=0-3");

      assertEquals(302, result.statusCode());
      assertEquals("/next", result.headers().firstValue("Location").orElseThrow());
      assertEquals("", result.body());
      assertEquals(List.of(), result.headers().allValues("Content-Range"));
      assertEquals(List.of(), result.headers().allValues("ETag"));
      assertEquals(List.of(), result.headers().allValues("Accept-Ranges"));
      assertEquals(List.of(), result.headers().allValues("Content-Type"));
    }
  }

  @Test
  void servesClasspathFilesBelowTheMountedPath() throws Exception {
    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes().classpathResources("/assets", "/");
      app.start();

      var result = send(client, app, "/assets/static-resource.txt");

      assertEquals(200, result.statusCode());
      assertEquals("classpath resource", result.body().trim());
      assertEquals("text/plain", result.headers().firstValue("Content-Type").orElseThrow());
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
        var app = new Shoostr(Options.defaults().withPort(occupied.getLocalPort()))) {
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
        var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      Thread.currentThread().setContextClassLoader(loader);
      var routes = app.routes();
      routes.classpathResources("/assets", "/archive");
      var filesystem = registeredArchiveFilesystem(routes);
      assertTrue(filesystem.isOpen());
      app.start();

      var result = send(client, app, "/assets/resource.txt");

      assertEquals(200, result.statusCode());
      assertEquals("archived resource", result.body());
      app.close();
      assertFalse(filesystem.isOpen());
    } finally {
      Thread.currentThread().setContextClassLoader(previous);
    }
  }

  @Test
  void supportsHeadRangesAndValidatorsForMountedFiles() throws Exception {
    assumeSecureDirectoryOperations();
    var file = temporaryDirectory.resolve("site.txt");
    Files.writeString(file, "filesystem resource");

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes().staticFiles("/assets", temporaryDirectory);
      app.start();

      var full = send(client, app, "/assets/site.txt");
      var range = send(client, app, "/assets/site.txt", "Range", "bytes=5-8");
      var conditional =
          send(
              client,
              app,
              "/assets/site.txt",
              "If-None-Match",
              full.headers().firstValue("ETag").orElseThrow());
      var head =
          client.send(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + app.port() + "/assets/site.txt"))
                  .method("HEAD", HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(206, range.statusCode());
      assertEquals("yste", range.body());
      assertEquals(304, conditional.statusCode());
      assertEquals(200, head.statusCode());
      assertEquals("19", head.headers().firstValue("Content-Length").orElseThrow());
    }
  }

  @Test
  void givesExplicitEndpointsPrecedenceOverMountedFiles() throws Exception {
    assumeSecureDirectoryOperations();
    Files.writeString(temporaryDirectory.resolve("site.txt"), "filesystem resource");

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes().staticFiles("/assets", temporaryDirectory);
      app.routes().get("/assets/site.txt", (request, response) -> response.text("endpoint"));
      app.start();

      var result = send(client, app, "/assets/site.txt");

      assertEquals(200, result.statusCode());
      assertEquals("endpoint", result.body());
    }
  }

  @Test
  void composesFilesystemMountsInsidePathGroups() throws Exception {
    assumeSecureDirectoryOperations();
    Files.writeString(temporaryDirectory.resolve("site.txt"), "composed resource");

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes().path("/api", routes -> routes.staticFiles("assets", temporaryDirectory));
      app.start();

      var result = send(client, app, "/api/assets/site.txt");

      assertEquals(200, result.statusCode());
      assertEquals("composed resource", result.body());
    }
  }

  @Test
  void doesNotExposeAnEncodedTraversalOutsideTheMountedDirectory() throws Exception {
    assumeSecureDirectoryOperations();
    var publicDirectory = Files.createDirectory(temporaryDirectory.resolve("public"));
    Files.writeString(temporaryDirectory.resolve("secret.txt"), "top-secret-content");

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes().staticFiles("/assets", publicDirectory);
      app.start();

      var result = send(client, app, "/assets/%2e%2e/secret.txt");

      assertEquals(400, result.statusCode());
      assertFalse(result.body().contains("top-secret-content"));
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

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes().staticFiles("/assets", temporaryDirectory);
      app.start();

      var missing = send(client, app, "/assets/missing.txt");
      var directory = send(client, app, "/assets/directory");
      var pipe =
          client.send(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + app.port() + "/assets/named-pipe"))
                  .timeout(Duration.ofSeconds(2))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(404, missing.statusCode());
      assertEquals(404, directory.statusCode());
      assertEquals(404, pipe.statusCode());
    }
  }

  @Test
  void appliesMatchedRouteGatesToMountedFiles() throws Exception {
    assumeSecureDirectoryOperations();
    Files.writeString(temporaryDirectory.resolve("site.txt"), "filesystem resource");

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.beforeRouteHandler(
          (request, response) -> {
            throw new UnauthorizedException();
          });
      app.routes().staticFiles("/assets", temporaryDirectory);
      app.start();

      var result = send(client, app, "/assets/site.txt");

      assertEquals(401, result.statusCode());
    }
  }

  @Test
  void reportsMountedFileRequestsToTerminalObservers() throws Exception {
    assumeSecureDirectoryOperations();
    Files.writeString(temporaryDirectory.resolve("site.txt"), "filesystem resource");
    var outcome = new AtomicReference<RequestOutcome>();
    var completed = new CountDownLatch(1);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.afterRequest(
          value -> {
            outcome.set(value);
            completed.countDown();
          });
      app.routes().staticFiles("/assets", temporaryDirectory);
      app.start();

      assertEquals(200, send(client, app, "/assets/site.txt").statusCode());
      assertTrue(completed.await(5, TimeUnit.SECONDS));
      assertEquals(200, outcome.get().statusCode());
      assertEquals("/assets", outcome.get().routePattern());
    }
  }

  @Test
  void resolvesTheCurrentFilesystemFileForEachRequest() throws Exception {
    assumeSecureDirectoryOperations();
    var file = temporaryDirectory.resolve("site.txt");
    Files.writeString(file, "first");

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes().staticFiles("/assets", temporaryDirectory);
      app.start();

      assertEquals("first", send(client, app, "/assets/site.txt").body());
      Files.writeString(file, "second");
      assertEquals("second", send(client, app, "/assets/site.txt").body());
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

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.beforeRouteHandler(
          (request, response) -> {
            Files.move(publicDirectory, relocatedDirectory);
            Files.createSymbolicLink(publicDirectory, externalDirectory);
          });
      app.routes().staticFiles("/assets", publicDirectory);
      app.start();

      var result = send(client, app, "/assets/site.txt");

      assertEquals(200, result.statusCode());
      assertEquals("mounted content", result.body());
    }
  }

  @Test
  void doesNotFollowFilesystemSymlinksOutsideTheMountedDirectory() throws Exception {
    assumeSecureDirectoryOperations();
    var publicDirectory = Files.createDirectory(temporaryDirectory.resolve("public"));
    var secret = temporaryDirectory.resolve("secret.txt");
    Files.writeString(secret, "top-secret-content");
    Files.createSymbolicLink(publicDirectory.resolve("link.txt"), secret);

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes().staticFiles("/assets", publicDirectory);
      app.start();

      var result = send(client, app, "/assets/link.txt");

      assertEquals(404, result.statusCode());
      assertFalse(result.body().contains("top-secret-content"));
    }
  }

  @Test
  void rejectsUnsafeDownloadFilenames() throws Exception {
    var file = temporaryDirectory.resolve("report.txt");
    Files.writeString(file, "download");

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      String[] filenames = {"", "line\nbreak.txt", "folder/report.txt", "folder\\report.txt"};
      for (int index = 0; index < filenames.length; index++) {
        var filename = filenames[index];
        app.routes()
            .get(
                "/download-" + index,
                (request, response) -> response.attachment(file, "text/plain", filename));
      }
      app.start();

      for (int index = 0; index < filenames.length; index++) {
        assertEquals(500, send(client, app, "/download-" + index).statusCode());
      }
    }
  }

  @Test
  void encodesDownloadFilenamesForModernAndLegacyClients() throws Exception {
    var file = temporaryDirectory.resolve("report.txt");
    Files.writeString(file, "download");

    try (var app = new Shoostr(Options.defaults().withPort(0));
        var client = HttpClient.newHttpClient()) {
      app.routes()
          .get(
              "/download",
              (request, response) -> response.attachment(file, "text/plain", "résumé 2026.txt"));
      app.start();

      var result = send(client, app, "/download");

      assertEquals(200, result.statusCode());
      assertEquals("download", result.body());
      assertEquals(
          "attachment; filename=\"r_sum_ 2026.txt\"; filename*=UTF-8''r%C3%A9sum%C3%A9%202026.txt",
          result.headers().firstValue("Content-Disposition").orElseThrow());
    }
  }

  private void assumeSecureDirectoryOperations() throws IOException {
    try (var stream = Files.newDirectoryStream(temporaryDirectory)) {
      assumeTrue(
          stream instanceof SecureDirectoryStream<?>,
          "Filesystem static mounts require SecureDirectoryStream support");
    }
  }

  private static HttpResponse<String> send(HttpClient client, Shoostr app, String path)
      throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static FileSystem registeredArchiveFilesystem(Routes routes)
      throws ReflectiveOperationException {
    Field field = Routes.class.getDeclaredField("staticFiles");
    field.setAccessible(true);
    var staticFiles = (List<?>) field.get(routes);
    var files = (StaticFiles) staticFiles.getFirst();
    return Objects.requireNonNull(files.archiveFileSystem());
  }

  private static HttpResponse<String> send(
      HttpClient client, Shoostr app, String path, String header, String value) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path));
    if (header != null) {
      request.header(header, value);
    }

    return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
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
