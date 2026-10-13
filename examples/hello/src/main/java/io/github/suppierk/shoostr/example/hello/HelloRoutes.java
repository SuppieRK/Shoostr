package io.github.suppierk.shoostr.example.hello;

import io.github.suppierk.shoostr.Routes;

/** Defines the hello response independently of listener startup. */
final class HelloRoutes {
  /** Prevents construction of the registration function. */
  private HelloRoutes() {}

  /**
   * Registers one text endpoint without acquiring resources or starting the app.
   *
   * @param routes application registration scope
   */
  static void register(Routes routes) {
    routes.get("/hello", (_, response) -> response.text("Hello, Shoostr!"));
  }
}
