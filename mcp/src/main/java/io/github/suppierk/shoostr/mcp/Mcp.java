package io.github.suppierk.shoostr.mcp;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.suppierk.shoostr.ApplicationCallbacks;
import io.github.suppierk.shoostr.Lifecycle;
import io.github.suppierk.shoostr.Request;
import io.github.suppierk.shoostr.Response;
import io.github.suppierk.shoostr.RouteExtension;
import io.github.suppierk.shoostr.Routes;
import io.github.suppierk.shoostr.http.HttpCharacters;
import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.http.HttpMethods;
import io.github.suppierk.shoostr.http.HttpStatusCodes;
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
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerSession.McpStreamableServerSessionInit;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import org.eclipse.jetty.http.HttpField;
import org.jspecify.annotations.Nullable;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.Empty;
import reactor.core.scheduler.Schedulers;

/**
 * Owns one native SDK server and its explicitly mounted Streamable HTTP transport. Configure tools,
 * schemas and capabilities directly through the SDK factory; installation adds no endpoint. The
 * returned native server is borrowed for management and must not be closed independently.
 *
 * @param <S> native SDK server type
 */
public final class Mcp<S> implements RouteExtension<Mcp<S>> {
  private static final MediaType EVENT_STREAM = MediaType.of("text/event-stream");
  private static final String FIELD_METHOD = "method";
  private static final String FIELD_RESULT = "result";
  private static final String FIELD_ERROR = "error";
  private static final String JSONRPC = "jsonrpc";
  private static final String ID = "id";
  private static final String VERSION_2_0 = "2.0";
  private static final String PARAMS = "params";
  private static final String CODE = "code";
  private static final String MESSAGE = "message";
  private static final String MCP_SESSION_ID = "Mcp-Session-Id";

  private final McpHttpSecurity security;
  private final String expiredTokenChallenge;
  private final Provider provider;
  private final S server;
  private final ScheduledThreadPoolExecutor deadlines;
  private final String lifetimeAttribute;
  private final AtomicReference<@Nullable String> mount;

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
    this.security = Objects.requireNonNull(security);
    Objects.requireNonNull(limits);

    final var metadata = security.resourceMetadata();
    this.expiredTokenChallenge =
        "Bearer error=\"invalid_token\""
            + (metadata == null ? "" : ", resource_metadata=\"" + metadata.toASCIIString() + "\"");

    Objects.requireNonNull(factory);

    this.provider = new Provider(limits);
    this.deadlines =
        new ScheduledThreadPoolExecutor(
            1, task -> Thread.ofPlatform().daemon().name("shoostr-mcp-expiry").unstarted(task));

