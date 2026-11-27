package io.github.suppierk.shoostr.example.errors;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.suppierk.shoostr.Codec;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.ProblemDetails;
import io.github.suppierk.shoostr.http.exceptions.NotFoundException;

import java.io.IOException;
import java.net.URI;
import java.util.Objects;

/**
 * Encodes application-owned problem data as an explicit error response.
 */
public final class ProblemDetailsExample {
    /**
     * Creates an unstarted application.
     *
     * @return application owned by its caller
     */
    public static Shoostr application() {
        return new Shoostr()
            .codec(new JacksonCodec())
            .routes(routes -> routes
                .exception(
                    NotFoundException.class,
                    (exception, request, response) -> {
                        // Supply public detail independently of the exception's message.
                        var problem = missingItemProblem();

                        response
                            .status(404)
                            .body("application/problem+json", problem.toMap());
                    })
                .get("/items/42", (request, response) -> {
                    throw new NotFoundException("Private storage diagnostics for item 42");
                }));
    }

    /**
     * Describes the missing item without exposing private storage diagnostics.
     *
     * @return public problem details for item 42
     */
    private static ProblemDetails missingItemProblem() {
        return ProblemDetails.builder()
            .type(URI.create("about:blank"))
            .title("Not Found")
            .status(404)
            .detail("Item \"42\" was not found.")
            .instance(URI.create("/items/42"))
            .extension("itemId", 42)
            .build();
    }

    /** Converts JSON using Jackson for the typed handlers. */
    private static final class JacksonCodec implements Codec {
        private final ObjectMapper mapper;

        /** Creates the mapper before the application starts. */
        private JacksonCodec() {
            this.mapper = new ObjectMapper();
        }

        /** {@inheritDoc} */
        @Override
        public <T> T read(byte[] source, Class<T> type) throws IOException {
            return Objects.requireNonNull(
                mapper.readValue(source, type),
                "Expected a JSON value"
            );
        }

        /** {@inheritDoc} */
        @Override
        public byte[] write(Object value) throws IOException {
            return mapper.writeValueAsBytes(value);
        }
    }
}
