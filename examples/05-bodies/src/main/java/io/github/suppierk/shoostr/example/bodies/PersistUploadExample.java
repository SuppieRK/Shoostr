package io.github.suppierk.shoostr.example.bodies;

import io.github.suppierk.shoostr.MultipartOptions;
import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.exceptions.BadRequestException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.eclipse.jetty.server.ServerConnector;

/** Transfers uploaded content to application-owned storage. */
public final class PersistUploadExample {
  /** Prevents construction of this example factory. */
  private PersistUploadExample() {}

  /**
   * Creates an unstarted application that saves uploads under generated filenames.
   *
   * @param directory directory for temporary uploads and retained files
   * @return application owned by its caller; saved files remain owned by the application
   * @throws IOException if the directory cannot be created
   */
  public static Shoostr application(Path directory) throws IOException {
    var storage =
        Files.createDirectories(Objects.requireNonNull(directory).toAbsolutePath().normalize());
    var multipart = MultipartOptions.defaults().withTemporaryDirectory(storage);
    return new Shoostr(Options.defaults().withMultipart(multipart))
        .modifyServer(
            nativeServer -> ((ServerConnector) nativeServer.getConnectors()[0]).setPort(0))
        .routes(
            routes ->
                routes.post(
                    "/documents",
                    (request, response) -> {
                      var upload =
                          request
                              .file("document")
                              .orElseThrow(() -> new BadRequestException("Missing document"));
                      // Generate the storage name; an uploaded filename is not a trusted path.
                      var destination = Files.createTempFile(storage, "upload-", ".bin");

                      try {
                        // Retained content becomes application-owned beyond this request's cleanup.
                        upload.persistTo(destination);
                      } catch (IOException | RuntimeException failure) {
                        try {
                          // Remove the reserved destination without hiding the original failure.
                          Files.deleteIfExists(destination);
                        } catch (IOException cleanup) {
                          failure.addSuppressed(cleanup);
                        }

                        throw failure;
                      }

                      response.status(201).text(destination.getFileName().toString());
                    }));
  }
}
