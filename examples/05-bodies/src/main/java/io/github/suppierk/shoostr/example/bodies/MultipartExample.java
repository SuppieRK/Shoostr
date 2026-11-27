package io.github.suppierk.shoostr.example.bodies;

import io.github.suppierk.shoostr.MultipartOptions;
import io.github.suppierk.shoostr.Options;
import io.github.suppierk.shoostr.Shoostr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Reads multipart fields and bounded file content within a request.
 */
public final class MultipartExample {
    /**
     * Creates an unstarted application with explicit multipart limits
     * and temporary storage.
     *
     * @param temporaryDirectory directory for file parts that exceed
     *                           the memory threshold
     * @return application owned by its caller
     * @throws IOException if the directory cannot be created
     */
    public static Shoostr application(Path temporaryDirectory) throws IOException {
        var storage = Files.createDirectories(
            Objects.requireNonNull(temporaryDirectory)
                .toAbsolutePath()
                .normalize()
        );

        // Keep large parts in this temporary directory
        // instead of retaining every byte in memory.
        var configuredOptions = options(storage);

        return new Shoostr(configuredOptions).routes(routes -> routes
            .post("/documents", (request, response) -> {
                var title = request.formParam("title").orElse("(missing)");
                var first = request.file("document");
                // Repeated file parts keep their order;
                // file() above selects only the first.
                var documents = request.files("document");
                var fileFields = request.files();

                long bytes = 0;
                var hex = new StringBuilder();
                for (var document : documents) {
                    bytes += document.size();

                    // Inspect content before the request ends and
                    // its temporary parts expire.
                    try (var content = document.content()) {
                        hex.append(HexFormat.of()
                            .formatHex(content.readAllBytes()));
                    }
                }

                response.text(String.join(
                    "\n",
                    "title=" + title,
                    "first=" + (first.isPresent()
                        ? first.orElseThrow().fileName()
                        : "(missing)"),
                    "files=" + documents.size(),
                    "fields=" + fileFields.size(),
                    "bytes=" + bytes,
                    "hex=" + hex
                ));
            }));
    }

    /**
     * Configures the listener and resource policy used by this application.
     *
     * @param storage application setting
     * @return detached serving options
     */
    private static Options options(Path storage) {
        var multipart = new MultipartOptions();
        // Lower the per-file limit first so the smaller aggregate limit remains valid.
        multipart.setMaxFileBytes(1024L);
        multipart.setMaxBytes(8192L);
        multipart.setMaxParts(8);
        multipart.setMaxPartHeadersBytes(1024);
        multipart.setMaxMemoryPartBytes(128L);
        multipart.setTemporaryDirectory(storage);

        var configuredOptions = new Options();
        configuredOptions.setMultipart(multipart);
        configuredOptions.setPort(0);
        return configuredOptions;
    }
}
