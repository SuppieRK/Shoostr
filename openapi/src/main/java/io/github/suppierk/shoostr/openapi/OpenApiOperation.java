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
    operation = inherited == null ? new Operation() : inherited.snapshot();
    hidden = inherited != null && inherited.hidden;
  }

  /**
   * Sets or overrides this operation's summary.
   *
   * @param value nonblank summary
   * @return this configuration
   */
  public OpenApiOperation summary(String value) {
    synchronized (owner) {
      owner.requireMutable();
      operation.setSummary(OpenApi.text(value));
      return this;
    }
  }

  /**
   * Sets or overrides this operation's description.
   *
   * @param value nonblank description
   * @return this configuration
   */
  public OpenApiOperation description(String value) {
    synchronized (owner) {
      owner.requireMutable();
      operation.setDescription(OpenApi.text(value));
      return this;
    }
  }

  /**
   * Sets an explicit operation identifier, checked for uniqueness within this document at startup.
   *
   * @param value nonblank operation identifier
   * @return this configuration
   */
  public OpenApiOperation operationId(String value) {
    synchronized (owner) {
      owner.requireMutable();
      operation.setOperationId(OpenApi.text(value));
      return this;
    }
  }

  /**
   * Adds a tag without duplicating an inherited tag.
   *
   * @param value nonblank tag name
   * @return this configuration
   */
  public OpenApiOperation tag(String value) {
    synchronized (owner) {
      owner.requireMutable();
      var tag = OpenApi.text(value);
      if (operation.getTags() == null) {
        operation.setTags(new ArrayList<>());
      }

      if (!operation.getTags().contains(tag)) {
        operation.addTagsItem(tag);
      }

      return this;
    }
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
    return parameter(
        new Parameter()
            .name(OpenApi.text(name))
            .in("query")
            .description(OpenApi.text(description))
            .schema(Objects.requireNonNull(schema))
            .required(required));
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
    return parameter(
        new Parameter()
            .name(OpenApi.text(name))
            .in("header")
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
            .content(
                new Content()
                    .addMediaType(
                        MediaType.of(mediaType).value(),
                        new io.swagger.v3.oas.models.media.MediaType()
                            .schema(Objects.requireNonNull(schema)))));
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
    synchronized (owner) {
      owner.requireMutable();
      Objects.requireNonNull(body);
      if (body.get$ref() == null && (body.getContent() == null || body.getContent().isEmpty())) {
        throw new IllegalArgumentException("OpenAPI request bodies require content");
      }

      operation.setRequestBody(OpenApi.copy(body, RequestBody.class));
      return this;
    }
  }

  /**
   * Replaces a parameter by name/location, preserving unrelated inherited parameters.
   *
   * @param value explicit path, query or header parameter
   * @return this configuration
   * @throws IllegalArgumentException if name/location is absent or unsupported
   */
  public OpenApiOperation parameter(Parameter value) {
    synchronized (owner) {
      owner.requireMutable();
      var parameter = OpenApi.copy(value, Parameter.class);
      if (parameter.getName() == null
          || parameter.getName().isBlank()
          || !java.util.Set.of("path", "query", "header").contains(parameter.getIn())) {
        throw new IllegalArgumentException(
            "Explicit parameters need a name and path/query/header location");
      }

      if ((parameter.getSchema() == null) == (parameter.getContent() == null)
          || (parameter.getContent() != null && parameter.getContent().size() != 1)) {
        throw new IllegalArgumentException(
            "OpenAPI parameters require either a schema or one content entry");
      }

      if (operation.getParameters() == null) {
        operation.setParameters(new ArrayList<>());
      }

      operation
          .getParameters()
          .removeIf(
              existing ->
                  parameter.getName().equals(existing.getName())
                      && parameter.getIn().equals(existing.getIn()));
      operation.addParametersItem(parameter);
      return this;
    }
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
    var requirement = new SecurityRequirement();
    var values = new ArrayList<String>();
    if (scopes != null) {
      for (var scope : scopes) {
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
    synchronized (owner) {
      owner.requireMutable();
      operation.setSecurity(
          new ArrayList<>(java.util.List.of(OpenApi.copy(requirement, SecurityRequirement.class))));
      return this;
    }
  }

  /**
   * Clears inherited documentation security without disabling actual route authentication.
   *
   * @return this configuration
   */
  public OpenApiOperation anonymous() {
    synchronized (owner) {
      owner.requireMutable();
      operation.setSecurity(new ArrayList<>());
      return this;
    }
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
            .content(
                new Content()
                    .addMediaType(
                        MediaType.of(mediaType).value(),
                        new io.swagger.v3.oas.models.media.MediaType()
                            .schema(Objects.requireNonNull(schema)))));
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
    synchronized (owner) {
      owner.requireMutable();
      if (status < 100 || status > 599) {
        throw new IllegalArgumentException("Invalid documented HTTP status");
      }

      var response = OpenApi.copy(value, ApiResponse.class);
      if (response.get$ref() == null && response.getDescription() == null) {
        throw new IllegalArgumentException("OpenAPI responses require a description");
      }

      if (operation.getResponses() == null) {
        operation.setResponses(new ApiResponses());
      }

      operation.getResponses().addApiResponse(Integer.toString(status), response);
      return this;
    }
  }

  /**
   * Excludes this scope and its descendants, including inherited documentation defaults.
   *
   * @return this configuration
   */
  public OpenApiOperation hidden() {
    synchronized (owner) {
      owner.requireMutable();
      hidden = true;
      return this;
    }
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
}
