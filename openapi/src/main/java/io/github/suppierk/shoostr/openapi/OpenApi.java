package io.github.suppierk.shoostr.openapi;

import io.github.suppierk.shoostr.ApplicationCallbacks;
import io.github.suppierk.shoostr.ApplicationExtension;
import io.github.suppierk.shoostr.Handler;
import io.github.suppierk.shoostr.Lifecycle;
import io.github.suppierk.shoostr.RouteExtension;
import io.github.suppierk.shoostr.RouteInfo;
import io.github.suppierk.shoostr.http.HttpCharacters;
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
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;

/**
 * Optional generated OpenAPI 3.1.1 document. Select this installed instance beside endpoint
 * registration; register the serving handler explicitly. Configuration and generation are cold: no
 * documented handler runs, no runtime condition is evaluated, and serving reuses frozen bytes.
 */
public final class OpenApi
    implements ApplicationExtension<OpenApiOperation>, RouteExtension<OpenApiOperation> {
  private static final String OPENAPI_VERSION = "3.1.1";
  private static final String COMPONENT_NAME_REGEX = "[A-Za-z0-9._-]+";
  private static final String PATH_PARAMETER_REGEX = "\\{[^}]+}";

  private final ReentrantLock configurationLock;
  private final Info info;
  private final List<Binding> bindings;
  private final Components components;
  private final List<Server> servers;
  private final List<Tag> tags;
  private final List<OpenApiUi> interfaces;
  private final AtomicReference<byte @Nullable []> document;

  private boolean installed;
  private boolean prepared;

  /**
   * Creates one independent document with required title and API version.
   *
   * @param title nonblank document title
   * @param version nonblank application API version
   */
  public OpenApi(String title, String version) {
    this.configurationLock = new ReentrantLock();
    this.info = new Info().title(text(title)).version(text(version));
    this.bindings = new ArrayList<>();
    this.components = new Components();
    this.servers = new ArrayList<>();
    this.tags = new ArrayList<>();
    this.interfaces = new ArrayList<>();
    this.installed = false;
    this.prepared = false;
    this.document = new AtomicReference<>();
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
    if (!text(value).matches(COMPONENT_NAME_REGEX)) {
      throw new IllegalArgumentException("Invalid OpenAPI component name: " + value);
    }

    return value;
  }

  /**
   * Sets document-wide descriptive text.
   *
   * @param value nonblank description
   * @return this extension
   */
  public OpenApi description(String value) {
    return withMutableConfiguration(() -> {
      info.setDescription(text(value));

      return this;
    });
  }

  /**
   * Adds an explicitly configured server URL without inferring a host from requests.
   *
   * @param url nonblank server URL
   * @return this extension
   */
  public OpenApi server(String url) {
    return withMutableConfiguration(() -> {
      servers.add(new Server().url(text(url)));

      return this;
    });
  }

  /**
   * Defines descriptive document-wide tag metadata.
   *
   * @param name unique nonblank tag name
   * @param description nonblank tag description
   * @return this extension
   * @throws IllegalArgumentException if the tag is duplicated
   */
  public OpenApi tag(String name, String description) {
    return withMutableConfiguration(() -> {
      final var key = text(name);
      if (tags.stream().anyMatch(tag -> key.equals(tag.getName()))) {
        throw new IllegalArgumentException("Duplicate OpenAPI tag: " + key);
      }

      tags.add(new Tag().name(key).description(text(description)));

      return this;
    });
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
  public Handler uiHandler(String basePath, String specPath) {
    return withMutableConfiguration(() -> {
      final var ui = new OpenApiUi(basePath, specPath);
      interfaces.add(ui);

      return ui;
    });
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
  public JsonSchema schema(String name, Schema<?> schema) {
    return withMutableConfiguration(() -> {
      final var key = componentName(name);
      if (components.getSchemas() != null && components.getSchemas().containsKey(key)) {
        throw new IllegalArgumentException("Duplicate schema: " + key);
      }

      components.addSchemas(key, copy(schema, Schema.class));
      final var reference = new JsonSchema();
      reference.set$ref(Components.COMPONENTS_SCHEMAS_REF + key);

      return reference;
    });
  }

  /**
   * Registers an explicit named security scheme; this documents but never enforces authentication.
   *
   * @param name unique component name
   * @param scheme explicit security scheme
   * @return this extension
   * @throws IllegalArgumentException if the name is invalid or duplicated
   */
  public OpenApi securityScheme(String name, SecurityScheme scheme) {
    return withMutableConfiguration(() -> {
      final var key = componentName(name);
      if (components.getSecuritySchemes() != null
          && components.getSecuritySchemes().containsKey(key)) {
        throw new IllegalArgumentException("Duplicate security scheme: " + key);
      }

      OpenApiValidation.scheme(Objects.requireNonNull(scheme));
      final var detached = copy(scheme, SecurityScheme.class);
      components.addSecuritySchemes(key, detached);

      return this;
    });
  }

  /**
   * Marks this instance installed once; installing does not register routes or runtime callbacks.
   *
   * @param callbacks unused app registration scope
   * @throws IllegalStateException if this instance was already installed
   */
  @Override
  public void install(ApplicationCallbacks callbacks) {
    configurationLock.lock();

    try {
      Objects.requireNonNull(callbacks);
      if (installed) {
        throw new IllegalStateException("An OpenApi instance belongs to one application");
      }

      installed = true;
    } finally {
      configurationLock.unlock();
    }
  }

  /**
   * Copies operation defaults independently and subscribes only to this scope's cold facts.
   *
   * @param endpoint provider contribution scope
   * @param inherited parent defaults, or null
   * @return independent operation configurator
   */
  @Override
  public OpenApiOperation configure(Lifecycle endpoint, @Nullable OpenApiOperation inherited) {
    return withMutableConfiguration(() -> {
      final var operation = new OpenApiOperation(this, inherited);

      endpoint.onRoute(route -> collect(route, operation));

      return operation;
    });
  }

  /**
   * Validates and serializes the document after route notifications, before the listener starts.
   *
   * @throws IllegalStateException if generation fails or this instance was already prepared
   * @throws IllegalArgumentException if selected bindings cannot form an unambiguous document
   */
  @Override
  public void beforeStart() {
    configurationLock.lock();

    try {
      requireMutable();

      prepared = true;

      final var paths = assemblePaths();

      try {
        final var bytes = serializeDocument(paths);
        prepareInterfaces();
        document.set(bytes);
      } catch (IOException | JacksonException failure) {
        throw new IllegalStateException("Cannot serialize OpenAPI document", failure);
      }
    } finally {
      configurationLock.unlock();
    }
  }

  /**
   * Validates and assembles paths from the collected bindings while holding the configuration lock.
   *
   * @return paths for the document
   * @throws IllegalArgumentException if the bindings cannot form an unambiguous document
   */
  private Paths assemblePaths() {
    final var paths = new Paths();
    final var shapes = new LinkedHashMap<String, String>();
    final var identifiers = new HashSet<String>();
    for (final var binding : bindings) {
      OpenApiValidation.security(binding.operation(), components);
      final var identifier = binding.operation().getOperationId();
      if (identifier != null && !identifiers.add(identifier)) {
        throw new IllegalArgumentException("Duplicate OpenAPI operationId: " + identifier);
      }

      final var template =
          OpenApiPaths.normalize(binding.route().pathTemplate(), binding.operation());
      final var shape = template.replaceAll(PATH_PARAMETER_REGEX, HttpCharacters.CURLY_BRACES);
      final var previous = shapes.putIfAbsent(shape, template);
      if (previous != null && !previous.equals(template)) {
        throw new IllegalArgumentException(
            "Conflicting OpenAPI path names: " + previous + " and " + template);
      }

      final var path = paths.computeIfAbsent(template, ignored -> new PathItem());
      final var method = PathItem.HttpMethod.valueOf(binding.route().method().value());
      if (path.readOperationsMap().containsKey(method)) {
        throw new IllegalArgumentException(
            "Conflicting OpenAPI operation: " + method + " " + template);
      }

      path.operation(method, binding.operation());
    }

    return paths;
  }

  /**
   * Serializes the document and validates its references while holding the configuration lock.
   *
   * @param paths assembled document paths
   * @return validated JSON bytes
   */
  private byte[] serializeDocument(Paths paths) {
    final var model =
        new OpenAPI().openapi(OPENAPI_VERSION).info(info).paths(paths).components(components);
    if (!servers.isEmpty()) {
      model.setServers(servers);
    }

    if (!tags.isEmpty()) {
      model.setTags(tags);
    }

    final var bytes = Json31.mapper().writeValueAsBytes(model);
    final var tree = Json31.mapper().readTree(bytes);
    OpenApiValidation.references(tree, tree, false);
    return bytes;
  }

  /**
   * Prepares registered UI handlers from the shared assets while holding the configuration lock.
   *
   * @throws IOException if a bundled asset cannot be read
   */
  private void prepareInterfaces() throws IOException {
    if (!interfaces.isEmpty()) {
      final var css = OpenApiUi.asset(OpenApiUi.STYLESHEET_NAME);
      final var bundle = OpenApiUi.asset(OpenApiUi.BUNDLE_NAME);
      for (final var ui : interfaces) {
        ui.prepare(css, bundle);
      }
    }
  }

  /**
   * Provides the generated JSON handler without registering a route. Document security metadata
   * never enforces authentication: select your authenticator on this handler's actual route.
   *
   * @return handler serving startup-prepared bytes
   */
  public Handler handler() {
    return (ignoredRequest, response) -> {
      final var bytes = document.get();
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
  private void collect(RouteInfo route, OpenApiOperation configuration) {
    configurationLock.lock();

    try {
      requireMutable();

      if (!configuration.isHidden()) {
        bindings.add(new Binding(route, configuration.snapshot()));
      }
    } finally {
      configurationLock.unlock();
    }
  }

  /**
   * Runs a configuration change under the lock after checking that configuration is mutable.
   *
   * @param action configuration validation and change
   * @param <T> configuration result type
   * @return result of the configuration change
   */
  private <T> T withMutableConfiguration(Supplier<T> action) {
    configurationLock.lock();

    try {
      requireMutable();
      return action.get();
    } finally {
      configurationLock.unlock();
    }
  }

  /**
   * Provides the lock shared by document and operation configuration.
   *
   * @return this document's configuration lock
   */
  ReentrantLock configurationLock() {
    return configurationLock;
  }

  /**
   * Rejects edits after startup preparation begins while the caller holds the configuration lock.
   *
   * @throws IllegalStateException if resources are already frozen
   */
  void requireMutable() {
    if (prepared) {
      throw new IllegalStateException("OpenAPI configuration is frozen at startup");
    }
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
