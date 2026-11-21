package io.github.suppierk.shoostr.openapi;

import io.github.suppierk.shoostr.http.MediaType;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Independent route/group documentation defaults selected through an installed OpenApi instance.
 */
public final class OpenApiOperation {
  private final OpenApi owner;
  private final Operation operation;
  private boolean hidden;

  /**
   * Creates independent defaults without sharing mutable model data with the parent.
   *
   * @param owner installed document
   * @param inherited parent operation, or null
   */
  OpenApiOperation(OpenApi owner, @Nullable OpenApiOperation inherited) {
    this.owner = owner;
    this.operation = inherited == null ? new Operation() : inherited.snapshot();
    this.hidden = inherited != null && inherited.hidden;
  }

  /**
   * Sets or overrides this operation's summary.
   *
   * @param value nonblank summary
   * @return this configuration
   */
  public OpenApiOperation summary(String value) {
    return mutate(() -> operation.setSummary(OpenApi.text(value)));
  }

  /**
   * Sets or overrides this operation's description.
   *
   * @param value nonblank description
   * @return this configuration
   */
  public OpenApiOperation description(String value) {
    return mutate(() -> operation.setDescription(OpenApi.text(value)));
  }

  /**
   * Sets an explicit operation identifier, checked for uniqueness within this document at startup.
   *
   * @param value nonblank operation identifier
   * @return this configuration
   */
  public OpenApiOperation operationId(String value) {
    return mutate(() -> operation.setOperationId(OpenApi.text(value)));
  }

  /**
   * Adds a tag without duplicating an inherited tag.
   *
   * @param value nonblank tag name
   * @return this configuration
   */
  public OpenApiOperation tag(String value) {
    return mutate(() -> {
      final var tag = OpenApi.text(value);
      final var tags = operation.getTags();
      if (tags == null || !tags.contains(tag)) {
        operation.addTagsItem(tag);
      }
    });
  }

  /**
   * Declares a required string path parameter; route constraints are attached at startup. For an
   * explicit alternative schema use parameter(Parameter).
   *
   * @param name exact template parameter name
   * @param description nonblank description
   * @return this configuration
   */
  public OpenApiOperation pathParameter(String name, String description) {
    return parameter(
        new Parameter()
            .name(OpenApi.text(name))
            .in("path")
            .required(true)
            .schema(new JsonSchema().types(Set.of("string")))
            .description(OpenApi.text(description)));
  }

  /**
   * Declares or overrides a query parameter using an explicit schema.
   *
   * @param name parameter name
   * @param description nonblank description
   * @param schema explicit schema
   * @param required whether this parameter is required
   * @return this configuration
   */
  public OpenApiOperation queryParameter(
      String name, String description, Schema<?> schema, boolean required) {
    return parameterAt("query", name, description, schema, required);
  }

  /**
   * Declares or overrides a header parameter using an explicit schema.
   *
   * @param name parameter name
   * @param description nonblank description
   * @param schema explicit schema
   * @param required whether this parameter is required
   * @return this configuration
   */
  public OpenApiOperation headerParameter(
      String name, String description, Schema<?> schema, boolean required) {
    return parameterAt("header", name, description, schema, required);
  }

  /**
   * Builds a query or header parameter before registering the completed model.
   *
   * @param location parameter location
   * @param name parameter name
   * @param description nonblank description
   * @param schema explicit schema
   * @param required whether this parameter is required
   * @return this configuration
   */
  private OpenApiOperation parameterAt(
      String location, String name, String description, Schema<?> schema, boolean required) {
    return parameter(
        new Parameter()
            .name(OpenApi.text(name))
            .in(location)
            .description(OpenApi.text(description))
            .schema(Objects.requireNonNull(schema))
            .required(required));
  }

  /**
   * Declares explicit request bytes independently of codec configuration.
   *
   * @param description nonblank body description
   * @param mediaType body representation
   * @param schema explicit schema or reusable reference
   * @param required whether the body is required
   * @return this configuration
   */
  public OpenApiOperation requestBody(
      String description, MediaType mediaType, Schema<?> schema, boolean required) {
    return requestBody(description, Objects.requireNonNull(mediaType).value(), schema, required);
  }

