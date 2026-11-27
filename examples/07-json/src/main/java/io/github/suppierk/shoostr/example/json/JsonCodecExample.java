package io.github.suppierk.shoostr.example.json;

import com.fasterxml.jackson.core.exc.StreamReadException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import com.fasterxml.jackson.databind.type.LogicalType;
import io.github.suppierk.shoostr.Codec;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.MediaType;
import io.github.suppierk.shoostr.http.exceptions.BadRequestException;

import java.io.IOException;
import java.util.Map;

/**
 * Converts Java values to and from JSON through Jackson.
 */
public final class JsonCodecExample {
    /**
     * Creates an unstarted application.
     *
     * @return application owned by its caller
     */
    public static Shoostr application() {
        return new Shoostr().codec(new JsonCodec()).routes(routes -> routes
            .exception(
                BadRequestException.class,
                (failure, request, response) -> response
                    .body(
                        MediaType.APPLICATION_JSON,
                        Map.of("error", "invalid_user")
                    ))
            .post("/users", (request, response) -> response
                .status(201)
                .body(MediaType.APPLICATION_JSON, request.body(User.class)))
            .get("/help", (request, response) -> response
                .text("Send a JSON object with a nonblank name")));
    }

    /**
     * A user whose name is present and nonblank.
     *
     * @param name username
     */
    public record User(String name) {
        /**
         * Validates the name before publishing the user.
         *
         * @param name username
         * @throws IllegalArgumentException if the name is missing or blank
         */
        public User {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Username is required");
            }
        }
    }

    /**
     * Converts Jackson-supported classes without annotations on the models.
     */
    private static final class JsonCodec implements Codec {
        private final ObjectMapper mapper;

        /**
         * Sets the JSON input rules before the mapper is shared.
         */
        private JsonCodec() {
            this.mapper = new ObjectMapper()
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

            // Require a JSON string for a String property,
            // including the user's name.
            mapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public <T> T read(byte[] source, Class<T> type) throws IOException {
            try {
                var value = mapper.readValue(source, type);

                if (value == null) {
                    throw new BadRequestException("JSON value is required");
                }

                return value;
            } catch (StreamReadException | MismatchedInputException failure) {
                throw new BadRequestException("Invalid JSON input", failure);
            } catch (ValueInstantiationException failure) {
                if (failure.getCause() instanceof IllegalArgumentException) {
                    throw new BadRequestException("Invalid JSON value", failure);
                }

                throw failure;
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public byte[] write(Object value) throws IOException {
            return mapper.writeValueAsBytes(value);
        }
    }
}
