package io.github.suppierk.shoostr.example.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.suppierk.shoostr.Codec;
import io.github.suppierk.shoostr.Shoostr;
import io.github.suppierk.shoostr.http.MediaType;
import io.github.suppierk.shoostr.http.exceptions.BadRequestException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Converts an explicit user representation to and from JSON. */
public final class JsonCodecExample {
  /** Prevents construction of this example factory. */
  private JsonCodecExample() {}

  /**
   * Creates an unstarted application.
   *
   * @return application owned by its caller
   */
  public static Shoostr application() {
    var app = new Shoostr();
    // This registration view supplies typed helpers without changing ordinary app.routes().
    app.codec(new UserCodec())
        .routes(
            routes -> {
              routes
                  .exception(
                      BadRequestException.class,
                      (ignored, ignored2, response) ->
                          response
                              .status(400)
                              // Error bytes bypass the codec, which only supports the User class.
                              .body(
                                  MediaType.APPLICATION_JSON,
                                  "{\"error\":\"invalid_user\"}".getBytes(StandardCharsets.UTF_8)))
                  // The same codec decodes the request into User and encodes the returned User.
                  .post(
                      "/users",
                      (request, response) ->
                          response
                              .status(201)
                              .body(MediaType.APPLICATION_JSON, request.body(User.class)))
                  .get(
                      "/help",
                      (ignored3, response) ->
                          response.text("Send a JSON object with a nonblank name"));
            });
    return app;
  }

  /**
   * A user whose name is present and nonblank.
   *
   * @param name user name
   */
  public record User(String name) {
    /**
     * Validates the name before publishing the user.
     *
     * @param name user name
     * @throws NullPointerException if the name is null
     * @throws IllegalArgumentException if the name is blank
     */
    public User {
      // These example invariants also apply to users constructed without JSON input.
      Objects.requireNonNull(name);
      if (name.isBlank()) {
        throw new IllegalArgumentException("A user name is required");
      }
    }
  }

  /** Converts only this example's user model with explicit JSON tree operations. */
  private static final class UserCodec implements Codec {
    private final ObjectMapper mapper;

    /** Configures the mapper before concurrent use. */
    private UserCodec() {
      // Configure the shared mapper once before concurrent handlers can use it.
      mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /**
     * Reads one user from a JSON object.
     *
     * @param source finite JSON bytes
     * @param type supported user class
     * @param <T> requested result type
     * @return validated user
     * @throws IOException if an unexpected reading failure occurs
     * @throws IllegalArgumentException if the class is unsupported
     * @throws BadRequestException if the JSON or user input is invalid
     */
    @Override
    public <T> T read(byte[] source, Class<T> type) throws IOException {
      if (type != User.class) {
        throw new IllegalArgumentException("This codec supports only User");
      }

      JsonNode tree;

      // Classify recognized input errors as 400; unexpected I/O failures remain server failures.

      try {
        tree = mapper.readTree(source);
      } catch (JsonProcessingException failure) {
        throw new BadRequestException("Malformed JSON", failure);
      }

      // Check the JSON shape before constructing the domain value.
      if (tree == null || !tree.isObject() || !tree.path("name").isTextual()) {
        throw new BadRequestException("A string name is required");
      }

      try {
        return type.cast(new User(tree.path("name").asText()));
      } catch (IllegalArgumentException failure) {
        throw new BadRequestException("Invalid user name", failure);
      }
    }

    /**
     * Writes one user's JSON representation.
     *
     * @param value validated user
     * @return encoded JSON bytes
     * @throws IOException if encoding fails
     * @throws IllegalArgumentException if the value is unsupported
     */
    @Override
    public byte[] write(Object value) throws IOException {
      if (!(value instanceof User(var name))) {
        throw new IllegalArgumentException("This codec supports only User");
      }

      // Explicit tree conversion needs no mapping annotations on the model.
      return mapper.writeValueAsBytes(mapper.createObjectNode().put("name", name));
    }
  }
}
