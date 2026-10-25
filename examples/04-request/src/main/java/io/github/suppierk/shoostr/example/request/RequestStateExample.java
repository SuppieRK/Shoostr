package io.github.suppierk.shoostr.example.request;

import io.github.suppierk.shoostr.Shoostr;
import java.util.Map;
import java.util.UUID;

/** Shares per-request values without retaining the live request. */
public final class RequestStateExample {
  private static final String LOCALE_ATTRIBUTE = "locale";

  /** Prevents construction of this example factory. */
  private RequestStateExample() {}

  /**
   * Creates an unstarted application whose hook supplies each greeting's context.
   *
   * @return application owned by its caller
   */
  public static Shoostr application() {
    return new Shoostr()
        // Populate context for this request rather than storing it in shared application state.
        .beforeRouteHandler(
            (request, ignored) ->
                request
                    .attribute("request.id", UUID.randomUUID().toString())
                    .attribute(LOCALE_ATTRIBUTE, request.queryParam(LOCALE_ATTRIBUTE).orElse("en")))
        .routes(
            routes ->
                routes.get(
                    "/greeting",
                    (request, response) ->
                        response
                            .setHeader(
                                "X-Request-Id",
                                request.attribute("request.id").orElseThrow().toString())
                            // Pass a snapshot of the bindings, not the live request, to the helper.
                            .text(greeting(request.attributeMap()))));
  }

  /**
   * Selects a greeting from captured attribute bindings.
   *
   * @param context snapshot of the request attributes
   * @return localized greeting
   */
  private static String greeting(Map<String, Object> context) {
    return "fr".equals(context.get(LOCALE_ATTRIBUTE)) ? "Bonjour" : "Hello";
  }
}
