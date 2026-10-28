package io.github.suppierk.shoostr.example.lifecycle;

/** Starts the standalone lifecycle recipe. */
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
    ShutdownExample.application(
            () ->
                System.getLogger(ExampleApplication.class.getName())
                    .log(System.Logger.Level.INFO, "Application resources closed"))
        .start();
  }
}
