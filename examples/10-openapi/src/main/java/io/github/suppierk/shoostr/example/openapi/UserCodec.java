package io.github.suppierk.shoostr.example.openapi;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.github.suppierk.shoostr.Codec;
import io.github.suppierk.shoostr.http.exceptions.BadRequestException;
import io.swagger.v3.core.util.Json31;
import java.io.IOException;

/** Explicit tree-based JSON conversion with no application-model reflection or annotations. */
final class UserCodec implements Codec {
  /** Creates the stateless example codec. */
  UserCodec() {}

  /**
   * Decodes the single explicit example type and classifies only malformed input as 400.
   *
   * @param source JSON bytes
   * @param type explicit payload class
   * @param <T> result type
   * @return validated payload
   * @throws IOException if the JSON reader fails unexpectedly
   * @throws BadRequestException if input JSON or the required name is malformed
   * @throws IllegalArgumentException if this example does not support the requested type
   */
  @Override
  public <T> T read(byte[] source, Class<T> type) throws IOException {
    // Json31 supplies Swagger's Jackson mapper; User is this example's record, not a schema type.
    if (type != User.class) {
      throw new IllegalArgumentException("Example codec supports only User");
    }

    try {
      var tree = Json31.mapper().readTree(source);
      if (tree == null || !tree.path("name").isTextual()) {
        throw new BadRequestException("User name must be a string");
      }

      return type.cast(new User(tree.path("name").asText()));
    } catch (JsonProcessingException | IllegalArgumentException failure) {
      throw new BadRequestException("Malformed user", failure);
    }
  }

  /**
   * Serializes the explicit payload through a JSON tree; no codec/schema inference occurs.
   *
   * @param value user payload
   * @return JSON bytes
   * @throws IOException if serialization fails
   * @throws IllegalArgumentException if the value is unsupported
   */
  @Override
  public byte[] write(Object value) throws IOException {
    if (!(value instanceof User(var name))) {
      throw new IllegalArgumentException("Example codec supports only User");
    }

    return Json31.mapper().writeValueAsBytes(Json31.mapper().createObjectNode().put("name", name));
  }
}