  /**
   * Declares explicit request bytes independently of codec configuration.
   *
   * @param description nonblank body description
   * @param mediaType body representation
   * @param schema explicit schema or reusable reference
   * @param required whether the body is required
   * @return this configuration
   */
  public OpenApiOperation requestBody(
      String description, String mediaType, Schema<?> schema, boolean required) {
    return requestBody(
        new RequestBody()
            .description(OpenApi.text(description))
            .required(required)
            .content(contentWithSchema(mediaType, schema)));
  }

  /**
   * Replaces the entire inherited request body with a detached explicit library model. Media
   * declarations may omit schemas; no handler or codec is inspected to fabricate one.
   *
   * @param body explicit body declaration
   * @return this configuration
   * @throws IllegalArgumentException if a non-reference body has no media declarations
   */
  public OpenApiOperation requestBody(RequestBody body) {
    return mutate(() -> {
      Objects.requireNonNull(body);
      if (body.get$ref() == null && (body.getContent() == null || body.getContent().isEmpty())) {
        throw new IllegalArgumentException("OpenAPI request bodies require content");
      }

      operation.setRequestBody(OpenApi.copy(body, RequestBody.class));
    });
  }

  /**
   * Replaces a parameter by name/location, preserving unrelated inherited parameters.
   *
   * @param value explicit path, query or header parameter
   * @return this configuration
   * @throws IllegalArgumentException if name/location is absent or unsupported
   */
  public OpenApiOperation parameter(Parameter value) {
    return mutate(() -> {
      final var parameter = OpenApi.copy(value, Parameter.class);
      if (parameter.getName() == null
          || parameter.getName().isBlank()
          || !Set.of("path", "query", "header").contains(parameter.getIn())) {
        throw new IllegalArgumentException(
            "Explicit parameters need a name and path/query/header location");
      }

      if ((parameter.getSchema() == null) == (parameter.getContent() == null)
          || (parameter.getContent() != null && parameter.getContent().size() != 1)) {
        throw new IllegalArgumentException(
            "OpenAPI parameters require either a schema or one content entry");
      }

      final var parameters = operation.getParameters();
      if (parameters != null) {
        parameters.removeIf(existing ->
            parameter.getName().equals(existing.getName())
                && parameter.getIn().equals(existing.getIn()));
      }

      operation.addParametersItem(parameter);
    });
  }

  /**
   * Replaces inherited documentation security with one required named scheme and explicit scopes.
   * This never changes the route's actual authentication.
   *
   * @param name registered security scheme name
   * @param scopes required OAuth/OpenID scopes, or none/null for schemes without scopes
   * @return this configuration
   */
  public OpenApiOperation security(String name, String @Nullable ... scopes) {
    final var requirement = new SecurityRequirement();
    final var values = new ArrayList<String>();
    if (scopes != null) {
      for (final var scope : scopes) {
        values.add(OpenApi.text(scope));
      }
    }

    requirement.put(OpenApi.componentName(name), values);

    return security(requirement);
  }

  /**
   * Replaces inherited security with one explicit requirement. Multiple schemes in this object are
   * conjunctive, as defined by OpenAPI; use anonymous() for no documented requirement.
   *
   * @param requirement native security requirement
   * @return this configuration
   */
  public OpenApiOperation security(SecurityRequirement requirement) {
    return mutate(() ->
        operation.setSecurity(
            new ArrayList<>(List.of(OpenApi.copy(requirement, SecurityRequirement.class)))));
  }

  /**
   * Clears inherited documentation security without disabling actual route authentication.
   *
   * @return this configuration
   */
  public OpenApiOperation anonymous() {
    return mutate(() -> operation.setSecurity(new ArrayList<>()));
  }

  /**
   * Replaces the complete response entry for one status; unrelated inherited responses remain.
   *
   * @param status HTTP status from 100 to 599
   * @param description nonblank response description
   * @return this configuration
   * @throws IllegalArgumentException if the status is invalid
   */
  public OpenApiOperation response(int status, String description) {
    return response(status, new ApiResponse().description(OpenApi.text(description)));
  }

