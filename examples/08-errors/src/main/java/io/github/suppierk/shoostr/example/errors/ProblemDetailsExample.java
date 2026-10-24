package io.github.suppierk.shoostr.example.errors;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.ProblemDetails;
import io.github.suppierk.shoostr.http.exceptions.NotFoundException;
import java.net.URI;

/** Encodes application-owned problem data as an explicit error response. */
public final class ProblemDetailsExample {
  /** Prevents construction of this example factory. */
  private ProblemDetailsExample() {}

  /**
   * Creates an unstarted application.
   *
   * @return application owned by its caller
   */
  public static Shoostr application() {
    var mapper = new ObjectMapper();
    return new Shoostr()
        .exception(
            NotFoundException.class,
            (ignored, ignored2, response) -> {
              // Publish application-selected detail, not the exception's private diagnostics.
              var problem =
                  ProblemDetails.builder()
                      .type(URI.create("about:blank"))
                      .title("Not Found")
                      .status(404)
                      .detail("Item \"42\" was not found.")
                      .instance(URI.create("/items/42"))
                      .extension("itemId", 42)
                      .build();
              // The payload's status field does not set the HTTP status or media type.
              response
                  .status(404)
                  .body("application/problem+json", mapper.writeValueAsBytes(problem.toMap()));
            })
        .routes(
            routes ->
                routes.get(
                    "/items/42",
                    (ignored3, ignored4) -> {
                      throw new NotFoundException("Private storage diagnostics for item 42");
                    }));
  }
}
