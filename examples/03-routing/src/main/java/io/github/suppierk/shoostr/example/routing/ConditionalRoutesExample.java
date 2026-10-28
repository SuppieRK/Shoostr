package io.github.suppierk.shoostr.example.routing;

import io.github.suppierk.shoostr.Shoostr;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Evaluates live and request-dependent route availability. */
public final class ConditionalRoutesExample {
  /** Prevents construction of this example factory. */
  private ConditionalRoutesExample() {}

  /**
   * Creates an unstarted application with nested availability conditions.
   *
   * @param enabled concurrent-safe live preview flag
   * @return application owned by its caller
   * @throws NullPointerException if the flag is null
   */
  public static Shoostr application(BooleanSupplier enabled) {
    Objects.requireNonNull(enabled);

    return new Shoostr()
        .routes(
            routes ->
                routes
                    .get("/preview/{*tail}", (ignored, response) -> response.text("fallback"))
                    // A false condition hides the selected literal with 404; it does not retry the
                    // tail.
                    .when(
                        enabled,
                        live ->
                            live.when(
                                // The live flag is checked before this query-dependent condition.
                                request ->
                                    request
                                        .queryParam("preview")
                                        .filter("true"::equals)
                                        .isPresent(),
                                available ->
                                    available.get(
                                        "/preview/latest",
                                        (ignored2, response) -> response.text("preview")))));
  }
}
