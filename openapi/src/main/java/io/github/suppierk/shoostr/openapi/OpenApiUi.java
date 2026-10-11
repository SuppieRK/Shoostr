package io.github.suppierk.shoostr.openapi;

import io.github.suppierk.shoostr.Handler;
import io.github.suppierk.shoostr.Request;
import io.github.suppierk.shoostr.Response;
import io.swagger.v3.core.util.Json31;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Exact startup-prepared UI URL allowlist, not a classpath/filesystem server or route registrar.
 */
final class OpenApiUi implements Handler {
  private static final String HTML =
      """
      <!doctype html>
      <html lang="en">
      <head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
      <title>API documentation</title><link rel="stylesheet" href="swagger-ui.css"></head>
      <body><div id="swagger-ui"></div><script src="swagger-ui-bundle.js"></script>
      <script src="swagger-initializer.js"></script></body></html>
      """;
  private final String basePath;
  private final String specPath;
  private volatile @Nullable Map<String, Resource> resources;

  /**
   * Validates literal local resource paths without preparing assets during registration.
   *
   * @param basePath UI page path ending in slash
   * @param specPath specification path
   * @throws IllegalArgumentException if the page path lacks its trailing slash
   */
  OpenApiUi(String basePath, String specPath) {
    this.basePath = path(basePath);
    this.specPath = path(specPath);
    if (!basePath.endsWith("/")) {
      throw new IllegalArgumentException("UI page path must end in a slash");
    }

    resources = null;
  }

  /**
   * Freezes four serving resources, sharing only immutable upstream bytes between UI instances.
   *
   * @param css packaged stylesheet
   * @param bundle packaged browser bundle
   * @throws IOException if configuration cannot be serialized
   */
  void prepare(byte[] css, byte[] bundle) throws IOException {
    var config =
        Json31.mapper()
            .createObjectNode()
            .put("url", specPath)
            .put("dom_id", "#swagger-ui")
            .putNull("validatorUrl")
            .put("persistAuthorization", false);
    var initializer =
        "window.addEventListener('load', () => { window.ui = SwaggerUIBundle(Object.assign("
            + Json31.mapper().writeValueAsString(config)
            + """
            , {requestInterceptor: request => {
              if (!request.loadSpec) {
                request.credentials = 'omit';
              }
              return request;
            }})); });
            """;
    resources =
        Map.of(
            basePath,
            new Resource("text/html; charset=utf-8", HTML.getBytes(StandardCharsets.UTF_8)),
            basePath + "swagger-ui.css",
            new Resource("text/css; charset=utf-8", css),
            basePath + "swagger-ui-bundle.js",
            new Resource("text/javascript; charset=utf-8", bundle),
            basePath + "swagger-initializer.js",
            new Resource(
                "text/javascript; charset=utf-8", initializer.getBytes(StandardCharsets.UTF_8)));
  }

  /**
   * Serves only exact configured resource URLs, with bounded streaming for large bundled assets.
   * Path-parameter names have no effect on dispatch; unknown URLs return 404.
   *
   * @param request current matched request
   * @param response current response
   * @throws IOException if transport output fails
   * @throws IllegalStateException if startup has not prepared the UI
   */
  @Override
  public void handle(Request request, Response response) throws IOException {
    var prepared = resources;
    if (prepared == null) {
      throw new IllegalStateException("OpenAPI UI is not prepared");
    }

    var resource = prepared.get(request.path());
    if (resource == null) {
      response.status(404);
      return;
    }

    response.startStream(resource.contentType).write(resource.bytes);
  }

  /**
   * Loads a fixed private packaged asset once during startup; names never come from requests.
   *
   * @param name known bundled asset name
   * @return owned asset bytes
   * @throws IOException if packaging omitted the asset or reading fails
   */
  static byte[] asset(String name) throws IOException {
    try (var source = OpenApiUi.class.getResourceAsStream("ui/" + name)) {
      if (source == null) {
        throw new IOException("Missing packaged Swagger UI asset: " + name);
      }

      return source.readAllBytes();
    }
  }

  /**
   * Validates a literal same-origin path without queries, fragments, templates or dot segments.
   *
   * @param value configured path
   * @return unchanged validated path
   * @throws IllegalArgumentException if the value is not a literal absolute local path
   */
  private static String path(String value) {
    var uri = URI.create(OpenApi.text(value));
    if (!value.startsWith("/")
        || value.startsWith("//")
        || uri.isAbsolute()
        || uri.getRawAuthority() != null
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null
        || !uri.normalize().equals(uri)
        || value.contains("{")
        || value.contains("%")
        || value.contains("//")) {
      throw new IllegalArgumentException("UI resources require absolute literal same-origin paths");
    }

    return value;
  }

  /** Private immutable resource metadata and owned bytes, never exposed to callers. */
  private static final class Resource {
    private final String contentType;
    private final byte[] bytes;

    /**
     * Validates the complete resource metadata before publication.
     *
     * @param contentType response media type
     * @param bytes owned body bytes
     */
    private Resource(String contentType, byte[] bytes) {
      this.contentType = Objects.requireNonNull(contentType);
      this.bytes = Objects.requireNonNull(bytes);
    }
  }
}
