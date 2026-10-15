package io.github.suppierk.shoostr.example.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.suppierk.shoostr.Codec;
import io.github.suppierk.shoostr.http.exceptions.BadRequestException;
import java.io.IOException;

/** Example-local implementation of Shoostr's core Codec interface, using Jackson JSON trees. */
final class UserCodec implements Codec {
  private final ObjectMapper mapper;

  /** Creates the mapper once, before any concurrent request can use it. */
  UserCodec() {
    // Jackson's ObjectMapper does the JSON conversion; Shoostr does not choose a JSON library.
    mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  /**
   * Decodes only the supported payload and classifies malformed user input as400.
   *
   * @param source bounded JSON bytes
   * @param type concrete payload type
   * @param <T> payload type
   * @return validated user
   * @throws IOException if an unexpected I/O failure occurs
   * @throws BadRequestException if JSON/name is malformed
   * @throws IllegalArgumentException if the requested type is unsupported
   */
  @Override
  public <T> T read(byte[] source, Class<T> type) throws IOException {
    // User is this example's record, not a framework model or an automatically scanned type.
    if (type != User.class) {
      throw new IllegalArgumentException("This codec supports only User");
    }

    try {
      var tree = mapper.readTree(source);
      if (tree == null || !tree.isObject() || !tree.path("name").isTextual()) {
        throw new BadRequestException("A string name is required");
      }

      return type.cast(new User(tree.path("name").asText()));
    } catch (JsonProcessingException | IllegalArgumentException failure) {
      throw new BadRequestException("Malformed user", failure);
    }
  }

  /**
   * Writes one explicit user representation without changing encoding failures into400.
   *
   * @param value validated user
   * @return finite JSON bytes
   * @throws IOException if encoding fails
   * @throws IllegalArgumentException if the value is unsupported
   */
  @Override
  public byte[] write(Object value) throws IOException {
    if (!(value instanceof User(var name))) {
      throw new IllegalArgumentException("This codec supports only User");
    }

    return mapper.writeValueAsBytes(mapper.createObjectNode().put("name", name));
  }
}
