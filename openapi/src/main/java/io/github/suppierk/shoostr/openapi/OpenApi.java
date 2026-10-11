package io.github.suppierk.shoostr.openapi;

import io.github.suppierk.shoostr.ApplicationCallbacks;
import io.github.suppierk.shoostr.Extension;
import io.github.suppierk.shoostr.Extensions;
import io.github.suppierk.shoostr.Handler;
import io.github.suppierk.shoostr.RouteInfo;
import io.github.suppierk.shoostr.http.MediaType;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * Optional generated OpenAPI 3.1.1 document. Select this installed instance beside endpoint
 * registration; register the serving handler explicitly. Configuration and generation are cold: no
 * documented handler runs, no runtime condition is evaluated, and serving reuses frozen bytes.
 */
public final class OpenApi implements Extension<OpenApiOperation> {
  private final Info info;
  private final List<Binding> bindings;
  private final Components components;
  private final List<Server> servers;
  private final List<Tag> tags;
  private final List<OpenApiUi> interfaces;
  private boolean installed;
  private boolean prepared;
  private final AtomicReference<byte @Nullable []> document;

  /**
   * Creates one independent document with required title and API version.
   *
   * @param title nonblank document title
   * @param version nonblank application API version
   */
  public OpenApi(String title, String version) {
    info = new Info().title(text(title)).version(text(version));
    bindings = new ArrayList<>();
    components = new Components();
    servers = new ArrayList<>();
    tags = new ArrayList<>();
    interfaces = new ArrayList<>();
    installed = false;
    prepared = false;
    document = new AtomicReference<>();
  }

  /**
   * Sets document-wide descriptive text.
   *
   * @param value nonblank description
   * @return this extension
   */
  public synchronized OpenApi description(String value) {
    requireMutable();
    info.setDescription(text(value));
    return this;
  }

  /**
   * Adds an explicitly configured server URL without inferring a host from requests.
   *
   * @param url nonblank server URL
   * @return this extension
   */
  public synchronized OpenApi server(String url) {
    requireMutable();
    servers.add(new Server().url(text(url)));
    return this;
  }

  /**
   * Defines descriptive document-wide tag metadata.
   *
   * @param name unique nonblank tag name
   * @param description nonblank tag description
   * @return this extension
   * @throws IllegalArgumentException if the tag is duplicated
   */
  public synchronized OpenApi tag(String name, String description) {
    requireMutable();
    var key = text(name);
    if (tags.stream().anyMatch(tag -> key.equals(tag.getName()))) {
      throw new IllegalArgumentException("Duplicate OpenAPI tag: " + key);
    }

    tags.add(new Tag().name(key).description(text(description)));
    return this;
  }

  /**
   * Creates optional local UI serving behavior, but registers no routes. Reuse this handler on the
   * explicit trailing-slash page and its asset catch-all; register the spec separately. Protect all
   * three registrations with the same authenticator. If documentation is inherited, explicitly hide
   * the UI scope so its catch-all is not published as an API operation.
   *
   * <p>Specification loading retains same-origin browser credentials. API requests omit cached HTTP
   * authentication and cookies; use Swagger Authorize to supply explicit authentication headers.
   * Cookie/session-based API authentication is not supported by this UI policy.
   *
   * @param basePath absolute literal UI page path ending in slash, such as /docs/
   * @param specPath absolute literal same-origin specification path
   * @return handler for the four exact prepared UI resource paths
   */
  public synchronized Handler uiHandler(String basePath, String specPath) {
    requireMutable();
    var ui = new OpenApiUi(basePath, specPath);
    interfaces.add(ui);
    return ui;
  }

  /**
   * Registers an explicit named schema and returns a reusable local reference. No application
   * class, annotation or codec is inspected. Registration detaches the supplied library model.
   *
   * @param name unique component name using letters, digits, dot, underscore or hyphen
   * @param schema explicit OpenAPI schema
   * @return reusable local schema reference
   * @throws IllegalArgumentException if the name is invalid or duplicated
   */
  public synchronized JsonSchema schema(String name, Schema<?> schema) {
    requireMutable();
    var key = componentName(name);
    if (components.getSchemas() != null && components.getSchemas().containsKey(key)) {
      throw new IllegalArgumentException("Duplicate schema: " + key);
    }

    components.addSchemas(key, copy(schema, Schema.class));
    var reference = new JsonSchema();
    reference.set$ref("#/components/schemas/" + key);
    return reference;
  }

  /**
   * Registers an explicit named security scheme; this documents but never enforces authentication.
   *
   * @param name unique component name
   * @param scheme explicit security scheme
   * @return this extension
   * @throws IllegalArgumentException if the name is invalid or duplicated
   */
  public synchronized OpenApi securityScheme(String name, SecurityScheme scheme) {
    requireMutable();
    var key = componentName(name);
    if (components.getSecuritySchemes() != null
        && components.getSecuritySchemes().containsKey(key)) {
      throw new IllegalArgumentException("Duplicate security scheme: " + key);
    }

    OpenApiValidation.scheme(Objects.requireNonNull(scheme));
    var detached = copy(scheme, SecurityScheme.class);
    components.addSecuritySchemes(key, detached);
    return this;
  }

  /**
   * Marks this instance installed once; installing does not register routes or runtime callbacks.
   *
   * @param callbacks unused app registration scope
   * @throws IllegalStateException if this instance was already installed
   */
  @Override
  public synchronized void install(ApplicationCallbacks callbacks) {
    Objects.requireNonNull(callbacks);
    if (installed) {
      throw new IllegalStateException("An OpenApi instance belongs to one application");
    }

    installed = true;
  }

