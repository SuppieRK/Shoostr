package io.github.suppierk.shoostr.example.bodies;

/** Starts the standalone bodies recipe. */
public final class ExampleApplication {
  /** Prevents construction of the launcher. */
  private ExampleApplication() {}

  /**
   * Starts the application; Shoostr owns its JVM shutdown hook.
   *
   * @param args unused command-line arguments
   * @throws Exception if configuration or startup fails
   */
  public static void main(String[] args) throws Exception {
    BufferedBodyExample.application().start();
  }
}