  /**
   * Declares a response representation without inferring its schema.
   *
   * @param status HTTP status from 100 to 599
   * @param description nonblank response description
   * @param mediaType explicit response media type
   * @return this configuration
   */
  public OpenApiOperation response(int status, String description, MediaType mediaType) {
    return response(status, description, Objects.requireNonNull(mediaType).value());
  }

  /**
   * Declares a response representation without inferring its schema.
   *
   * @param status HTTP status from 100 to 599
   * @param description nonblank response description
   * @param mediaType explicit response media type
   * @return this configuration
   */
  public OpenApiOperation response(int status, String description, String mediaType) {
    return response(
        status,
        new ApiResponse()
            .description(OpenApi.text(description))
            .content(
                new Content()
                    .addMediaType(
                        MediaType.of(mediaType).value(),
                        new io.swagger.v3.oas.models.media.MediaType())));
  }

  /**
   * Declares response bytes with an explicit schema, independently of codec presence.
   *
   * @param status HTTP status from 100 to 599
   * @param description nonblank response description
   * @param mediaType response representation
   * @param schema explicit schema or reusable reference
   * @return this configuration
   */
  public OpenApiOperation response(
      int status, String description, MediaType mediaType, Schema<?> schema) {
    return response(status, description, Objects.requireNonNull(mediaType).value(), schema);
  }

  /**
   * Declares response bytes with an explicit schema, independently of codec presence.
   *
   * @param status HTTP status from 100 to 599
   * @param description nonblank response description
   * @param mediaType response representation
   * @param schema explicit schema or reusable reference
   * @return this configuration
   */
  public OpenApiOperation response(
      int status, String description, String mediaType, Schema<?> schema) {
    return response(
        status,
        new ApiResponse()
            .description(OpenApi.text(description))
            .content(contentWithSchema(mediaType, schema)));
  }

  /**
   * Replaces one explicit native response, copying it so sibling scopes remain independent.
   *
   * @param status HTTP status from 100 to 599
   * @param value explicit response with a description or local response reference
   * @return this configuration
   * @throws IllegalArgumentException if the status is invalid
   */
  public OpenApiOperation response(int status, ApiResponse value) {
    return mutate(() -> {
      if (status < 100 || status > 599) {
        throw new IllegalArgumentException("Invalid documented HTTP status");
      }

      final var response = OpenApi.copy(value, ApiResponse.class);
      if (response.get$ref() == null && response.getDescription() == null) {
        throw new IllegalArgumentException("OpenAPI responses require a description");
      }

      if (operation.getResponses() == null) {
        operation.setResponses(new ApiResponses());
      }

      operation.getResponses().addApiResponse(Integer.toString(status), response);
    });
  }

  /**
   * Builds one content entry with an explicit schema for a request body or response.
   *
   * @param mediaType representation media type
   * @param schema explicit schema or reusable reference
   * @return content containing the declared representation
   */
  private static Content contentWithSchema(String mediaType, Schema<?> schema) {
    return new Content()
        .addMediaType(
            MediaType.of(mediaType).value(),
            new io.swagger.v3.oas.models.media.MediaType().schema(Objects.requireNonNull(schema)));
  }

  /**
   * Excludes this scope and its descendants, including inherited documentation defaults.
   *
   * @return this configuration
   */
  public OpenApiOperation hidden() {
    return mutate(() -> hidden = true);
  }

  /**
   * Reports the documentation opt-out during cold collection.
   *
   * @return whether this scope is excluded
   */
  boolean isHidden() {
    return hidden;
  }

  /**
   * Copies the currently supported operation fields for inheritance or final binding.
   *
   * @return detached operation
   */
  Operation snapshot() {
    return OpenApi.copy(operation, Operation.class);
  }

  /**
   * Checks the owner's freeze state and applies a change under its configuration lock.
   *
   * @param action guarded validation and change
   * @return this configuration
   */
  private OpenApiOperation mutate(Runnable action) {
    final var lock = owner.configurationLock();
    lock.lock();

    try {
      owner.requireMutable();
      action.run();
      return this;
    } finally {
      lock.unlock();
    }
  }
}