    deadlines.setRemoveOnCancelPolicy(true);
    deadlines.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);

    this.lifetimeAttribute = McpRequestLifetime.class.getName() + "." + UUID.randomUUID();

    this.mount = new AtomicReference<>();
    this.server = createServer(factory);
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
   * Validates the wire envelope before native mapper coercion can erase invalid field types. Native
   * SDK dispatch remains responsible for method-specific parameters.
   *
   * @param fields parsed JSON object
   * @return native message class for a valid envelope, or null if the envelope is invalid
   */
  private static @Nullable Class<? extends McpSchema.JSONRPCMessage> classifyEnvelope(
      Map<?, ?> fields) {
    final var hasId = fields.containsKey(ID);
    if (!VERSION_2_0.equals(fields.get(JSONRPC)) || (hasId && !validId(fields.get(ID)))) {
      return null;
    }

    if (fields.containsKey(FIELD_METHOD)) {
      if (!(fields.get(FIELD_METHOD) instanceof String method)
          || method.isBlank()
          || fields.containsKey(FIELD_RESULT)
          || fields.containsKey(FIELD_ERROR)
          || (fields.containsKey(PARAMS) && !(fields.get(PARAMS) instanceof Map<?, ?>))) {
        return null;
      }

      return hasId ? McpSchema.JSONRPCRequest.class : McpSchema.JSONRPCNotification.class;
    }

    final var hasResult = fields.containsKey(FIELD_RESULT);
    if (!hasId || hasResult == fields.containsKey(FIELD_ERROR)) {
      return null;
    }

    final var validPayload =
        hasResult
            ? fields.get(FIELD_RESULT) instanceof Map<?, ?>
            : validError(fields.get(FIELD_ERROR));

    return validPayload ? McpSchema.JSONRPCResponse.class : null;
  }

  /**
   * Checks that an error response supplies the required wire code and message.
   *
   * @param value original error field
   * @return whether the error object has an integer code and a string message
   */
  private static boolean validError(@Nullable Object value) {
    return value instanceof Map<?, ?> error
        && error.get(CODE) instanceof Integer
        && error.get(MESSAGE) instanceof String;
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
   * Requires the explicit SSE acceptance shared by POST and GET, using core negotiation rules.
   *
   * @param request current HTTP request
   * @param response uncommitted owner-thread response
   * @throws NotAcceptableException if Accept is absent or excludes event streams
   */
  private static void requireAccept(Request request, Response response) {
    if (request.headers(HttpHeaders.ACCEPT.value()).isEmpty()) {
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
    final var values = request.headers(HttpHeaders.CONTENT_TYPE.value());
    if (!values.isEmpty()
        && (values.size() != 1
            || !MediaType.APPLICATION_JSON
                .value()
                .equalsIgnoreCase(HttpField.getValueParameters(values.getFirst(), null)))) {
      throw new UnsupportedMediaTypeException();
    }
  }

  /**
   * Builds the native server and releases owned transport resources if construction fails.
   *
   * @param factory eager native server factory
   * @return non-null server bound to this transport
   * @throws IllegalArgumentException if the factory does not bind the supplied transport
   */
  private S createServer(Function<McpStreamableServerTransportProvider, S> factory) {
    boolean initialized = false;

    try {
      final var nativeServer = Objects.requireNonNull(factory.apply(provider));
      if (provider.factory == null) {
        throw new IllegalArgumentException(
            "The native factory must bind the supplied MCP transport");
      }

      provider.startIdleExpiry(deadlines);

      initialized = true;
      return nativeServer;
    } finally {
      if (!initialized) {
        closeTransport();
      }
    }
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
    provider.claimOwnership();
  }

  /**
   * Contributes the pre-authentication Origin guard without adding HTTP routes.
   *
   * @param callbacks registration-only application callbacks
   */
  @Override
  public void install(ApplicationCallbacks callbacks) {
    callbacks.observe(request -> {
      if (!Objects.equals(mount.get(), request.path())) {
        return ignored -> {};
      }

      final var lifetime = new McpRequestLifetime(deadlines, expiredTokenChallenge);
      request.attribute(lifetimeAttribute, lifetime);

      return lifetime;
    });
    callbacks.beforeResponseFlush((request, response) -> {
      if (Objects.equals(mount.get(), request.path())) {
        lifetime(request).beforeFlush(response.status() == HttpStatusCodes.UNAUTHORIZED.value());
      }
    });
    callbacks.onRequestHeaders((request, ignoredResponse) -> {
      if (Objects.equals(mount.get(), request.path())) {
        validateOrigin(request);
      }
    });
  }

  /**
   * Supplies mount metadata for the protocol routes registered by {@link #routes(Routes)}.
   * Selection establishes shared infrastructure but does not create protocol handlers. One instance
   * supports one literal mount; only the POST registration binds that mount.
   *
   * @param lifecycle protocol endpoint configuration
   * @param inherited parent provider, or null
   * @return this transport instance
   */
  @Override
  public Mcp<S> configure(Lifecycle lifecycle, @Nullable Mcp<S> inherited) {
    lifecycle.onRoute(info -> {
      if (info.method() == HttpMethods.POST) {
        bind(info.pathTemplate());
      }
    });
    return this;
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
   * Native SDK callback scheduling is unchanged. The caller must keep the executor available until
   * the app has closed; Shoostr never shuts it down.
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
    Consumer<Lifecycle> configuration = lifecycle -> lifecycle.withExtension(this);
    Consumer<Lifecycle> installed = lifecycle -> lifecycle.withExtension(this);
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
    closeTransport();
  }

  /** Closes the provider before shutting down the deadline executor. */
  private void closeTransport() {
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
  private void bind(String path) {
    if (path.indexOf(HttpCharacters.OPEN_CURLY_BRACE) >= 0
        || path.indexOf(HttpCharacters.ASTERISK) >= 0) {
      throw new IllegalArgumentException("An MCP mount must be a literal path");
    }

    if (!mount.compareAndSet(null, path)) {
      throw new IllegalStateException("An MCP capability can be mounted only once");
    }
  }

  /**
   * Rejects every malformed or unapproved present Origin before authentication.
   *
   * @param request current request
   * @throws ForbiddenException if a present Origin is malformed or not allowlisted
   */
  private void validateOrigin(Request request) {
    final var values = request.headers(HttpHeaders.ORIGIN.value());
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
    } catch (IllegalArgumentException ignoredFailure) {
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

    final var identity = identity(request);
    byte[] bytes;

    try {
      bytes = request.bodyBytes();
    } catch (IOException | RuntimeException failure) {
      lifetime(request).requireFresh();
      throw failure;
    }

    lifetime(request).requireFresh();
    final var message = decodeMessage(bytes, response);
    if (message == null) {
      return;
    }

    if (message instanceof McpSchema.JSONRPCRequest rpc
        && McpSchema.METHOD_INITIALIZE.equals(rpc.method())) {
      initialize(rpc, identity, response, lifetime(request));
      return;
    }

    final var session = session(request, identity);
    authorize(lifetime(request), identity, message);
    if (message instanceof McpSchema.JSONRPCRequest rpc) {
      streamResponse(
          request,
          response,
          identity,
          stream -> provider.dispatch(session, stream, rpc, identity.context()));
    } else {
      provider.accept(session, message, identity.context());
      response.status(HttpStatusCodes.ACCEPTED.value());
    }
  }

  /**
   * Decodes one strict JSON-RPC message or stages a finite protocol rejection.
   *
   * @param bytes current request body
   * @param response owner-thread response
   * @return decoded SDK message, or null if a rejection was staged
   * @throws IOException if writing the rejection fails
   */
  private McpSchema.@Nullable JSONRPCMessage decodeMessage(byte[] bytes, Response response)
      throws IOException {
    Object decoded;

    try {
      final var body =
          StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
      decoded = provider.mapper.readValue(body, new TypeRef<>() {});
    } catch (IOException ignoredFailure) {
      rejectMessage(response, McpSchema.ErrorCodes.PARSE_ERROR, "Parse error", null);
      return null;
    }

    Object id = null;
    if (decoded instanceof Map<?, ?> fields) {
      final var type = classifyEnvelope(fields);
      if (type != null) {
        try {
          return provider.mapper.convertValue(fields, type);
        } catch (IllegalArgumentException ignoredFailure) {
          // A message the SDK cannot convert gets the same rejection as an invalid envelope.
        }
      }

      id = fields.get(ID);
    }

    rejectMessage(response, McpSchema.ErrorCodes.INVALID_REQUEST, "Invalid Request", id);
    return null;
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
    final var parameters = decodeInitialization(rpc.params());
    if (parameters == null) {
      rejectMessage(response, McpSchema.ErrorCodes.INVALID_PARAMS, "Invalid params", rpc.id());
      return;
    }

    authorize(lifetime, identity, rpc);
    provider.reserve(identity.sessionOwner());
    McpStreamableServerSession.McpStreamableServerSessionInit initialized;
    Session session;
    var constructed = false;

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
      lifetime.initialization(outcome -> {
        if (outcome.statusCode() != HttpStatusCodes.OK.value()
            || outcome.applicationFailure() != null
            || outcome.transportFailure() != null) {
          provider.rollback(session);
        }
      });
      final var cancellation = provider.provision(session);
      final var result = initializationResponse(initialized, cancellation, identity);
      final var body =
          provider.mapper.writeValueAsBytes(McpSchema.JSONRPCResponse.result(rpc.id(), result));
      lifetime.requireFresh();
      provider.publish(session);

      response
          .setHeader(MCP_SESSION_ID, session.sdk.getId())
          .body(MediaType.APPLICATION_JSON.value(), body);
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
   * Checks required wire field types before converting initialization parameters with the SDK
   * mapper.
   *
   * @param parameters uncoerced initialization parameters
   * @return decoded initialization parameters, or null if validation or conversion fails
   */
  private McpSchema.@Nullable InitializeRequest decodeInitialization(@Nullable Object parameters) {
    if (!(parameters instanceof Map<?, ?> fields)
        || !(fields.get("protocolVersion") instanceof String)
        || !(fields.get("capabilities") instanceof Map<?, ?>)
        || !(fields.get("clientInfo") instanceof Map<?, ?> client)
        || !(client.get("name") instanceof String)
        || !(client.get("version") instanceof String)) {
      return null;
    }

    try {
      return provider.mapper.convertValue(fields, McpSchema.InitializeRequest.class);
    } catch (IllegalArgumentException ignoredFailure) {
      return null;
    }
  }

  /**
   * Awaits native initialization with the authenticated context and cancellation signal.
   *
   * @param initialized allocated native session and initialization result
   * @param cancellation signal that stops initialization or termination work
   * @param identity detached verified HTTP identity
   * @return completed native initialization result
   */
  private InitializeResult initializationResponse(
      McpStreamableServerSessionInit initialized, Empty<Void> cancellation, McpIdentity identity) {
    return Objects.requireNonNull(
        initialized
            .initResult()
            .takeUntilOther(cancellation.asMono())
            .switchIfEmpty(Mono.error(new ServiceUnavailableException()))
            .contextWrite(context -> context.put(McpTransportContext.KEY, identity.context()))
            .block());
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
    final var envelope = new HashMap<String, Object>();
    envelope.put(JSONRPC, VERSION_2_0);
    envelope.put(FIELD_ERROR, Map.of(CODE, code, MESSAGE, message));
    if (validId(id)) {
      envelope.put(ID, id);
    }

    response
        .status(HttpStatusCodes.BAD_REQUEST.value())
        .body(MediaType.APPLICATION_JSON.value(), provider.mapper.writeValueAsBytes(envelope));
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
    final var identity = identity(request);
    final var session = session(request, identity);

    streamResponse(request, response, identity, stream -> provider.listen(session, stream));
  }

  /**
   * Attaches a mailbox to the request, opens the native transport and pumps HTTP output.
   *
   * @param request current authenticated request
   * @param response owner-thread response
   * @param identity verified identity whose expiry bounds HTTP output
   * @param openStream native operation that opens the transport for the mailbox
   * @throws IOException if streaming fails
   */
  private void streamResponse(
      Request request,
      Response response,
      McpIdentity identity,
      Function<McpStream, McpStreamableServerSession.McpStreamableServerSessionStream> openStream)
      throws IOException {
    final var stream = new McpStream(provider.mapper, identity.expiresAt());
    lifetime(request).stream(stream);
    final var nativeStream = openStream.apply(stream);

    try {
      stream.pump(response);
    } finally {
      provider.releaseStream(stream, nativeStream);
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
    final var identity = identity(request);
    final var session = session(request, identity);
    lifetime(request).requireFresh();
    provider
        .terminate(session, TerminationReason.DELETE)
        .contextWrite(context -> context.put(McpTransportContext.KEY, identity.context()))
        .block();
    response.status(HttpStatusCodes.OK.value());
  }

  /**
   * Rejects unsupported or ambiguous explicit versions without rejecting a legacy absent header.
   * The native initialization handler negotiates its body version separately.
   *
   * @param request current HTTP request
   * @throws BadRequestException if the version is duplicated or unsupported
   */
  private void validateProtocolVersion(Request request) {
    final var versions = request.headers("MCP-Protocol-Version");
    if (!versions.isEmpty()
        && (versions.size() != 1 || !provider.protocolVersions().contains(versions.getFirst()))) {
      throw new BadRequestException("Unsupported MCP protocol version");
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
    final var identity = Objects.requireNonNull(security.identity().apply(request));
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
    final var values = request.headers(MCP_SESSION_ID);
    if (values.size() != 1 || values.getFirst().isBlank()) {
      throw new BadRequestException();
    }

    final var session = provider.sessions.get(values.getFirst());
    if (session == null || !session.owner.equals(identity.sessionOwner())) {
      throw new NotFoundException();
    }

    return session;
  }

  /** Uses object identity for one live session allocation and its detached owner. */
  private static final class Session {
    private final McpStreamableServerSession sdk;
    private final String owner;
    private final Disposable.Composite dispatches;

    // The provider lock guards termination and idle bookkeeping after construction.
    private boolean terminating;
    private long lastActivityNanos;
    private int pendingNotifications;

    /**
     * Validates detached session ownership before publishing it.
     *
     * @param sdk native session state
     * @param owner stable authenticated owner key
     * @throws IllegalArgumentException if the owner is blank
     */
    private Session(McpStreamableServerSession sdk, String owner) {
      this.sdk = Objects.requireNonNull(sdk);
      this.owner = Objects.requireNonNull(owner);
      if (owner.isBlank()) {
        throw new IllegalArgumentException("A session owner must not be blank");
      }

      this.dispatches = Disposables.composite();
      this.lastActivityNanos = System.nanoTime();
    }
  }

  /** Determines the admission check and native operation for session termination. */
  private enum TerminationReason {
    DELETE,
    IDLE_EXPIRY,
    CLOSE
  }

  /**
   * Native SDK transport/session ownership, kept separate from the public configuration surface.
   */
  private static final class Provider implements McpStreamableServerTransportProvider {
    private static final int SESSION_CLOSE_CONCURRENCY = 32;
    private static final int SESSION_CLOSE_PREFETCH = 1;

    private final ReentrantLock lock;
    private final McpJsonMapper mapper;
    private final McpSessionLimits limits;
    private final Map<String, Integer> owners;
    private final Map<Session, Mono<Void>> closing;
    private final Map<Session, Sinks.Empty<Void>> initializing;
    private final long idleNanos;
    private final Map<String, Session> sessions;
    private final Map<McpStream, Session> streams;
    private int allocations;
    private @Nullable ScheduledFuture<?> idleExpiry;
    private McpStreamableServerSession.@Nullable Factory factory;
    private boolean claimed;
    private boolean stopping;

    /**
     * Creates protocol serialization and finite session ownership eagerly.
     *
     * @param limits configured session capacity and idle timeout
     */
    private Provider(McpSessionLimits limits) {
      this.lock = new ReentrantLock();
      this.limits = new McpSessionLimits(limits);
      this.idleNanos =
          this.limits.idleTimeout().compareTo(Duration.ofNanos(Long.MAX_VALUE)) > 0
              ? Long.MAX_VALUE
              : this.limits.idleTimeout().toNanos();
      this.owners = new HashMap<>();
      this.closing = new HashMap<>();
      this.initializing = new HashMap<>();
      this.mapper =
          new JacksonMcpJsonMapper(
              JsonMapper.builder()
                  .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .build());
      this.sessions = new ConcurrentHashMap<>();
      this.streams = new ConcurrentHashMap<>();
    }

    /**
     * Records one application claim while the transport is open.
     *
     * @throws IllegalStateException if the provider is stopping or already claimed
     */
    private void claimOwnership() {
      lock.lock();

      try {
        if (stopping || claimed) {
          throw new IllegalStateException(
              "An MCP capability must be open and can belong to only one application");
        }

        claimed = true;
      } finally {
        lock.unlock();
      }
    }

    /**
     * Starts bounded idle checks after the native server has bound its transport successfully.
     *
     * @param scheduler owned deadline scheduler; never used for native cleanup
     */
    private void startIdleExpiry(ScheduledThreadPoolExecutor scheduler) {
      lock.lock();

      try {
        final var interval = Math.min(idleNanos, TimeUnit.SECONDS.toNanos(1));
        idleExpiry =
            scheduler.scheduleWithFixedDelay(
                this::expireIdle, interval, interval, TimeUnit.NANOSECONDS);
      } finally {
        lock.unlock();
      }
    }

    /** Claims idle ownership before scheduling any native cleanup outside the timer thread. */
    private void expireIdle() {
      List<Session> candidates;
      lock.lock();

      try {
        if (stopping) {
          return;
        }

        candidates = sessions.values().stream().filter(this::idle).toList();
      } finally {
        lock.unlock();
      }

      for (final var session : candidates) {
        cleanup(terminate(session, TerminationReason.IDLE_EXPIRY));
      }
    }

    /**
     * Checks quiescence while the caller holds the admission lock.
     *
     * @param session current session candidate
     * @return whether accepted activity and all owned native work have been idle long enough
     */
    private boolean idle(Session session) {
      return session.dispatches.size() == 0
          && session.pendingNotifications == 0
          && System.nanoTime() - session.lastActivityNanos >= idleNanos;
    }

    /**
     * Reserves a verified owner's capacity before native session allocation.
     *
     * @param owner stable verified owner key
     * @throws ServiceUnavailableException if shutdown has begun or total capacity is exhausted
     * @throws TooManyRequestsException if this owner has reached its capacity
     */
    private void reserve(String owner) {
      lock.lock();

      try {
        if (stopping || allocations >= limits.maxSessions()) {
          throw new ServiceUnavailableException();
        }

        final int count = owners.getOrDefault(owner, 0);
        if (count >= limits.maxSessionsPerOwner()) {
          throw new TooManyRequestsException();
        }

        owners.put(owner, count + 1);
        allocations++;
      } finally {
        lock.unlock();
      }
    }

    /**
     * Releases one allocation after its native cleanup attempt has ended.
     *
     * @param owner verified owner whose reservation terminates
     */
    private void release(String owner) {
      lock.lock();

      try {
        final int count = Objects.requireNonNull(owners.get(owner));
        allocations--;
        if (count == 1) {
          owners.remove(owner);
        } else {
          owners.put(owner, count - 1);
        }
      } finally {
        lock.unlock();
      }
    }

    /**
     * Accepts exactly one native factory binding during eager server construction.
     *
     * @param sessionFactory native session factory
     * @throws IllegalStateException if the native factory was already bound
     */
    @Override
    public void setSessionFactory(McpStreamableServerSession.Factory sessionFactory) {
      lock.lock();

      try {
        Objects.requireNonNull(sessionFactory);
        if (factory != null) {
          throw new IllegalStateException("The MCP transport is already bound");
        }

        factory = sessionFactory;
      } finally {
        lock.unlock();
      }
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
          .flatMap(session ->
              notification(session, method, params)
                  .onErrorResume(failure -> {
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
      return Mono.defer(() -> {
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
      return Mono.defer(() -> {
        lock.lock();

        try {
          requireActive(session);
          session.pendingNotifications++;
          session.lastActivityNanos = System.nanoTime();
        } finally {
          lock.unlock();
        }

        return Mono.defer(() -> session.sdk.sendNotification(method, params))
            .doFinally(ignoredSignalType -> notificationComplete(session));
      });
    }

    /**
     * Starts a fresh idle interval after owned notification work terminates.
     *
     * @param session native notification owner
     */
    private void notificationComplete(Session session) {
      lock.lock();

      try {
        session.pendingNotifications--;
        session.lastActivityNanos = System.nanoTime();
      } finally {
        lock.unlock();
      }
    }

    /**
     * Closes the native sessions owned by this transport provider.
     *
     * @return native close completion
     */
    @Override
    public Mono<Void> closeGracefully() {
      return Mono.defer(() -> {
        stop();
        List<Mono<Void>> terminations;
        lock.lock();

        try {
          List.copyOf(sessions.values())
              .forEach(session -> claimTermination(session, TerminationReason.CLOSE));
          terminations = List.copyOf(closing.values());
        } finally {
          lock.unlock();
        }

        return Flux.fromIterable(terminations)
            .flatMapDelayError(
                termination -> termination, SESSION_CLOSE_CONCURRENCY, SESSION_CLOSE_PREFETCH)
            .then();
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
      lock.lock();

      try {
        if (!stopping) {
          final var cancellation = Sinks.<Void>empty();
          initializing.put(session, cancellation);

          return cancellation;
        }
      } finally {
        lock.unlock();
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
      lock.lock();

      try {
        if (!stopping && !session.terminating && initializing.remove(session) != null) {
          session.lastActivityNanos = System.nanoTime();
          sessions.put(session.sdk.getId(), session);
          return;
        }
      } finally {
        lock.unlock();
      }

      throw new ServiceUnavailableException();
    }

    /**
     * Admits and attaches a GET atomically against session deletion and application shutdown.
     * Native attachment creates its detached stream, publishes an atomic reference and finishes the
     * previous detached transport, if any. That closure only signals its mailbox; transport I/O and
     * native callbacks run outside this registry lock.
     *
     * @param session freshly authenticated native session
     * @param stream detached transport
     * @return native listening handle belonging to this HTTP transport
     * @throws ServiceUnavailableException if shutdown already began
     * @throws NotFoundException if the session was deleted before admission
     */
    private McpStreamableServerSession.McpStreamableServerSessionStream listen(
        Session session, McpStream stream) {
      lock.lock();

      try {
        requireActive(session);
        session.lastActivityNanos = System.nanoTime();
        final var listening = session.sdk.listeningStream(stream);
        streams.put(stream, session);

        return listening;
      } finally {
        lock.unlock();
      }
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
      final var dispatch = dispatchSubscriber(session, stream);
      McpStreamableServerSession.McpStreamableServerSessionStream nativeStream;
      lock.lock();

      try {
        requireActive(session);
        nativeStream = session.sdk.responseStream(stream);
        session.dispatches.add(dispatch);

        session.lastActivityNanos = System.nanoTime();

        streams.put(stream, session);
      } finally {
        lock.unlock();
      }

      boolean subscribed = false;

      try {
        subscribeDispatch(nativeStream.handle(request), context, dispatch);

        subscribed = true;
        return nativeStream;
      } finally {
        if (!subscribed) {
          dispatch.dispose();
          releaseStream(stream, nativeStream);
          stream.finish();
        }
      }
    }

    /**
     * Releases the native transport before removing its mailbox from the registry.
     *
     * @param stream registered mailbox
     * @param nativeStream native handle for the same transport
     */
    private void releaseStream(
        McpStream stream,
        McpStreamableServerSession.McpStreamableServerSessionStream nativeStream) {
      nativeStream.releaseTransport();
      streams.remove(stream);
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
      final var dispatch = dispatchSubscriber(session, null);
      lock.lock();

      try {
        requireActive(session);
        if (!session.dispatches.add(dispatch)) {
          throw new NotFoundException();
        }

        session.lastActivityNanos = System.nanoTime();
      } finally {
        lock.unlock();
      }

      boolean subscribed = false;

      try {
        subscribeDispatch(
            Mono.defer(() -> {
              if (message instanceof McpSchema.JSONRPCNotification notification) {
                return session.sdk.accept(notification);
              }

              if (message instanceof McpSchema.JSONRPCResponse reply) {
                return session.sdk.accept(reply);
              }

              return Mono.error(new BadRequestException());
            }),
            context,
            dispatch);
        subscribed = true;
      } finally {
        if (!subscribed) {
          dispatch.dispose();
        }
      }
    }

    /**
     * Subscribes owned native work on the bounded-elastic scheduler with its authenticated context.
     *
     * @param operation native work to subscribe to
     * @param context detached authenticated context
     * @param dispatch subscriber already owned by the session
     */
    private static void subscribeDispatch(
        Mono<Void> operation, McpTransportContext context, BaseSubscriber<Void> dispatch) {
      operation
          .contextWrite(current -> current.put(McpTransportContext.KEY, context))
          .subscribeOn(Schedulers.boundedElastic())
          .subscribe(dispatch);
    }

    /**
     * Tracks an accepted session operation until completion, failure or cancellation.
     *
     * @param session native session whose work is being observed
     * @param stream detached HTTP mailbox for a streamed request, or null for a notification or
     *     reply
     * @return session completion subscriber
     */
    private BaseSubscriber<Void> dispatchSubscriber(Session session, @Nullable McpStream stream) {
      return new BaseSubscriber<Void>() {
        /**
         * Signals the mailbox or logs a failure for a message accepted without an event stream.
         *
         * @param failure native processing failure
         */
        @Override
        protected void hookOnError(Throwable failure) {
          if (stream != null) {
            stream.fail(failure);
            return;
          }

          System.getLogger(Mcp.class.getName())
              .log(System.Logger.Level.WARNING, "Accepted MCP message processing failed", failure);
        }

        /**
         * Releases session ownership on completion, failure or cancellation.
         *
         * @param type native terminal signal
         */
        @Override
        protected void hookFinally(SignalType type) {
          if (stream != null) {
            stream.finish();
          }

          dispatchComplete(session, this);
        }
      };
    }

    /**
     * Removes completed native work and renews quiescence atomically against idle eviction.
     *
     * @param session native operation owner
     * @param dispatch finished native subscriber
     */
    private void dispatchComplete(Session session, Disposable dispatch) {
      lock.lock();

      try {
        if (session.dispatches.remove(dispatch)) {
          session.lastActivityNanos = System.nanoTime();
        }
      } finally {
        lock.unlock();
      }
    }

    /**
     * Ends native ownership exactly once, outside registry and observation completion threads.
     *
     * @param session failed initialization whose allocation must be released
     */
    private void rollback(Session session) {
      cleanup(terminate(session, TerminationReason.CLOSE));
    }

    /**
     * Schedules native cleanup without blocking an observation or deadline scheduler.
     *
     * @param termination already claimed session cleanup
     */
    private void cleanup(Mono<Void> termination) {
      termination
          .subscribeOn(Schedulers.boundedElastic())
          .subscribe(
              ignoredVoid -> {},
              failure ->
                  System.getLogger(Mcp.class.getName())
                      .log(System.Logger.Level.WARNING, "MCP session cleanup failed", failure));
    }

    /**
     * Checks eligibility and claims termination under the same admission lock.
     *
     * @param session allocation to terminate
     * @param reason why the session is terminating
     * @return native cleanup completion, without executing native work during the claim
     */
    private Mono<Void> terminate(Session session, TerminationReason reason) {
      lock.lock();

      try {
        return claimTermination(session, reason);
      } finally {
        lock.unlock();
      }
    }

    /**
     * Claims termination while the caller holds the provider lock.
     *
     * @param session allocation to terminate
     * @param reason why the session is terminating
     * @return deferred native cleanup completion
     */
    private Mono<Void> claimTermination(Session session, TerminationReason reason) {
      if (reason == TerminationReason.IDLE_EXPIRY && (stopping || !idle(session))) {
        return Mono.empty();
      }

      if (reason == TerminationReason.DELETE && session != sessions.get(session.sdk.getId())) {
        return Mono.error(new NotFoundException());
      }

      if (session.terminating) {
        return closing.getOrDefault(session, Mono.empty());
      }

      session.terminating = true;
      sessions.remove(session.sdk.getId(), session);
      final var cancellation = initializing.remove(session);
      final var active =
          streams.entrySet().stream()
              .filter(entry -> session == entry.getValue())
              .map(Map.Entry::getKey)
              .toList();
      final var termination = terminationPublisher(cancellation, active, session, reason);
      closing.put(session, termination);

      return termination;
    }

    /**
     * Builds deferred session termination while retaining the existing cleanup sequence.
     *
     * @param cancellation signal that stops initialization or termination work
     * @param active streams retained for termination cleanup
     * @param session native session whose work is being observed
     * @param reason why the session is terminating
     * @return termination publisher cached by the session
     */
    private Mono<Void> terminationPublisher(
        @Nullable Empty<Void> cancellation,
        List<McpStream> active,
        Session session,
        TerminationReason reason) {
      return Mono.fromRunnable(
              () -> {
                if (cancellation != null) {
                  cancellation.tryEmitEmpty();
                }

                active.forEach(McpStream::finish);
                session.dispatches.dispose();
              })
          .then(
              Mono.defer(() ->
                  reason == TerminationReason.DELETE
                      ? session.sdk.delete()
                      : session.sdk.closeGracefully()))
          .doFinally(ignoredSignalType -> terminationComplete(session))
          .cache();
    }

    /**
     * Releases native closing ownership and its capacity after the shared cleanup attempt ends.
     *
     * @param session terminated allocation
     */
    private void terminationComplete(Session session) {
      lock.lock();

      try {
        closing.remove(session);
        release(session.owner);
      } finally {
        lock.unlock();
      }
    }

    /**
     * Checks session membership while the caller holds the admission/deletion lock.
     *
     * @param session freshly authenticated native session
     * @throws ServiceUnavailableException if application shutdown has begun
     * @throws NotFoundException if the session has been deleted
     */
    private void requireActive(Session session) {
      if (stopping) {
        throw new ServiceUnavailableException();
      }

      if (session != sessions.get(session.sdk.getId())) {
        throw new NotFoundException();
      }
    }

    /** Signals active transports promptly without invoking native SDK continuations. */
    private void stop() {
      List<McpStream> active;
      List<Mono<Void>> pending;
      lock.lock();

      try {
        stopping = true;
        if (idleExpiry != null) {
          idleExpiry.cancel(false);
          idleExpiry = null;
        }

        active = List.copyOf(streams.keySet());
        pending =
            List.copyOf(initializing.keySet()).stream()
                .map(session -> claimTermination(session, TerminationReason.CLOSE))
                .toList();
      } finally {
        lock.unlock();
      }

      active.forEach(McpStream::finish);

      pending.forEach(this::cleanup);
    }
  }
}
