package io.github.suppierk.shoostr.example.authentication;

import io.github.suppierk.shoostr.Cors;
import io.github.suppierk.shoostr.CorsPolicy;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.http.HttpMethods;

/** Explicit cross-origin sharing with whole-policy replacement, independent of authentication. */
public final class CorsExample {
  /** Shares the response header name with the CORS policy. */
  private static final HttpHeaders REQUEST_ID = HttpHeaders.of("X-Request-Id");

  /** Prevents utility-class construction. */
  private CorsExample() {}

  /**
   * Creates an application with an exact-origin credentialed CORS policy.
   *
   * @return unstarted, caller-owned application
   */
  public static Shoostr application() {
    // Credentialed sharing names an exact origin; CORS does not authenticate that origin's users.
    var policy =
        CorsPolicy.builder()
            .origins("https://app.example")
            .methods(HttpMethods.GET, HttpMethods.POST)
            .allowedHeaders(HttpHeaders.CONTENT_TYPE, HttpHeaders.AUTHORIZATION)
            .credentials(true)
            .exposedHeaders(REQUEST_ID)
            .maxAgeSeconds(600)
            .build();
    var cors = new Cors(policy);
    var reportHeader = HttpHeaders.of("X-Report-Format");
    var reports =
        CorsPolicy.builder()
            .origins("https://reports.example")
            .allowedHeaders(reportHeader)
            .credentials(false)
            .maxAgeSeconds(0)
            .build();
    return new Shoostr()
        .extensions(cors)
        .configureExtensionsDefaults(extensions -> extensions.with(cors))
        .routes(
            routes ->
                routes
                    .get(
                        "/messages",
                        (request, response) ->
                            response.setHeader(REQUEST_ID.value(), "demo").text("hello"))
                    .post(
                        "/messages",
                        (request, response) ->
                            response
                                .setHeader(REQUEST_ID.value(), "demo")
                                .status(201)
                                .text("accepted"))
                    // Exclusion removes automatic sharing and Origin rejection, not HTTP access.
                    .get(
                        "/health",
                        (request, response) -> response.text("ready"),
                        extensions -> extensions.without(cors))
                    .path(
                        "/reports",
                        reportRoutes ->
                            reportRoutes.get(
                                "/daily",
                                (request, response) ->
                                    response
                                        .setHeader(REQUEST_ID.value(), "report")
                                        .text(
                                            request.header(reportHeader.value()).orElse("plain"))),
                        // Replace the complete policy; parent origins and headers do not carry
                        // forward.
                        extensions -> extensions.with(cors).policy(reports)));
  }
}
