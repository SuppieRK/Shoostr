package io.github.suppierk.shoostr.mcp;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.suppierk.shoostr.ApplicationCallbacks;
import io.github.suppierk.shoostr.Extension;
import io.github.suppierk.shoostr.Extensions;
import io.github.suppierk.shoostr.Request;
import io.github.suppierk.shoostr.Response;
import io.github.suppierk.shoostr.Routes;
import io.github.suppierk.shoostr.http.MediaType;
import io.github.suppierk.shoostr.http.exceptions.AuthenticationRequiredException;
import io.github.suppierk.shoostr.http.exceptions.BadRequestException;
import io.github.suppierk.shoostr.http.exceptions.ForbiddenException;
import io.github.suppierk.shoostr.http.exceptions.NotAcceptableException;
import io.github.suppierk.shoostr.http.exceptions.NotFoundException;
import io.github.suppierk.shoostr.http.exceptions.ServiceUnavailableException;
import io.github.suppierk.shoostr.http.exceptions.TooManyRequestsException;
import io.github.suppierk.shoostr.http.exceptions.UnsupportedMediaTypeException;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import org.eclipse.jetty.http.HttpField;
import org.jspecify.annotations.Nullable;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.Exceptions;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Owns one native SDK server and its explicitly mounted Streamable HTTP transport. Configure tools,
 * schemas and capabilities directly through the SDK factory; installation adds no endpoint. The
 * returned native server is borrowed for management and must not be closed independently. Existing
 * factories apply finite admission defaults. Compose {@link McpWork#call} through a native async
 * handler for owned callback execution; raw native callback execution remains application-owned.
 *
 * @param <S> native SDK server type
 */
public final class Mcp<S> implements Extension<Void> {
  private static final MediaType EVENT_STREAM = MediaType.of("text/event-stream");
  private static final String FIELD_METHOD = "method";
  private static final String FIELD_RESULT = "result";
  private static final String FIELD_ERROR = "error";
  private final McpHttpSecurity security;
  private final String expiredTokenChallenge;
  private final Provider provider;
  private final S server;
  private final AtomicBoolean owned;
  private final ScheduledThreadPoolExecutor deadlines;
  private final String lifetimeAttribute;
  private @Nullable String mount;

  /**
   * Invokes the native factory eagerly and verifies that it bound the supplied transport.
   *
   * @param security explicit HTTP security
   * @param limits finite session capacity and idle policy
   * @param factory native server factory, invoked exactly once
   */
  private Mcp(
      McpHttpSecurity security,
      McpSessionLimits limits,
      Function<McpStreamableServerTransportProvider, S> factory) {
    this(
        security,
        limits,
        McpOperationLimits.defaults(),
        McpOperationLimits.defaults(),
        nativeFactory(factory));
  }

  /**
   * Creates finite session and operation ownership before invoking the native factory.
   *
   * @param security detached identity and HTTP authorization policy
   * @param limits session allocation and idle policy
   * @param ordinary ordinary admission and managed execution policy
   * @param control independent reply and cancellation policy
   * @param factory eager native factory receiving borrowed transport and managed work
   */
  private Mcp(
      McpHttpSecurity security,
      McpSessionLimits limits,
      McpOperationLimits ordinary,
      McpOperationLimits control,
      BiFunction<McpStreamableServerTransportProvider, McpWork, S> factory) {
    this.security = Objects.requireNonNull(security);
    Objects.requireNonNull(limits);
    var metadata = security.resourceMetadata();
    expiredTokenChallenge =
        "Bearer error=\"invalid_token\""
            + (metadata == null ? "" : ", resource_metadata=\"" + metadata.toASCIIString() + "\"");
    Objects.requireNonNull(factory);
    Objects.requireNonNull(ordinary);
    Objects.requireNonNull(control);
    owned = new AtomicBoolean();
    deadlines =
        new ScheduledThreadPoolExecutor(
            1, task -> Thread.ofPlatform().daemon().name("shoostr-mcp-expiry").unstarted(task));
    deadlines.setRemoveOnCancelPolicy(true);
    deadlines.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    lifetimeAttribute = McpRequestLifetime.class.getName() + "." + UUID.randomUUID();
    provider = new Provider(limits, ordinary, control, deadlines);

    server = createServer(factory);
  }

  /**
   * Builds the native server and releases owned transport resources if construction fails.
   *
   * @param factory eager native server factory
   * @return non-null server bound to this transport
   * @throws IllegalArgumentException if the factory does not bind the supplied transport
   */
  private S createServer(BiFunction<McpStreamableServerTransportProvider, McpWork, S> factory) {
    boolean created = false;

    try {
      var nativeServer = Objects.requireNonNull(factory.apply(provider, provider));
      if (provider.factory == null) {
        throw new IllegalArgumentException(
            "The native factory must bind the supplied MCP transport");
      }

      provider.startIdleExpiry(deadlines);
      created = true;
      return nativeServer;
    } finally {
      if (!created) {
        try {
          provider.closeGracefully().block();
        } finally {
          deadlines.shutdownNow();
        }
      }
    }
  }

  /**
   * Creates a synchronous native server without duplicating its configuration methods.
   *
   * @param security explicit HTTP security
   * @param factory eager native server factory; do not return a server owned elsewhere
   * @return independently owned MCP capability
   */
  public static Mcp<McpSyncServer> sync(
      McpHttpSecurity security,
      Function<McpStreamableServerTransportProvider, McpSyncServer> factory) {
    return sync(security, McpSessionLimits.defaults(), factory);
  }

  /**
   * Creates a synchronous native server with explicit session capacity and idle policy.
   *
   * @param security explicit HTTP security
   * @param limits finite limits within this capability and each verified owner
   * @param factory eager native server factory; do not return a server owned elsewhere
   * @return independently owned MCP capability
   */
  public static Mcp<McpSyncServer> sync(
      McpHttpSecurity security,
      McpSessionLimits limits,
      Function<McpStreamableServerTransportProvider, McpSyncServer> factory) {
    return new Mcp<>(security, limits, factory);
  }

  /**
   * Creates a native synchronous server with independent ordinary and control admission policies.
   * Native callback execution remains application-owned.
   *
   * @param security explicit HTTP security
   * @param sessions session capacity and idle policy
   * @param ordinary ordinary operation capacity and deadline
   * @param control reserved reply and cancellation capacity and deadline
   * @param factory eager native server factory
   * @return independently owned MCP capability
   */
  public static Mcp<McpSyncServer> sync(
      McpHttpSecurity security,
      McpSessionLimits sessions,
      McpOperationLimits ordinary,
      McpOperationLimits control,
      Function<McpStreamableServerTransportProvider, McpSyncServer> factory) {
    return new Mcp<>(security, sessions, ordinary, control, nativeFactory(factory));
  }

  /**
   * Creates an asynchronous native server without duplicating its configuration methods.
   *
   * @param security explicit HTTP security
   * @param factory eager native server factory; do not return a server owned elsewhere
   * @return independently owned MCP capability
   */
  public static Mcp<McpAsyncServer> async(
      McpHttpSecurity security,
      Function<McpStreamableServerTransportProvider, McpAsyncServer> factory) {
    return async(security, McpSessionLimits.defaults(), factory);
  }

  /**
   * Creates an asynchronous native server with explicit session capacity and idle policy.
   *
   * @param security explicit HTTP security
   * @param limits finite limits within this capability and each verified owner
   * @param factory eager native server factory; do not return a server owned elsewhere
   * @return independently owned MCP capability
   */
  public static Mcp<McpAsyncServer> async(
      McpHttpSecurity security,
      McpSessionLimits limits,
      Function<McpStreamableServerTransportProvider, McpAsyncServer> factory) {
    return new Mcp<>(security, limits, factory);
  }

  /**
   * Creates a native async server with independently configured ordinary/control admission and
   * borrowed managed execution. Return or compose {@link McpWork#call} from native async handlers.
   *
   * @param security explicit HTTP security
   * @param sessions session capacity and idle policy
   * @param ordinary operation and managed execution capacities and absolute deadline
   * @param control reserved reply/cancellation capacity and absolute deadline
   * @param factory eager factory receiving native transport and borrowed managed work
   * @return independently owned MCP capability
   */
  public static Mcp<McpAsyncServer> async(
      McpHttpSecurity security,
      McpSessionLimits sessions,
      McpOperationLimits ordinary,
      McpOperationLimits control,
      BiFunction<McpStreamableServerTransportProvider, McpWork, McpAsyncServer> factory) {
    return new Mcp<>(security, sessions, ordinary, control, factory);
  }

  /**
   * Returns the borrowed execution facility for later native async callback registration.
   *
   * @return capability-owned managed work; never independently close it
   */
  public McpWork work() {
    return provider;
  }

  /**
   * Adapts an eagerly validated native factory without changing its callback execution.
   *
   * @param <T> native server type
   * @param factory transport-only native factory
   * @return factory that preserves application ownership of raw native callbacks
   */
  private static <T> BiFunction<McpStreamableServerTransportProvider, McpWork, T> nativeFactory(
      Function<McpStreamableServerTransportProvider, T> factory) {
    Objects.requireNonNull(factory);
    return (transport, work) -> factory.apply(transport);
  }

  /**
   * Returns borrowed native management access. The adapter/app owns server closure.
   *
   * @return configured native server
   */
  public S server() {
    return server;
  }

  /**
   * Rejects concurrent or repeated installation without disturbing the first owner's resources.
   *
   * @throws IllegalStateException if an application already claimed this instance
   */
  @Override
  public void claimOwnership() {
    synchronized (provider) {
      if (provider.stopping || !owned.compareAndSet(false, true)) {
        throw new IllegalStateException(
            "An MCP capability must be open and can belong to only one application");
      }
    }
  }

  /**
   * Contributes the pre-authentication Origin guard without adding HTTP routes.
   *
   * @param callbacks registration-only application callbacks
   */
  @Override
  public void install(ApplicationCallbacks callbacks) {
    callbacks.observe(
        request -> {
          if (!Objects.equals(mount, request.path())) {
            return ignored -> {};
          }

          var lifetime = new McpRequestLifetime(deadlines, expiredTokenChallenge);
          request.attribute(lifetimeAttribute, lifetime);
          return lifetime;
        });
    callbacks.beforeResponseFlush(
        (request, response) -> {
          if (Objects.equals(mount, request.path())) {
            lifetime(request).beforeFlush(response.status() == 401);
          }
        });
    callbacks.onRequestHeaders(
        (request, ignored2) -> {
          if (Objects.equals(mount, request.path())) {
            validateOrigin(request);
          }
        });
  }

  /**
   * Mounts this instance at the current literal group endpoint. Select managed authentication on
   * the enclosing group when required; installation alone never protects the endpoint.
   *
   * @param routes explicitly composed route group
   */
  public void routes(Routes routes) {
    register(routes, null);
  }

  /**
   * Mounts this instance using a caller-owned executor for matched HTTP lifecycle and handlers.
   * Native callbacks retain their configured execution; managed work uses the capability's owned
   * executor. The caller must keep the HTTP executor available until the app has closed; Shoostr
   * never shuts it down.
   *
   * @param routes explicitly composed route group
   * @param executor caller-owned executor for POST, GET and DELETE
   */
  public void routes(Routes routes, ExecutorService executor) {
    register(routes, Objects.requireNonNull(executor));
  }

  /**
   * Shares endpoint security and mount validation between default and explicit execution.
   *
   * @param routes explicitly composed route group
   * @param executor caller-owned executor, or null for default execution
   */
  private void register(Routes routes, @Nullable ExecutorService executor) {
    Objects.requireNonNull(routes);
    Consumer<Extensions> configuration =
        extensions -> extensions.requireInstalled(this).onRoute((ignored3, path) -> bind(path));
    Consumer<Extensions> installed = extensions -> extensions.requireInstalled(this);
    if (executor == null) {
      routes.post("", this::post, configuration);
      routes.get("", this::get, installed);
      routes.delete("", this::delete, installed);
    } else {
      routes.post("", executor, this::post, configuration);
      routes.get("", executor, this::get, installed);
      routes.delete("", executor, this::delete, installed);
    }
  }

  /** Closes the owned native sessions and transport. */
  @Override
  public void close() {
    try {
      provider.closeGracefully().block();
    } finally {
      deadlines.shutdownNow();
    }
  }

  /** Signals detached active transports before the native server begins graceful drain. */
  @Override
  public void beforeStop() {
    provider.stop();
  }

  /**
   * Records and validates the compiled mount before requests are admitted.
   *
   * @param path composed literal path
   * @throws IllegalArgumentException if the mount contains dynamic path segments
   * @throws IllegalStateException if this instance already has a mount
   */
  private synchronized void bind(String path) {
    if (path.indexOf('{') >= 0 || path.indexOf('*') >= 0) {
      throw new IllegalArgumentException("An MCP mount must be a literal path");
    }

    if (mount != null) {
      throw new IllegalStateException("An MCP capability can be mounted only once");
    }

    mount = path;
  }

  /**
   * Rejects every malformed or unapproved present Origin before authentication.
   *
   * @param request current request
   * @throws ForbiddenException if a present Origin is malformed or not allowlisted
   */
  private void validateOrigin(Request request) {
    var values = request.headers("Origin");
    if (values.isEmpty()) {
      return;
    }

    if (values.size() != 1) {
      throw new ForbiddenException();
    }

    try {
      if (!security.allowedOrigins().contains(McpHttpSecurity.origin(values.getFirst()))) {
        throw new ForbiddenException();
      }
    } catch (IllegalArgumentException ignored4) {
      throw new ForbiddenException();
    }
  }

  /**
   * Initializes one native SDK session with current detached identity and permissions.
   *
   * @param request current authenticated request
   * @param response owner-thread response
   * @throws IOException if protocol JSON cannot be read or written
   * @throws BadRequestException if the message kind is unsupported
   */
  private void post(Request request, Response response) throws IOException {
    validateProtocolVersion(request);
    requireJson(request);
    requireAccept(request, response);
    response.negotiate(MediaType.APPLICATION_JSON);
    var identity = identity(request);
    byte[] bytes;

    try {
      bytes = request.bodyBytes();
    } catch (IOException | RuntimeException failure) {
      lifetime(request).requireFresh();
      throw failure;
    }

    lifetime(request).requireFresh();
    Object decoded;

    try {
      var body = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
      decoded = provider.mapper.readValue(body, new TypeRef<>() {});
    } catch (IOException ignored5) {
      rejectMessage(response, -32700, "Parse error", null);
      return;
    }

    if (!(decoded instanceof Map<?, ?> fields) || !validEnvelope(fields)) {
      rejectMessage(
          response,
          -32600,
          "Invalid Request",
          decoded instanceof Map<?, ?> invalid ? invalid.get("id") : null);
      return;
    }

    McpSchema.JSONRPCMessage message;

    try {
      Class<? extends McpSchema.JSONRPCMessage> type;
      if (fields.containsKey(FIELD_METHOD)) {
        type =
            fields.containsKey("id")
                ? McpSchema.JSONRPCRequest.class
                : McpSchema.JSONRPCNotification.class;
      } else {
        type = McpSchema.JSONRPCResponse.class;
      }

      message = provider.mapper.convertValue(fields, type);
    } catch (IllegalArgumentException ignored6) {
      rejectMessage(response, -32600, "Invalid Request", fields.get("id"));
      return;
    }

    if (message instanceof McpSchema.JSONRPCRequest rpc
        && McpSchema.METHOD_INITIALIZE.equals(rpc.method())) {
      initialize(rpc, identity, response, lifetime(request));
      return;
    }

    var session = session(request, identity);
    authorize(lifetime(request), identity, message);
    if (message instanceof McpSchema.JSONRPCRequest rpc) {
      var stream = new McpStream(provider.mapper, identity.expiresAt());
      lifetime(request).stream(stream);
      var nativeStream = provider.dispatch(session, stream, rpc, identity.context());

      try {
        stream.pump(response);
      } finally {
        nativeStream.releaseTransport();
        stream.finish();
        provider.streams.remove(stream);
      }
    } else {
      provider.accept(session, message, identity.context());
      response.status(202);
    }
  }

  /**
   * Validates and publishes one native initialization with no pre-validation side effects.
   *
   * @param rpc initialization request
   * @param identity detached authenticated identity
   * @param response owner-thread response
   * @param lifetime authenticated HTTP lifetime
   * @throws IOException if protocol output cannot be encoded
   */
  private void initialize(
      McpSchema.JSONRPCRequest rpc,
      McpIdentity identity,
      Response response,
      McpRequestLifetime lifetime)
      throws IOException {
    if (!validInitialization(rpc.params())) {
      rejectMessage(response, -32602, "Invalid params", rpc.id());
      return;
    }

    McpSchema.InitializeRequest parameters;

    try {
      parameters = provider.mapper.convertValue(rpc.params(), McpSchema.InitializeRequest.class);
    } catch (IllegalArgumentException ignored7) {
      rejectMessage(response, -32602, "Invalid params", rpc.id());
      return;
    }

    authorize(lifetime, identity, rpc);
    provider.reserve(identity.sessionOwner());
    McpStreamableServerSession.McpStreamableServerSessionInit initialized;
    Session session;
    boolean constructed = false;

    try {
      initialized =
          Objects.requireNonNull(Objects.requireNonNull(provider.factory).startSession(parameters));
      session = new Session(initialized.session(), identity.sessionOwner());
      constructed = true;
    } catch (RuntimeException failure) {
      lifetime.requireFresh();
      throw failure;
    } finally {
      if (!constructed) {
        provider.release(identity.sessionOwner());
      }
    }

    boolean staged = false;

    try {
      lifetime.initialization(
          outcome -> {
            if (outcome.statusCode() != 200
                || outcome.applicationFailure() != null
                || outcome.transportFailure() != null) {
              provider.rollback(session);
            }
          });
      var cancellation = provider.provision(session);
      var result =
          Objects.requireNonNull(
              initialized
                  .initResult()
                  .takeUntilOther(cancellation.asMono())
                  .switchIfEmpty(Mono.error(new ServiceUnavailableException()))
                  .contextWrite(context -> context.put(McpTransportContext.KEY, identity.context()))
                  .block());
      var body =
          provider.mapper.writeValueAsBytes(McpSchema.JSONRPCResponse.result(rpc.id(), result));
      lifetime.requireFresh();
      provider.publish(session);
      response.setHeader("Mcp-Session-Id", session.sdk().getId()).body("application/json", body);
      staged = true;
    } catch (IOException | RuntimeException failure) {
      lifetime.requireFresh();
      throw failure;
    } finally {
      if (!staged) {
        provider.rollback(session);
      }
    }
  }

  /**
   * Verifies freshness around permission callbacks without leaking deadline interruptions.
   *
   * @param lifetime authenticated HTTP lifetime
   * @param identity detached verified identity
   * @param message validated native message
   * @throws AuthenticationRequiredException if authorization outlives the verified expiry
   */
  private void authorize(
      McpRequestLifetime lifetime, McpIdentity identity, McpSchema.JSONRPCMessage message) {
    lifetime.requireFresh();

    try {
      security.authorize().accept(identity, message);
    } catch (RuntimeException failure) {
      lifetime.requireFresh();
      throw failure;
    }

    lifetime.requireFresh();
  }

  /**
   * Checks required initialization fields before native coercion and session allocation.
   *
   * @param parameters uncoerced initialization parameters
   * @return whether the required version, capability and client-info shapes are present
   */
  private static boolean validInitialization(@Nullable Object parameters) {
    return parameters instanceof Map<?, ?> fields
        && fields.get("protocolVersion") instanceof String
        && fields.get("capabilities") instanceof Map<?, ?>
        && fields.get("clientInfo") instanceof Map<?, ?> client
        && client.get("name") instanceof String
        && client.get("version") instanceof String;
  }

  /**
   * Validates the wire envelope before native mapper coercion can erase invalid field types. Native
   * SDK dispatch remains responsible for method-specific parameters.
   *
   * @param fields parsed JSON object
   * @return whether one dated MCP message can be passed to native decoding
   */
  private static boolean validEnvelope(Map<?, ?> fields) {
    if (!"2.0".equals(fields.get("jsonrpc"))
        || (fields.containsKey("id") && !validId(fields.get("id")))) {
      return false;
    }

    if (fields.containsKey(FIELD_METHOD)) {
      return fields.get(FIELD_METHOD) instanceof String method
          && !method.isBlank()
          && !fields.containsKey(FIELD_RESULT)
          && !fields.containsKey(FIELD_ERROR)
          && (!fields.containsKey("params") || fields.get("params") instanceof Map<?, ?>);
    }

    if (!fields.containsKey("id")
        || fields.containsKey(FIELD_RESULT) == fields.containsKey(FIELD_ERROR)) {
      return false;
    }

    if (fields.containsKey(FIELD_RESULT)) {
      return fields.get(FIELD_RESULT) instanceof Map<?, ?>;
    }

    return fields.get(FIELD_ERROR) instanceof Map<?, ?> error
        && error.get("code") instanceof Integer
        && error.get("message") instanceof String;
  }

  /**
   * Checks the non-null identifier types supported by the released SDK.
   *
   * @param id original wire identifier
   * @return whether native correlation can retain this identifier without coercion
   */
  private static boolean validId(@Nullable Object id) {
    return id instanceof String || id instanceof Integer || id instanceof Long;
  }

  /**
   * Emits a finite dated MCP transport error without allocating a session or committing SSE.
   *
   * @param response current owner-thread response
   * @param code standard JSON-RPC error code
   * @param message static error text without input or credentials
   * @param id original identifier, retained only when native correlation supports it
   * @throws IOException if serializing the finite error fails
   */
  private void rejectMessage(Response response, int code, String message, @Nullable Object id)
      throws IOException {
    var envelope = new HashMap<String, Object>();
    envelope.put("jsonrpc", "2.0");
    envelope.put(FIELD_ERROR, Map.of("code", code, "message", message));
    if (validId(id)) {
      envelope.put("id", id);
    }

    response.status(400).body("application/json", provider.mapper.writeValueAsBytes(envelope));
  }

  /**
   * Opens the native session's listening stream without sharing its live HTTP response.
   *
   * @param request current authenticated request
   * @param response owner-thread response
   * @throws IOException if streaming fails
   */
  private void get(Request request, Response response) throws IOException {
    validateProtocolVersion(request);
    requireAccept(request, response);
    var identity = identity(request);
    var session = session(request, identity);
    var stream = new McpStream(provider.mapper, identity.expiresAt());
    lifetime(request).stream(stream);
    var listening = provider.listen(session, stream);

    try {
      stream.pump(response);
    } finally {
      listening.releaseTransport();
      provider.streams.remove(stream);
    }
  }

  /**
   * Deletes only the freshly authenticated caller's native session.
   *
   * @param request current authenticated request
   * @param response owner-thread response
   */
  private void delete(Request request, Response response) {
    validateProtocolVersion(request);
    var identity = identity(request);
    var session = session(request, identity);
    lifetime(request).requireFresh();
    provider
        .terminate(session, true)
        .contextWrite(context -> context.put(McpTransportContext.KEY, identity.context()))
        .block();
    response.status(200);
  }

  /**
   * Rejects unsupported or ambiguous explicit versions without rejecting a legacy absent header.
   * The native initialization handler negotiates its body version separately.
   *
   * @param request current HTTP request
   * @throws BadRequestException if the version is duplicated or unsupported
   */
  private void validateProtocolVersion(Request request) {
    var versions = request.headers("MCP-Protocol-Version");
    if (!versions.isEmpty()
        && (versions.size() != 1 || !provider.protocolVersions().contains(versions.getFirst()))) {
      throw new BadRequestException("Unsupported MCP protocol version");
    }
  }

  /**
   * Requires the explicit SSE acceptance shared by POST and GET, using core negotiation rules.
   *
   * @param request current HTTP request
   * @param response uncommitted owner-thread response
   * @throws NotAcceptableException if Accept is absent or excludes event streams
   */
  private static void requireAccept(Request request, Response response) {
    if (request.headers("Accept").isEmpty()) {
      throw new NotAcceptableException();
    }

    response.negotiate(EVENT_STREAM);
  }

  /**
   * Rejects an explicit non-JSON or ambiguous representation without requiring Content-Type.
   *
   * @param request current HTTP request
   * @throws UnsupportedMediaTypeException if an explicit representation is not JSON
   */
  private static void requireJson(Request request) {
    var values = request.headers("Content-Type");
    if (!values.isEmpty()
        && (values.size() != 1
            || !MediaType.APPLICATION_JSON
                .value()
                .equalsIgnoreCase(HttpField.getValueParameters(values.getFirst(), null)))) {
      throw new UnsupportedMediaTypeException();
    }
  }

  /**
   * Verifies freshness before permissions or native effects on every authenticated HTTP request.
   *
   * @param request current authenticated request
   * @return detached, currently valid identity
   * @throws AuthenticationRequiredException if the verified token has expired
   */
  private McpIdentity identity(Request request) {
    var identity = Objects.requireNonNull(security.identity().apply(request));
    lifetime(request).arm(identity.expiresAt());
    return identity;
  }

  /**
   * Requires installed lifetime bookkeeping rather than relying on isolated observer failures.
   *
   * @param request owner-thread request
   * @return detached lifetime initialized before routing
   * @throws IllegalStateException if required observation initialization was missing or failed
   */
  private McpRequestLifetime lifetime(Request request) {
    return request
        .attribute(lifetimeAttribute)
        .filter(McpRequestLifetime.class::isInstance)
        .map(McpRequestLifetime.class::cast)
        .orElseThrow(() -> new IllegalStateException("The MCP HTTP lifetime was not initialized"));
  }

  /**
   * Resolves an opaque session only for the same authenticated owner.
   *
   * @param request current authenticated request
   * @param identity current detached identity
   * @return native session and its bound owner
   * @throws BadRequestException if the session header is absent, blank or duplicated
   * @throws NotFoundException if the session does not belong to this caller
   */
  private Session session(Request request, McpIdentity identity) {
    var values = request.headers("Mcp-Session-Id");
    if (values.size() != 1 || values.getFirst().isBlank()) {
      throw new BadRequestException();
    }

    var session = provider.sessions.get(values.getFirst());
    if (session == null || !session.owner().equals(identity.sessionOwner())) {
      throw new NotFoundException();
    }

    return session;
  }

  /** Correlates native session state with the detached authenticated owner. */
  private record Session(
      McpStreamableServerSession sdk,
      String owner,
      Disposable.Composite dispatches,
      AtomicBoolean terminating,
      AtomicLong activity,
      AtomicInteger notifications) {
    /**
     * Creates independent ownership for native operations, including detached HTTP work.
     *
     * @param sdk native session state
     * @param owner stable authenticated owner key
     */
    private Session(McpStreamableServerSession sdk, String owner) {
      this(
          sdk,
          owner,
          Disposables.composite(),
          new AtomicBoolean(),
          new AtomicLong(System.nanoTime()),
          new AtomicInteger());
    }

    /**
     * Validates detached session ownership before publishing it.
     *
     * @param sdk native session state
     * @param owner stable authenticated owner key
     * @param dispatches session-owned native operations
     * @param terminating exactly-once native termination claim
     * @param activity monotonic accepted-activity time
     * @param notifications pending adapter-owned native notifications
     * @throws IllegalArgumentException if the owner is blank
     */
    private Session {
      Objects.requireNonNull(sdk);
      Objects.requireNonNull(owner);
      Objects.requireNonNull(dispatches);
      Objects.requireNonNull(terminating);
      Objects.requireNonNull(activity);
      Objects.requireNonNull(notifications);
      if (owner.isBlank()) {
        throw new IllegalArgumentException("A session owner must not be blank");
      }
    }
  }

  /**
   * Native SDK transport/session ownership, kept separate from the public configuration surface.
   */
  private static final class Provider implements McpStreamableServerTransportProvider, McpWork {
    private final McpJsonMapper mapper;
    private final McpSessionLimits limits;
    private final McpOperationLimits operationLimits;
    private final McpOperationLimits controlLimits;
    private final Map<Disposable, Dispatch> operations;
    private final Map<String, Integer> operationOwners;
    private final Map<String, Integer> controlOwners;
    private final Object workContext;
    private final ExecutorService managed;
    private final Scheduler nativeScheduler;
    private final Set<Disposable> completedOperations;
    private final Set<Disposable> cancelledOperations;
    private final Map<Disposable, Integer> runningDispatches;
    private final Map<Disposable, Thread> dispatchThreads;
    private final Map<Disposable, ScheduledFuture<?>> operationDeadlines;
    private final ScheduledThreadPoolExecutor timer;
    private final Map<Disposable, Set<Execution<?>>> executions;
    private final Map<String, Integer> executionOwners;
    private final Map<Session, Integer> executionSessions;
    private int executionCount;
    private final Map<String, Integer> owners;
    private final Map<Session, Mono<Void>> closing;
    private final Map<Session, Sinks.Empty<Void>> initializing;
    private int allocations;
    private final long idleNanos;
    private @Nullable ScheduledFuture<?> idleExpiry;
    private final Map<String, Session> sessions;
    private final Map<McpStream, Session> streams;
    private McpStreamableServerSession.@Nullable Factory factory;
    private boolean stopping;

    /**
     * Creates protocol serialization and finite session ownership eagerly.
     *
     * @param limits configured session capacity and idle timeout
     * @param operationLimits ordinary operation and managed execution policy
     * @param controlLimits independent reply and cancellation policy
     * @param timer owned deadline scheduler, never used for arbitrary native callbacks
     */
    private Provider(
        McpSessionLimits limits,
        McpOperationLimits operationLimits,
        McpOperationLimits controlLimits,
        ScheduledThreadPoolExecutor timer) {
      this.limits = limits;
      this.operationLimits = operationLimits;
      this.controlLimits = controlLimits;
      operations = new HashMap<>();
      operationOwners = new HashMap<>();
      controlOwners = new HashMap<>();
      workContext = new Object();
      managed =
          Executors.newThreadPerTaskExecutor(
              Thread.ofVirtual().name("shoostr-mcp-work-", 0).factory());
      nativeScheduler = Schedulers.fromExecutorService(managed);
      completedOperations = new HashSet<>();
      cancelledOperations = new HashSet<>();
      runningDispatches = new HashMap<>();
      dispatchThreads = new HashMap<>();
      operationDeadlines = new HashMap<>();
      this.timer = timer;
      executions = new HashMap<>();
      executionOwners = new HashMap<>();
      executionSessions = new HashMap<>();
      idleNanos =
          limits.idleTimeout().compareTo(Duration.ofNanos(Long.MAX_VALUE)) > 0
              ? Long.MAX_VALUE
              : limits.idleTimeout().toNanos();
      owners = new HashMap<>();
      closing = new HashMap<>();
      initializing = new HashMap<>();
      mapper =
          new JacksonMcpJsonMapper(
              JsonMapper.builder()
                  .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .build());
      sessions = new ConcurrentHashMap<>();
      streams = new ConcurrentHashMap<>();
    }

    /**
     * Executes only publishers composed within this capability's active native operation.
     *
     * @param <T> callable result type
     * @param action application work submitted for owned execution
     * @return publisher that validates originating ownership before scheduling the callable
     */
    @Override
    public <T> Mono<T> call(Callable<? extends T> action) {
      Objects.requireNonNull(action);
      return Mono.deferContextual(
          current -> {
            Disposable operation = current.getOrDefault(workContext, null);
            return Mono.create(
                sink -> {
                  Execution<T> execution;

                  try {
                    execution = reserveExecution(operation, action, sink);
                  } catch (RuntimeException failure) {
                    sink.error(failure);
                    return;
                  }

                  sink.onCancel(execution::cancel);

                  try {
                    managed.execute(execution);
                  } catch (RuntimeException failure) {
                    execution.cancel();
                    sink.error(failure);
                  }
                });
          });
    }

    /**
     * Checks ownership and reserves both scoped execution counts before scheduling user work.
     *
     * @param <T> callable result type
     * @param operation originating native subscriber, or null when context is absent
     * @param action application callable
     * @param sink result and cancellation channel
     * @return reserved execution whose lifetime ends only after actual exit or prevented start
     * @throws IllegalStateException if originating ownership is missing or terminated
     * @throws McpError if managed execution capacity is exhausted
     */
    private synchronized <T> Execution<T> reserveExecution(
        @Nullable Disposable operation, Callable<? extends T> action, MonoSink<T> sink) {
      var dispatch = operation == null ? null : operations.get(operation);
      if (stopping
          || dispatch == null
          || dispatch.control()
          || completedOperations.contains(operation)
          || cancelledOperations.contains(operation)) {
        throw new IllegalStateException("Managed MCP work requires an active operation");
      }

      var session = dispatch.session();
      if (executionCount >= operationLimits.maxOperations()
          || executionOwners.getOrDefault(session.owner(), 0)
              >= operationLimits.maxOperationsPerOwner()
          || executionSessions.getOrDefault(session, 0)
              >= operationLimits.maxOperationsPerSession()) {
        throw McpError.builder(-32000).message("MCP managed execution capacity exceeded").build();
      }

      var execution = new Execution<>(Objects.requireNonNull(operation), session, action, sink);
      executionCount++;
      executionOwners.merge(session.owner(), 1, Integer::sum);
      executionSessions.merge(session, 1, Integer::sum);
      executions.computeIfAbsent(operation, ignored -> new HashSet<>()).add(execution);
      return execution;
    }

    /**
     * Starts bounded idle checks after the native server has bound its transport successfully.
     *
     * @param scheduler owned deadline scheduler; never used for native cleanup
     */
    private synchronized void startIdleExpiry(ScheduledThreadPoolExecutor scheduler) {
      long interval = Math.min(idleNanos, TimeUnit.SECONDS.toNanos(1));
      idleExpiry =
          scheduler.scheduleWithFixedDelay(
              this::expireIdle, interval, interval, TimeUnit.NANOSECONDS);
    }

    /** Claims idle ownership before scheduling any native cleanup outside the timer thread. */
    private void expireIdle() {
      List<Session> candidates;
      synchronized (this) {
        if (stopping) {
          return;
        }

        candidates = sessions.values().stream().filter(this::idle).toList();
      }
      for (var session : candidates) {
        cleanup(terminate(session, false, true));
      }
    }

    /**
     * Checks quiescence while the caller holds the admission monitor.
     *
     * @param session current session candidate
     * @return whether accepted activity and all owned native work have been idle long enough
     */
    private boolean idle(Session session) {
      return session.dispatches().size() == 0
          && operations.values().stream().noneMatch(dispatch -> session.equals(dispatch.session()))
          && session.notifications().get() == 0
          && System.nanoTime() - session.activity().get() >= idleNanos;
    }

    /**
     * Reserves a verified owner's capacity before native session allocation.
     *
     * @param owner stable verified owner key
     * @throws ServiceUnavailableException if shutdown has begun or total capacity is exhausted
     * @throws TooManyRequestsException if this owner has reached its capacity
     */
    private synchronized void reserve(String owner) {
      if (stopping || allocations >= limits.maxSessions()) {
        throw new ServiceUnavailableException();
      }

      int count = owners.getOrDefault(owner, 0);
      if (count >= limits.maxSessionsPerOwner()) {
        throw new TooManyRequestsException();
      }

      owners.put(owner, count + 1);
      allocations++;
    }

    /**
     * Releases one allocation after its native cleanup attempt has ended.
     *
     * @param owner verified owner whose reservation terminates
     */
    private synchronized void release(String owner) {
      int count = Objects.requireNonNull(owners.get(owner));
      allocations--;
      if (count == 1) {
        owners.remove(owner);
      } else {
        owners.put(owner, count - 1);
      }

      disposeExecutionIfUnallocated();
    }

    /** Keeps owned cleanup available until even late native factory allocations have returned. */
    private synchronized void disposeExecutionIfUnallocated() {
      if (stopping && allocations == 0) {
        nativeScheduler.dispose();
      }
    }

    /**
     * Accepts exactly one native factory binding during eager server construction.
     *
     * @param sessionFactory native session factory
     * @throws IllegalStateException if the native factory was already bound
     */
    @Override
    public synchronized void setSessionFactory(McpStreamableServerSession.Factory sessionFactory) {
      Objects.requireNonNull(sessionFactory);
      if (factory != null) {
        throw new IllegalStateException("The MCP transport is already bound");
      }

      factory = sessionFactory;
    }

    /**
     * Broadcasts through native SDK sessions, isolating individual delivery failures.
     *
     * @param method native notification method
     * @param params native parameters
     * @return native broadcast completion
     */
    @Override
    public Mono<Void> notifyClients(String method, Object params) {
      return Flux.fromIterable(sessions.values())
          .flatMap(
              session ->
                  notification(session, method, params)
                      .onErrorResume(
                          failure -> {
                            System.getLogger(Mcp.class.getName())
                                .log(
                                    System.Logger.Level.WARNING,
                                    "MCP notification delivery failed",
                                    failure);
                            return Mono.empty();
                          }))
          .then();
    }

    /**
     * Delivers SDK-managed resource updates only to the selected session, if still present.
     *
     * @param sessionId native session identifier
     * @param method native notification method
     * @param params native parameters
     * @return native delivery completion, or empty completion if the session is absent
     */
    @Override
    public Mono<Void> notifyClient(String sessionId, String method, Object params) {
      return Mono.defer(
          () -> {
            var session = sessions.get(sessionId);
            return session == null ? Mono.empty() : notification(session, method, params);
          });
    }

    /**
     * Retains native notification ownership through delivery, failure or cancellation.
     *
     * @param session selected native session
     * @param method native notification method
     * @param params native parameters
     * @return owned delivery completion
     */
    private Mono<Void> notification(Session session, String method, Object params) {
      return Mono.defer(
          () -> {
            synchronized (this) {
              requireActive(session);
              session.notifications().incrementAndGet();
              session.activity().set(System.nanoTime());
            }
            return Mono.defer(() -> session.sdk().sendNotification(method, params))
                .doFinally(ignored8 -> notificationComplete(session));
          });
    }

    /**
     * Starts a fresh idle interval after owned notification work terminates.
     *
     * @param session native notification owner
     */
    private synchronized void notificationComplete(Session session) {
      session.notifications().decrementAndGet();
      session.activity().set(System.nanoTime());
    }

    /**
     * Closes the native sessions owned by this transport provider.
     *
     * @return native close completion
     */
    @Override
    public Mono<Void> closeGracefully() {
      return Mono.defer(
          () -> {
            stop();
            List<Mono<Void>> terminations;
            synchronized (this) {
              List.copyOf(sessions.values()).forEach(session -> terminate(session, false));
              terminations = List.copyOf(closing.values());
            }
            return Flux.fromIterable(terminations)
                .flatMapDelayError(termination -> termination, 32, 1)
                .then()
                .doFinally(ignored -> disposeExecutionIfUnallocated());
          });
    }

    /**
     * Advertises only the released Streamable HTTP protocol revisions.
     *
     * @return supported Streamable HTTP revisions
     */
    @Override
    public List<String> protocolVersions() {
      return List.of("2025-03-26", "2025-06-18", "2025-11-25");
    }

    /**
     * Takes ownership of a provisional session before subscribing to its initialization.
     *
     * @param session allocated native session and detached owner
     * @return replaying cancellation signal for pending initialization
     * @throws ServiceUnavailableException if shutdown won registration
     */
    private Sinks.Empty<Void> provision(Session session) {
      synchronized (this) {
        if (!stopping) {
          var cancellation = Sinks.<Void>empty();
          initializing.put(session, cancellation);
          return cancellation;
        }
      }

      rollback(session);
      throw new ServiceUnavailableException();
    }

    /**
     * Publishes a completed initialization atomically against shutdown and rollback.
     *
     * @param session initialized provisional session
     * @throws ServiceUnavailableException if termination won publication
     */
    private void publish(Session session) {
      synchronized (this) {
        if (!stopping && !session.terminating().get() && initializing.remove(session) != null) {
          session.activity().set(System.nanoTime());
          sessions.put(session.sdk().getId(), session);
          return;
        }
      }

      throw new ServiceUnavailableException();
    }

    /**
     * Admits and attaches a GET atomically against session deletion and application shutdown.
     * Native attachment creates its detached stream, publishes an atomic reference and finishes the
     * previous detached transport, if any. That closure only signals its mailbox; transport I/O and
     * native callbacks run outside this registry monitor.
     *
     * @param session freshly authenticated native session
     * @param stream detached transport
     * @return native listening handle belonging to this HTTP transport
     * @throws ServiceUnavailableException if shutdown already began
     * @throws NotFoundException if the session was deleted before admission
     */
    private synchronized McpStreamableServerSession.McpStreamableServerSessionStream listen(
        Session session, McpStream stream) {
      requireActive(session);
      session.activity().set(System.nanoTime());
      var listening = session.sdk().listeningStream(stream);
      streams.put(stream, session);
      return listening;
    }

    /**
     * Owns native dispatch independently of its HTTP stream until completion or explicit closure.
     * The cancellation-aware subscriber is owned before subscription and connects its cancellation
     * in {@code onSubscribe}, even if the producer has not returned. SDK callbacks never touch a
     * live request or response.
     *
     * @param session authenticated native session
     * @param stream detached HTTP transport
     * @param request native request
     * @param context detached authenticated context
     * @return native stream handle whose HTTP owner releases its transport
     * @throws ServiceUnavailableException if application shutdown has begun
     * @throws NotFoundException if the session was deleted before dispatch admission
     */
    private McpStreamableServerSession.McpStreamableServerSessionStream dispatch(
        Session session,
        McpStream stream,
        McpSchema.JSONRPCRequest request,
        McpTransportContext context) {
      var dispatch =
          new BaseSubscriber<Void>() {
            /**
             * Signals native failure without touching live HTTP output.
             *
             * @param failure native dispatch failure
             */
            @Override
            protected void hookOnError(Throwable failure) {
              stream.fail(failure);
            }

            /**
             * Releases detached ownership after completion, failure or cancellation.
             *
             * @param type native terminal signal
             */
            @Override
            protected void hookFinally(SignalType type) {
              stream.finish();
              dispatchComplete(session, this);
            }
          };
      McpStreamableServerSession.McpStreamableServerSessionStream nativeStream;
      boolean admitted = false;
      boolean attached = false;

      try {
        synchronized (this) {
          admit(session, dispatch, stream, request.id(), false);
          admitted = true;
          nativeStream = session.sdk().responseStream(stream);
          streams.put(stream, session);
          attached = true;
        }
      } finally {
        if (admitted && !attached) {
          abandonDispatch(dispatch);
        }
      }

      boolean subscribed = false;

      try {
        startDispatch(
            dispatch,
            Mono.defer(() -> nativeStream.handle(request))
                .contextWrite(
                    current ->
                        current.put(McpTransportContext.KEY, context).put(workContext, dispatch)));
        subscribed = true;
        return nativeStream;
      } finally {
        if (!subscribed) {
          abandonDispatch(dispatch);
          nativeStream.releaseTransport();
          streams.remove(stream);
          stream.finish();
        }
      }
    }

    /**
     * Owns accepted notifications and replies without making HTTP await application callbacks.
     *
     * @param session freshly authenticated native session
     * @param message incoming notification or response
     * @param context detached authenticated context
     * @throws ServiceUnavailableException if shutdown has begun
     * @throws NotFoundException if the session was deleted before admission
     */
    private void accept(
        Session session, McpSchema.JSONRPCMessage message, McpTransportContext context) {
      var dispatch =
          new BaseSubscriber<Void>() {
            /**
             * Reports asynchronous processing failure after HTTP acceptance.
             *
             * @param failure native processing failure
             */
            @Override
            protected void hookOnError(Throwable failure) {
              System.getLogger(Mcp.class.getName())
                  .log(
                      System.Logger.Level.WARNING,
                      "Accepted MCP message processing failed",
                      failure);
            }

            /**
             * Releases session ownership on completion, failure or cancellation.
             *
             * @param type native terminal signal
             */
            @Override
            protected void hookFinally(SignalType type) {
              dispatchComplete(session, this);
            }
          };
      synchronized (this) {
        boolean control =
            message instanceof McpSchema.JSONRPCResponse
                || (message instanceof McpSchema.JSONRPCNotification notification
                    && "notifications/cancelled".equals(notification.method()));
        admit(session, dispatch, null, null, control);
      }

      boolean subscribed = false;

      try {
        startDispatch(
            dispatch,
            Mono.defer(
                    () -> {
                      if (message instanceof McpSchema.JSONRPCNotification notification) {
                        if ("notifications/cancelled".equals(notification.method())) {
                          cancelRequest(session, notification.params());
                          return Mono.empty();
                        }

                        return session.sdk().accept(notification);
                      }

                      if (message instanceof McpSchema.JSONRPCResponse reply) {
                        return session.sdk().accept(reply);
                      }

                      return Mono.error(new BadRequestException());
                    })
                .contextWrite(
                    current ->
                        current.put(McpTransportContext.KEY, context).put(workContext, dispatch)));
        subscribed = true;
      } finally {
        if (!subscribed) {
          abandonDispatch(dispatch);
        }
      }
    }

    /**
     * Reconciles setup rollback even when disposal precedes the native subscription.
     *
     * @param dispatch admitted subscriber whose attachment or scheduling failed
     */
    private void abandonDispatch(Disposable dispatch) {
      try {
        dispatch.dispose();
      } finally {
        synchronized (this) {
          completeIfDisposed(dispatch);
          releaseOperation(dispatch);
        }
      }
    }

    /**
     * Looks up only this verified session's matching request and cancels outside the registry.
     *
     * @param session freshly verified session
     * @param parameters uncoerced cancellation parameters; malformed targets are ignored
     */
    private void cancelRequest(Session session, @Nullable Object parameters) {
      Map.Entry<Disposable, Dispatch> target;
      synchronized (this) {
        requireActive(session);
        if (!(parameters instanceof Map<?, ?> fields) || !validId(fields.get("requestId"))) {
          return;
        }

        target =
            operations.entrySet().stream()
                .filter(
                    entry ->
                        session.equals(entry.getValue().session())
                            && Objects.equals(fields.get("requestId"), entry.getValue().id()))
                .findFirst()
                .orElse(null);
      }
      if (target != null && Objects.requireNonNull(target.getValue().stream()).cancelResponse()) {
        synchronized (this) {
          if (!operations.containsKey(target.getKey())) {
            return;
          }

          cancelledOperations.add(target.getKey());
        }
        cancelNative(target.getKey());
      }
    }

    /**
     * Removes completed native work and renews quiescence atomically against idle eviction.
     *
     * @param session native operation owner
     * @param dispatch finished native subscriber
     */
    private synchronized void dispatchComplete(Session session, Disposable dispatch) {
      session.dispatches().remove(dispatch);
      if (operations.containsKey(dispatch)) {
        completedOperations.add(dispatch);
        releaseOperation(dispatch);
      }

      session.activity().set(System.nanoTime());
    }

    /**
     * Claims expiry before native cancellation, which runs away from the deadline thread.
     *
     * @param operation admitted subscriber whose absolute deadline elapsed
     */
    private void expireOperation(Disposable operation) {
      Dispatch dispatch;
      synchronized (this) {
        var active = operations.get(operation);
        if (stopping || active == null || cancelledOperations.contains(operation)) {
          return;
        }

        dispatch = active;
        cancelledOperations.add(operation);
      }
      if (dispatch.stream() == null
          || dispatch.stream().timeout(Objects.requireNonNull(dispatch.id()))) {
        cancelNative(operation);
      }
    }

    /**
     * Releases admission only after both native completion and actual managed execution exit.
     *
     * @param operation admitted subscriber checked while holding the provider monitor
     */
    private void releaseOperation(Disposable operation) {
      if (completedOperations.contains(operation)
          && !executions.containsKey(operation)
          && !runningDispatches.containsKey(operation)) {
        var dispatch = Objects.requireNonNull(operations.remove(operation));
        var session = dispatch.session();
        decrement(dispatch.control() ? controlOwners : operationOwners, session.owner());
        var deadline = operationDeadlines.remove(operation);
        if (deadline != null) {
          deadline.cancel(false);
        }

        completedOperations.remove(operation);
        cancelledOperations.remove(operation);
        session.activity().set(System.nanoTime());
      }
    }

    /**
     * Reserves one lane before retaining native work; callers hold the provider monitor.
     *
     * @param session verified native session
     * @param operation cancellation-aware native subscriber
     * @param stream detached response stream, or null for accepted notifications/replies
     * @param id request identifier, or null when no response stream is owned
     * @param control whether independently reserved control capacity applies
     * @throws BadRequestException if a request identifier is already active in this session
     * @throws ServiceUnavailableException if total capacity is exhausted or shutdown has begun
     * @throws TooManyRequestsException if owner or session capacity is exhausted
     * @throws NotFoundException if the session ended before admission
     */
    private void admit(
        Session session,
        Disposable operation,
        @Nullable McpStream stream,
        @Nullable Object id,
        boolean control) {
      requireActive(session);
      if (id != null
          && operations.values().stream()
              .anyMatch(active -> session.equals(active.session()) && id.equals(active.id()))) {
        throw new BadRequestException();
      }

      var policy = control ? controlLimits : operationLimits;
      var counts = control ? controlOwners : operationOwners;
      if (operations.values().stream().filter(active -> active.control() == control).count()
          >= policy.maxOperations()) {
        throw new ServiceUnavailableException();
      }

      if (counts.getOrDefault(session.owner(), 0) >= policy.maxOperationsPerOwner()
          || operations.values().stream()
                  .filter(active -> active.control() == control && session.equals(active.session()))
                  .count()
              >= policy.maxOperationsPerSession()) {
        throw new TooManyRequestsException();
      }

      var dispatch = new Dispatch(session, stream, id, control);
      if (!session.dispatches().add(operation)) {
        throw new NotFoundException();
      }

      operations.put(operation, dispatch);
      counts.merge(session.owner(), 1, Integer::sum);
      boolean scheduled = false;

      try {
        var timeout = policy.timeout();
        long delay =
            timeout.compareTo(Duration.ofNanos(Long.MAX_VALUE)) > 0
                ? Long.MAX_VALUE
                : timeout.toNanos();
        operationDeadlines.put(
            operation,
            timer.schedule(() -> expireOperation(operation), delay, TimeUnit.NANOSECONDS));
        scheduled = true;
      } finally {
        if (!scheduled) {
          operations.remove(operation);
          decrement(counts, session.owner());
          session.dispatches().remove(operation);
        }
      }

      session.activity().set(System.nanoTime());
    }

    /**
     * Owns native subscription execution until its actual runnable returns.
     *
     * @param dispatch already admitted native subscriber
     * @param publisher native execution carrying detached operation context
     */
    private void startDispatch(BaseSubscriber<Void> dispatch, Mono<Void> publisher) {
      synchronized (this) {
        if (!operations.containsKey(dispatch) || cancelledOperations.contains(dispatch)) {
          return;
        }

        runningDispatches.merge(dispatch, 1, Integer::sum);
      }
      boolean scheduled = false;

      try {
        managed.execute(
            () -> {
              try {
                synchronized (this) {
                  if (cancelledOperations.contains(dispatch)) {
                    return;
                  }

                  dispatchThreads.put(dispatch, Thread.currentThread());
                }
                publisher.subscribe(dispatch);
              } finally {
                synchronized (this) {
                  completeIfDisposed(dispatch);
                  dispatchThreads.remove(dispatch);
                  decrement(runningDispatches, dispatch);
                  releaseOperation(dispatch);
                }
              }
            });
        scheduled = true;
      } finally {
        if (!scheduled) {
          synchronized (this) {
            decrement(runningDispatches, dispatch);
            releaseOperation(dispatch);
          }
        }
      }
    }

    /**
     * Signals only owned threads directly; arbitrary native cancellation hooks run separately.
     *
     * @param operation admitted subscriber whose cancellation has been claimed
     */
    private void cancelNative(Disposable operation) {
      Thread thread;
      List<Execution<?>> active;
      synchronized (this) {
        if (!operations.containsKey(operation)) {
          return;
        }

        runningDispatches.merge(operation, 1, Integer::sum);
        thread = dispatchThreads.get(operation);
        active = List.copyOf(executions.getOrDefault(operation, Set.of()));
      }
      if (thread != null) {
        thread.interrupt();
      }

      active.forEach(Execution::cancel);
      boolean scheduled = false;

      try {
        managed.execute(
            () -> {
              try {
                operation.dispose();
              } finally {
                synchronized (this) {
                  completeIfDisposed(operation);
                  decrement(runningDispatches, operation);
                  releaseOperation(operation);
                }
              }
            });
        scheduled = true;
      } catch (RejectedExecutionException failure) {
        synchronized (this) {
          if (!stopping) {
            throw failure;
          }
        }
      } finally {
        if (!scheduled) {
          synchronized (this) {
            decrement(runningDispatches, operation);
            releaseOperation(operation);
          }
        }
      }
    }

    /**
     * Cancellation before native onSubscribe may not invoke the subscriber's final hook.
     *
     * @param operation subscriber checked after owned subscription or cancellation execution
     */
    private void completeIfDisposed(Disposable operation) {
      var dispatch = operations.get(operation);
      if (dispatch != null && operation.isDisposed()) {
        dispatch.session().dispatches().remove(operation);
        completedOperations.add(operation);
      }
    }

    /**
     * Removes zero-valued ownership counts while the provider monitor is held.
     *
     * @param <T> ownership key type
     * @param counts scoped reservations
     * @param key ownership whose reservation terminates
     */
    private static <T> void decrement(Map<T, Integer> counts, T key) {
      int count = Objects.requireNonNull(counts.get(key));
      if (count == 1) {
        counts.remove(key);
      } else {
        counts.put(key, count - 1);
      }
    }

    /** Correlates an admitted request with its detached stream and protocol identifier. */
    private record Dispatch(
        Session session, @Nullable McpStream stream, @Nullable Object id, boolean control) {
      /**
       * Validates detached request-only response ownership before registration.
       *
       * @param session verified session owning the admitted operation
       * @param stream response mailbox, or null for notifications/replies
       * @param id request identifier paired with a response mailbox
       * @param control whether the independent control policy applies
       * @throws IllegalArgumentException if response stream and request identifier do not agree
       */
      private Dispatch {
        Objects.requireNonNull(session);
        if ((stream == null) != (id == null) || (control && stream != null)) {
          throw new IllegalArgumentException("Only ordinary requests own a response stream and ID");
        }
      }
    }

    /**
     * Accounts for actual callable execution independently of its publisher.
     *
     * @param <T> callable result type
     */
    private final class Execution<T> implements Runnable {
      private final Disposable operation;
      private final Session session;
      private final Callable<? extends T> action;
      private final MonoSink<T> sink;
      private @Nullable Thread thread;
      private boolean started;
      private boolean cancelled;
      private boolean released;

      /**
       * Captures detached ownership before the callable can be scheduled.
       *
       * @param operation originating admitted subscriber
       * @param session verified session owning the execution reservation
       * @param action application callable
       * @param sink result and cancellation channel
       */
      private Execution(
          Disposable operation, Session session, Callable<? extends T> action, MonoSink<T> sink) {
        this.operation = Objects.requireNonNull(operation);
        this.session = Objects.requireNonNull(session);
        this.action = Objects.requireNonNull(action);
        this.sink = Objects.requireNonNull(sink);
      }

      /** Returns capacity after the callable exits, before delivering its result downstream. */
      @Override
      @SuppressWarnings(
          "java:S1181") // Relay nonfatal publisher failures; fatal errors still escape.
      public void run() {
        synchronized (Provider.this) {
          if (cancelled) {
            return;
          }

          started = true;
          thread = Thread.currentThread();
        }

        T result;

        try {
          try {
            result = action.call();
          } finally {
            synchronized (Provider.this) {
              thread = null;
              release();
            }
          }
        } catch (Throwable failure) {
          Exceptions.throwIfFatal(failure);
          sink.error(failure);
          return;
        }

        sink.success(result);
      }

      /** Requests interruption after start, or prevents a queued callable from executing. */
      private void cancel() {
        Thread active;
        synchronized (Provider.this) {
          cancelled = true;
          active = thread;
          if (!started) {
            release();
          }
        }
        if (active != null) {
          active.interrupt();
        }
      }

      /** Returns execution capacity once, after actual exit or verified prevention of start. */
      private void release() {
        if (released) {
          return;
        }

        released = true;
        executionCount--;
        decrement(executionOwners, session.owner());
        decrement(executionSessions, session);
        var owned = Objects.requireNonNull(executions.get(operation));
        owned.remove(this);
        if (owned.isEmpty()) {
          executions.remove(operation);
        }

        releaseOperation(operation);
      }
    }

    /**
     * Ends native ownership exactly once, outside registry and observation completion threads.
     *
     * @param session failed initialization whose allocation must be released
     */
    private void rollback(Session session) {
      cleanup(terminate(session, false));
    }

    /**
     * Schedules native cleanup without blocking an observation or deadline scheduler.
     *
     * @param termination already claimed session cleanup
     */
    private void cleanup(Mono<Void> termination) {
      termination
          .subscribeOn(nativeScheduler)
          .subscribe(
              ignored9 -> {},
              failure ->
                  System.getLogger(Mcp.class.getName())
                      .log(System.Logger.Level.WARNING, "MCP session cleanup failed", failure));
    }

    /**
     * Claims registry removal before closing transports, dispatches and native session state.
     *
     * @param session session allocation to release
     * @param deletion whether an explicit DELETE requires current membership
     * @return native cleanup completion
     */
    private Mono<Void> terminate(Session session, boolean deletion) {
      return terminate(session, deletion, false);
    }

    /**
     * Checks an idle candidate and claims termination under the same admission monitor.
     *
     * @param session allocation to terminate
     * @param deletion whether current membership is required by an explicit DELETE
     * @param idle whether eligibility must be rechecked against concurrent activity
     * @return native cleanup completion, without executing native work during the claim
     */
    private Mono<Void> terminate(Session session, boolean deletion, boolean idle) {
      synchronized (this) {
        if (idle && (stopping || !idle(session))) {
          return Mono.empty();
        }

        if (deletion && !session.equals(sessions.get(session.sdk().getId()))) {
          return Mono.error(new NotFoundException());
        }

        if (!session.terminating().compareAndSet(false, true)) {
          return closing.getOrDefault(session, Mono.empty());
        }

        sessions.remove(session.sdk().getId(), session);
        var cancellation = initializing.remove(session);
        var active =
            streams.entrySet().stream()
                .filter(entry -> session.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .toList();
        var termination =
            Mono.fromRunnable(
                    () -> {
                      if (cancellation != null) {
                        cancellation.tryEmitEmpty();
                      }

                      active.forEach(McpStream::finish);
                      session.dispatches().dispose();
                    })
                .then(
                    Mono.defer(
                        () -> deletion ? session.sdk().delete() : session.sdk().closeGracefully()))
                .doFinally(ignored10 -> terminationComplete(session))
                .cache();
        closing.put(session, termination);
        return termination;
      }
    }

    /**
     * Releases native closing ownership and its capacity after the shared cleanup attempt ends.
     *
     * @param session terminated allocation
     */
    private synchronized void terminationComplete(Session session) {
      closing.remove(session);
      release(session.owner());
    }

    /**
     * Checks session membership while the caller holds the admission/deletion monitor.
     *
     * @param session freshly authenticated native session
     * @throws ServiceUnavailableException if application shutdown has begun
     * @throws NotFoundException if the session has been deleted
     */
    private void requireActive(Session session) {
      if (stopping) {
        throw new ServiceUnavailableException();
      }

      if (!session.equals(sessions.get(session.sdk().getId()))) {
        throw new NotFoundException();
      }
    }

    /** Signals active transports promptly without invoking native SDK continuations. */
    private void stop() {
      List<McpStream> active;
      List<Mono<Void>> pending;
      synchronized (this) {
        stopping = true;
        if (idleExpiry != null) {
          idleExpiry.cancel(false);
          idleExpiry = null;
        }

        active = List.copyOf(streams.keySet());
        pending =
            List.copyOf(initializing.keySet()).stream()
                .map(session -> terminate(session, false))
                .toList();
      }
      active.forEach(McpStream::finish);
      pending.forEach(this::cleanup);
    }
  }
}