  /**
   * Copies operation defaults independently and subscribes only to this scope's cold facts.
   *
   * @param endpoint provider contribution scope
   * @param inherited parent defaults, or null
   * @return independent operation configurator
   */
  @Override
  public synchronized OpenApiOperation configure(
      Extensions endpoint, @Nullable OpenApiOperation inherited) {
    requireMutable();
    var operation = new OpenApiOperation(this, inherited);
    endpoint.onRoute(route -> collect(route, operation));
    return operation;
  }

  /**
   * Validates and serializes the document after route notifications, before the listener starts.
   *
   * @throws IllegalStateException if generation fails or this instance was already prepared
   * @throws IllegalArgumentException if selected bindings cannot form an unambiguous document
   */
  @Override
  public synchronized void beforeStart() {
    requireMutable();
    prepared = true;
    var paths = new Paths();
    var shapes = new LinkedHashMap<String, String>();
    var identifiers = new HashSet<String>();
    for (var binding : bindings) {
      OpenApiValidation.security(binding.operation(), components);
      var identifier = binding.operation().getOperationId();
      if (identifier != null && !identifiers.add(identifier)) {
        throw new IllegalArgumentException("Duplicate OpenAPI operationId: " + identifier);
      }

      var template = OpenApiPaths.normalize(binding.route().pathTemplate(), binding.operation());
      var shape = template.replaceAll("\\{[^}]+}", "{}");
      var previous = shapes.putIfAbsent(shape, template);
      if (previous != null && !previous.equals(template)) {
        throw new IllegalArgumentException(
            "Conflicting OpenAPI path names: " + previous + " and " + template);
      }

      var path = paths.computeIfAbsent(template, _ -> new PathItem());
      var method = PathItem.HttpMethod.valueOf(binding.route().method().value());
      if (path.readOperationsMap().containsKey(method)) {
        throw new IllegalArgumentException(
            "Conflicting OpenAPI operation: " + method + " " + template);
      }

      path.operation(method, binding.operation());
    }

    try {
      var model = new OpenAPI().openapi("3.1.1").info(info).paths(paths).components(components);
      if (!servers.isEmpty()) {
        model.setServers(servers);
      }

      if (!tags.isEmpty()) {
        model.setTags(tags);
      }

      var bytes = Json31.mapper().writeValueAsBytes(model);
      var tree = Json31.mapper().readTree(bytes);
      OpenApiValidation.references(tree, tree, false);
      if (!interfaces.isEmpty()) {
        var css = OpenApiUi.asset("swagger-ui.css");
        var bundle = OpenApiUi.asset("swagger-ui-bundle.js");
        for (var ui : interfaces) {
          ui.prepare(css, bundle);
        }
      }

      document.set(bytes);
    } catch (IOException failure) {
      throw new IllegalStateException("Cannot serialize OpenAPI document", failure);
    }
  }

  /**
   * Provides the generated JSON handler without registering a route. Document security metadata
   * never enforces authentication: select your authenticator on this handler's actual route.
   *
   * @return handler serving startup-prepared bytes
   */
  public Handler handler() {
    return (_, response) -> {
      var bytes = document.get();
      if (bytes == null) {
        throw new IllegalStateException("OpenAPI document is not prepared");
      }

      response.body(MediaType.APPLICATION_JSON, bytes);
    };
  }

  /**
   * Collects one detached operation without inspecting or evaluating availability predicates.
   *
   * @param route successful final binding
   * @param configuration this binding's operation configuration
   */
  private synchronized void collect(RouteInfo route, OpenApiOperation configuration) {
    requireMutable();
    if (!configuration.isHidden()) {
      bindings.add(new Binding(route, configuration.snapshot()));
    }
  }

  /**
   * Rejects edits after startup preparation begins.
   *
   * @throws IllegalStateException if resources are already frozen
   */
  void requireMutable() {
    if (prepared) {
      throw new IllegalStateException("OpenAPI configuration is frozen at startup");
    }
  }

  /**
   * Detaches a Swagger model using its established OpenAPI 3.1 serializer/deserializer.
   *
   * @param value explicit library model; application models are never scanned
   * @param type library model type
   * @param <T> model type
   * @return independent model graph
   */
  static <T> T copy(T value, Class<T> type) {
    return Objects.requireNonNull(
        Json31.mapper().convertValue(Objects.requireNonNull(value), type));
  }

  /**
   * Validates a required human-readable value.
   *
   * @param value supplied text
   * @return unchanged nonblank text
   * @throws IllegalArgumentException if blank
   */
  static String text(String value) {
    if (Objects.requireNonNull(value).isBlank()) {
      throw new IllegalArgumentException("OpenAPI value must not be blank");
    }

    return value;
  }

  /**
   * Validates an OpenAPI component name without JSON Pointer escaping ambiguity.
   *
   * @param value supplied component name
   * @return valid component name
   * @throws IllegalArgumentException if the name is not representable
   */
  static String componentName(String value) {
    if (!text(value).matches("[A-Za-z0-9._-]+")) {
      throw new IllegalArgumentException("Invalid OpenAPI component name: " + value);
    }

    return value;
  }

  /**
   * Detached metadata for one selected binding.
   *
   * @param route successful final binding
   * @param operation independent document operation
   */
  private record Binding(RouteInfo route, Operation operation) {
    /**
     * Validates required binding metadata.
     *
     * @param route successful binding
     * @param operation detached documentation
     */
    private Binding {
      Objects.requireNonNull(route);
      Objects.requireNonNull(operation);
    }
  }
}
