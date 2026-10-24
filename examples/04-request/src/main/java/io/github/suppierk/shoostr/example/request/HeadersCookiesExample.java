package io.github.suppierk.shoostr.example.request;

import io.github.suppierk.shoostr.Shoostr;
import java.util.List;

/** Reads repeated headers and cookies without changing their representation. */
public final class HeadersCookiesExample {
  /** Prevents construction of this example factory. */
  private HeadersCookiesExample() {}

  /**
   * Creates an unstarted application that inspects incoming preferences.
   *
   * @return application owned by its caller
   */
  public static Shoostr application() {
    return new Shoostr()
        .routes(
            routes ->
                routes.get(
                    "/preferences",
                    (request, response) ->
                        response.text(
                            String.join(
                                "\n",
                                // Header lookup ignores case and preserves repeated field values.
                                "header=" + request.header("x-label").orElse("(missing)"),
                                "headers=" + String.join("|", request.headers("X-LABEL")),
                                "headerMap="
                                    + String.join(
                                        "|",
                                        request.headerMap().getOrDefault("x-LaBeL", List.of())),
                                // Cookie names stay case-sensitive; duplicates are not collapsed.
                                "cookie=" + request.cookie("theme").orElse("(missing)"),
                                "cookies=" + String.join("|", request.cookies("theme")),
                                "cookieMap="
                                    + String.join(
                                        "|", request.cookieMap().getOrDefault("theme", List.of())),
                                "otherCookie=" + request.cookie("Theme").orElse("(missing)")))));
  }
}
