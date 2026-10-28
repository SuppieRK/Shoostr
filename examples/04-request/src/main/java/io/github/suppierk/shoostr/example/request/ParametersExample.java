package io.github.suppierk.shoostr.example.request;

import io.github.suppierk.shoostr.Shoostr;
import java.util.List;

/** Reads decoded parameters without merging their sources. */
public final class ParametersExample {
  private static final String CATEGORY_PARAMETER = "category";

  /** Prevents construction of this example factory. */
  private ParametersExample() {}

  /**
   * Creates an unstarted application that reads path, query, and URL-encoded form parameters.
   *
   * @return application owned by its caller
   */
  public static Shoostr application() {
    return new Shoostr()
        .routes(
            routes ->
                routes
                    .get(
                        "/search/{category}",
                        (request, response) -> {
                          // Read repeated query values without merging them with path parameters.
                          var parameters = request.queryParamMap();
                          response.text(
                              String.join(
                                  "\n",
                                  "category=" + request.pathParam(CATEGORY_PARAMETER).orElseThrow(),
                                  "path=" + request.pathParamMap().get(CATEGORY_PARAMETER),
                                  "first=" + request.queryParam("tag").orElse("(missing)"),
                                  "tags=" + String.join("|", request.queryParams("tag")),
                                  "sort="
                                      + parameters
                                          .getOrDefault("sort", List.of("recent"))
                                          .getFirst()));
                        })
                    .post(
                        "/search/{category}",
                        (request, response) -> {
                          // Form fields and the same-named query parameters remain separate
                          // sources.
                          var fields = request.formParamMap();
                          response.text(
                              String.join(
                                  "\n",
                                  "category=" + request.pathParam(CATEGORY_PARAMETER).orElseThrow(),
                                  "first=" + request.formParam("tag").orElse("(missing)"),
                                  "tags=" + String.join("|", request.formParams("tag")),
                                  "sort="
                                      + fields.getOrDefault("sort", List.of("recent")).getFirst(),
                                  "query=" + String.join("|", request.queryParams("tag"))));
                        }));
  }
}
