package io.github.suppierk.shoostr.example.errors;

/** Starts the standalone errors recipe. */
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
    ExceptionHandlingExample.application().start();
  }
}
