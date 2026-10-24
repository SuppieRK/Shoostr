package io.github.suppierk.shoostr.example.extensions;

import io.github.suppierk.shoostr.ApplicationCallbacks;
import io.github.suppierk.shoostr.Extension;
import io.github.suppierk.shoostr.Extensions;
import io.github.suppierk.shoostr.Shoostr;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Generates a route catalog from registration facts, not synthetic requests. */
public final class RouteCatalogExample {
  /** Prevents construction of the factory. */
  private RouteCatalogExample() {}

  /**
   * Creates an application with a catalog and an optionally available preview route.
   *
   * @param previewAvailable request-time preview availability
   * @return unstarted application
   */
  public static Shoostr application(BooleanSupplier previewAvailable) {
    Objects.requireNonNull(previewAvailable);
    var catalog = new RouteCatalog();
    return new Shoostr()
        // Install this instance before selecting its configuration on routes or groups.
        .extensions(catalog)
        .configureExtensionsDefaults(
            extensions -> extensions.with(catalog).summary("List route descriptions"))
        .routes(
            routes -> {
              // Exclusion removes catalog facts, while its installed application hook still runs.
              routes
                  .get(
                      "/health",
                      (ignored, response) -> response.text("ok"),
                      extensions -> extensions.without(catalog))
                  .get("/catalog", (ignored2, response) -> response.text(catalog.document()))
                  // Selected children inherit a summary and can override their own copy.
                  .path(
                      "/api",
                      api ->
                          api.get(
                                  "/items/{id}",
                                  (request, response) ->
                                      response.text(request.pathParam("id").orElseThrow()))
                              .get(
                                  "/status",
                                  (ignored3, response) -> response.text("ready"),
                                  extensions ->
                                      extensions.with(catalog).summary("Check readiness")),
                      extensions -> extensions.with(catalog).summary("Read an item"))
                  // Registration records this route even while its runtime condition is false.
                  .when(
                      previewAvailable,
                      preview ->
                          preview.get(
                              "/preview",
                              (ignored4, response) -> response.text("preview"),
                              extensions -> extensions.with(catalog).summary("Preview content")));
            });
  }

  /** Compiles selected route descriptions into a snapshot before accepting traffic. */
  public static final class RouteCatalog implements Extension<Description> {
    private final AtomicBoolean claimed;
    private final List<String> routes;
    private volatile String document;

    /** Creates an empty catalog. */
    public RouteCatalog() {
      claimed = new AtomicBoolean();
      routes = new ArrayList<>();
      document = "";
    }

    /** {@inheritDoc} */
    @Override
    public void claimOwnership() {
      // This catalog has application-specific state and cannot belong to two applications.
      if (!claimed.compareAndSet(false, true)) {
        throw new IllegalStateException("The route catalog already has an owner");
      }
    }

    /** {@inheritDoc} */
    @Override
    public void install(ApplicationCallbacks callbacks) {
      callbacks.onRequestHeaders(
          (ignored5, response) -> response.setHeader("X-Route-Catalog", "/catalog"));
    }

    /** {@inheritDoc} */
    @Override
    public Description configure(Extensions endpoint, Description inherited) {
      // Copy inherited settings instead of sharing a mutable object with parents or siblings.
      var description = new Description(inherited == null ? "Route" : inherited.summary);
      // Read final registration facts after this scope's configuration has been applied.
      endpoint.onRoute(
          info ->
              routes.add(
                  info.method()
                      + " "
                      + info.pathTemplate()
                      + " — "
                      + description.summary
                      + (info.conditional() ? " [conditional]" : "")));
      return description;
    }

    /** {@inheritDoc} */
    @Override
    public void beforeStart() {
      // Publish one completed snapshot before request handlers read it concurrently.
      document = String.join("\n", routes.stream().sorted().toList());
    }

    /** {@inheritDoc} */
    @Override
    public void close() {
      routes.clear();
      document = "";
    }

    /**
     * Returns the compiled snapshot, independent of requests and later settings changes.
     *
     * @return selected route descriptions
     */
    public String document() {
      return document;
    }
  }

  /** Independent description settings copied for each selected route or group. */
  public static final class Description {
    private String summary;

    /**
     * Creates validated settings for one scope.
     *
     * @param summary nonblank route description
     * @throws IllegalArgumentException if the description is blank
     */
    private Description(String summary) {
      this.summary = Objects.requireNonNull(summary);
      if (summary.isBlank()) {
        throw new IllegalArgumentException("A route description cannot be blank");
      }
    }

    /**
     * Replaces this scope's description without changing its parent or siblings.
     *
     * @param value nonblank route description
     * @return this configuration
     * @throws IllegalArgumentException if the description is blank
     */
    public Description summary(String value) {
      Objects.requireNonNull(value);
      if (value.isBlank()) {
        throw new IllegalArgumentException("A route description cannot be blank");
      }

      summary = value;
      return this;
    }
  }
}
