package io.github.suppierk.shoostr;

import io.github.suppierk.shoostr.http.HttpCharacters;
import io.github.suppierk.shoostr.http.HttpHeaders;
import io.github.suppierk.shoostr.http.HttpMethods;
import io.github.suppierk.shoostr.http.exceptions.NotFoundException;
import java.io.Closeable;
import java.io.IOException;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.jspecify.annotations.Nullable;

/**
 * A filesystem or classpath directory mounted below one application path. Equality compares stored
 * mount, policy, option and native resource state; it does not compare directory contents. Separate
 * openings retain their resources' equality contracts. Registration updates to behavior can change
 * equality and hash codes; registration ownership is tracked by identity.
 */
final class StaticFiles implements Closeable {
  private static final String BINARY_CONTENT_TYPE = "application/octet-stream";
  private final String mount;
  private @Nullable EndpointBehavior behavior;
  private final StaticOptions options;
  private final boolean filesystem;
  private final ResourceFactory.Closeable resourceFactory;
  private final Resource source;

  /**
   * Validates a filesystem directory mount at registration time.
   *
   * @param mount mounted request path
   * @param directory source directory
   * @throws IllegalArgumentException if the source cannot be resolved or is not a readable
   *     directory
   */
  StaticFiles(String mount, Path directory) {
    this(mount, directory, StaticOptions.defaults());
  }

  /**
   * Creates a filesystem mount with explicit resource options.
   *
   * @param mount mounted request path
   * @param directory source directory
   * @param options welcome-file and SPA fallback options
   * @throws IllegalArgumentException if the directory cannot be mounted safely
   */
  StaticFiles(String mount, Path directory, StaticOptions options) {
    this(mount, options, true, factory -> factory.newResource(realDirectory(directory)));
  }

  /**
   * Validates and fixes one classpath directory mount at registration time.
   *
   * @param mount mounted request path
   * @param directory classpath source directory
   * @throws IllegalArgumentException if the source is unavailable or not a readable directory
   */
  StaticFiles(String mount, String directory) {
    this(mount, directory, StaticOptions.defaults());
  }

  /**
   * Creates a classpath mount with explicit resource options.
   *
   * @param mount mounted request path
   * @param directory source directory
   * @param options welcome-file and SPA fallback options
   * @throws IllegalArgumentException if the directory cannot be mounted safely
   */
  StaticFiles(String mount, String directory, StaticOptions options) {
    this(
        mount,
        options,
        false,
        factory -> factory.newClassLoaderResource(Objects.requireNonNull(directory)));
  }

  /**
   * Creates and validates an owned native source before publishing this mount.
   *
   * @param mount mounted request path
   * @param options welcome-file and fallback options
   * @param filesystem whether response selection opens a native filesystem channel
   * @param resolve factory operation that locates the root directory
   */
  @SuppressWarnings(
      "java:S1181") // Release native resources even when initialization fails fatally.
  private StaticFiles(
      String mount,
      StaticOptions options,
      boolean filesystem,
      Function<ResourceFactory.Closeable, @Nullable Resource> resolve) {
    this.mount = Objects.requireNonNull(mount);
    this.options = Objects.requireNonNull(options);
    this.filesystem = filesystem;
    resourceFactory = ResourceFactory.closeable();

    try {
      var resolved = resolve.apply(resourceFactory);
      if (resolved == null
          || !resolved.exists()
          || !resolved.isDirectory()
          || !resolved.isReadable()) {
        throw new IllegalArgumentException("Static source must be a readable directory");
      }

      source = resolved;
    } catch (RuntimeException | Error failure) {
      try {
        resourceFactory.close();
      } catch (RuntimeException | Error cleanup) {
        failure.addSuppressed(cleanup);
      }

      throw failure;
    }
  }

  /**
   * Compares all stored mount settings and owned resource references.
   *
   * @param other candidate mount
   * @return whether both mounts have equal stored state
   */
  @Override
  public boolean equals(@Nullable Object other) {
    return other instanceof StaticFiles files
        && mount.equals(files.mount)
        && Objects.equals(behavior, files.behavior)
        && options.equals(files.options)
        && filesystem == files.filesystem
        && Objects.equals(resourceFactory, files.resourceFactory)
        && Objects.equals(source, files.source);
  }

  /**
   * Hashes the same mount settings and resource references used by equality.
   *
   * @return hash of the stored mount state
   */
  @Override
  public int hashCode() {
    return Objects.hash(mount, behavior, options, filesystem, resourceFactory, source);
  }

  /**
   * Returns the mounted classpath archive filesystem for lifecycle verification.
   *
   * @return archive filesystem, or null for filesystem mounts and classpath directories
   */
  @Nullable FileSystem archiveFileSystem() {
    var path = source.getPath();
    return path == null || path.getFileSystem().equals(FileSystems.getDefault())
        ? null
        : path.getFileSystem();
  }

  /**
   * Returns a synthetic endpoint for one existing GET or HEAD resource. The resource is opened by
   * the endpoint only after hooks and gates have admitted the request.
   *
   * <p>Sonar rule S4449 is suppressed here because the endpoint record's constructor already marks
   * its optional WebSocket factory, local behavior and executor with JSpecify {@code @Nullable}.
   * Passing null for those components intentionally selects the ordinary HTTP defaults.
   *
   * @param path request path
   * @param method requested HTTP method
   * @return synthetic endpoint, or null when this mount has no matching resource
   */
  @SuppressWarnings("java:S4449")
  RadixRoutes.@Nullable Endpoint endpoint(String path, @Nullable HttpMethods method) {
    if (method != HttpMethods.GET && method != HttpMethods.HEAD) {
      return null;
    }

    var selected = select(path);
    if (selected == null) {
      return null;
    }

    return new RadixRoutes.Endpoint(
        method,
        mount,
        mount,
        (ignored, response) -> serve(selected, response),
        Map.of(),
        -1,
        0,
        null,
        behavior,
        null);
  }

  /**
   * Captures mount-scoped phases during registration, before the router is published.
   *
   * @param behavior immutable mount behavior
   */
  void behavior(@Nullable EndpointBehavior behavior) {
    this.behavior = behavior;
  }

  /**
   * Reports whether the selected protection requires native application sessions.
   *
   * @return whether this mount selects CSRF protection
   */
  boolean requiresSessions() {
    return behavior != null && behavior.csrf() != null;
  }

  /**
   * Finalizes exception renderers inherited through the mount's registration scope at startup.
   *
   * @param errors resolved immutable scope mappings
   */
  void exceptions(Map<Class<? extends Exception>, ExceptionHandler<Exception>> errors) {
    behavior = EndpointBehavior.withErrors(behavior, errors);
  }

  /** Releases the owned native resource factory, including mounted classpath archives. */
  @Override
  public void close() {
    resourceFactory.close();
  }

  /**
   * Resolves and stages a static resource after all route admission callbacks have completed.
   *
   * @param relative validated resource selected during route matching
   * @param response response receiving the selected resource
   * @throws NotFoundException if the resource disappears after route matching
   * @throws IOException if the resource cannot be staged
   */
  private void serve(String relative, Response response) throws IOException {
    if (!filesystem) {
      serveClasspath(relative, response);
      return;
    }

    var selected = filesystemResource(relative);
    if (selected == null) {
      throw new NotFoundException();
    }

    try {
      response
          .setHeader(HttpHeaders.CACHE_CONTROL.value(), "no-cache")
          .resource(
              selected.channel(),
              selected.length(),
              selected.lastModified(),
              contentType(selected.name()));
    } catch (IOException | RuntimeException failure) {
      selected.close();
      throw failure;
    }
  }

  /**
   * Stages one classpath resource selected after route admission.
   *
   * @param relative validated resource path
   * @param response response receiving the selected resource
   * @throws NotFoundException if the resource disappears after route matching
   * @throws IOException if the resource cannot be staged
   */
  private void serveClasspath(String relative, Response response) throws IOException {
    var resource = source.resolve(URIUtil.encodePath(relative));
    if (!resource.exists()
        || resource.isDirectory()
        || !resource.isReadable()
        || resource.isAlias()) {
      throw new NotFoundException();
    }

    response
        .setHeader(HttpHeaders.CACHE_CONTROL.value(), "no-cache")
        .resource(resource, contentType(resource.getFileName()));
  }

  /**
   * Reports whether an existing GET resource is available without opening a file before gates run.
   *
   * @param path request path
   * @return true when this mount contains an existing resource
   */
  boolean exists(String path) {
    return select(path) != null;
  }

  /**
   * Resolves one existing file while retaining the selected target across route admission. Welcome
   * files are checked beneath a requested directory; SPA fallback is checked at the mount root only
   * when no requested file or welcome file exists.
   *
   * @param path request path
   * @return validated relative file path, or null when this mount cannot serve the request
   */
  private @Nullable String select(String path) {
    var requested = relative(path);
    if (requested == null) {
      return null;
    }

    if (!requested.isEmpty()
        && !requested.endsWith(HttpCharacters.PATH_SEPARATOR_STRING)
        && available(requested)) {
      return requested;
    }

    String welcome = options.welcomeFile();
    if (welcome != null) {
      String directory =
          requested.endsWith(HttpCharacters.PATH_SEPARATOR_STRING)
              ? requested.substring(0, requested.length() - 1)
              : requested;
      String candidate =
          directory.isEmpty() ? welcome : directory + HttpCharacters.PATH_SEPARATOR + welcome;
      if (available(candidate)) {
        return candidate;
      }
    }

    String fallback = options.spaFallback();
    return fallback != null && available(fallback) ? fallback : null;
  }

  /**
   * Checks one validated relative file without opening a response-owned descriptor.
   *
   * @param relative validated file path
   * @return true when a readable regular resource exists
   */
  private boolean available(String relative) {
    if (!safe(relative)) {
      return false;
    }

    try {
      var resource = source.resolve(URIUtil.encodePath(relative));
      return readable(resource);
    } catch (InvalidPathException ignored) {
      return false;
    }
  }

  /**
   * Checks a fresh resource without opening its contents. Alias checks reject symbolic links and
   * noncanonical names; they are not atomic with a later open and require a trusted publication
   * tree.
   *
   * @param resource freshly resolved child resource
   * @return whether the selected resource is a readable file within this mount
   */
  private boolean readable(Resource resource) {
    if (!resource.exists()
        || resource.isDirectory()
        || !resource.isReadable()
        || resource.isAlias()
        || !source.contains(resource)) {
      return false;
    }

    var path = resource.getPath();
    return !filesystem || (path != null && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS));
  }

  /**
   * Opens the selected pathname after admission and retains its channel for response completion.
   * Length comes from the channel; modification time is sampled from the trusted pathname.
   *
   * @param relative validated relative resource path
   * @return selected channel and metadata, or null when the resource is unavailable
   * @throws IOException if opening or reading metadata fails
   */
  @SuppressWarnings("java:S1181") // Release the channel if metadata selection fails fatally.
  private @Nullable Selected filesystemResource(String relative) throws IOException {
    var resource = source.resolve(URIUtil.encodePath(relative));
    if (!readable(resource)) {
      return null;
    }

    var path = Objects.requireNonNull(resource.getPath());
    var channel = Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);

    try {
      return new Selected(
          channel,
          channel.size(),
          Files.getLastModifiedTime(path).toInstant(),
          resource.getFileName());
    } catch (IOException | RuntimeException | Error failure) {
      try {
        channel.close();
      } catch (IOException | RuntimeException | Error cleanup) {
        failure.addSuppressed(cleanup);
      }

      throw failure;
    }
  }

  /**
   * Extracts a safe decoded relative resource path from this mount.
   *
   * @param path request path
   * @return validated decoded relative path, or null when it cannot be mounted safely
   */
  private @Nullable String relative(String path) {
    if (!mounted(path)) {
      return null;
    }

    String encoded;
    if (mount.length() == 1) {
      encoded = path.substring(1);
    } else if (path.equals(mount)) {
      encoded = "";
    } else {
      encoded = path.substring(mount.length() + 1);
    }

    String decoded;

    try {
      decoded =
          URLDecoder.decode(
              encoded.replace(HttpCharacters.PLUS_SIGN_STRING, HttpCharacters.PERCENT_ENCODED_PLUS),
              StandardCharsets.UTF_8);
    } catch (IllegalArgumentException ignored5) {
      return null;
    }

    if (decoded.isEmpty()) {
      return decoded;
    }

    if (encoded.endsWith(HttpCharacters.PATH_SEPARATOR_STRING)
        && decoded.endsWith(HttpCharacters.PATH_SEPARATOR_STRING)) {
      return safe(decoded.substring(0, decoded.length() - 1)) ? decoded : null;
    }

    return safe(decoded) ? decoded : null;
  }

  /**
   * Reports whether a request path is at or below this mount without matching sibling prefixes.
   *
   * @param path request path
   * @return true when the path is at or below this mount
   */
  private boolean mounted(String path) {
    return path.equals(mount)
        || (path.startsWith(mount)
            && path.length() > mount.length()
            && (mount.length() == 1
                || path.charAt(mount.length()) == HttpCharacters.PATH_SEPARATOR));
  }

  /**
   * Selects a deterministic MIME type while keeping unknown files safely binary.
   *
   * @param name selected resource filename
   * @return detected or fallback media type
   */
  private static String contentType(String name) {
    return Objects.requireNonNullElse(
        URLConnection.guessContentTypeFromName(name), BINARY_CONTENT_TYPE);
  }

  /**
   * Resolves a configured filesystem root before creating its native resource.
   *
   * @param directory configured publication directory
   * @return canonical directory path
   * @throws IllegalArgumentException if the directory cannot be resolved
   */
  private static Path realDirectory(Path directory) {
    try {
      return Objects.requireNonNull(directory).toRealPath();
    } catch (IOException failure) {
      throw new IllegalArgumentException("Static-file source cannot be resolved", failure);
    }
  }

  /**
   * Rejects traversal, empty segments and platform separators before resource resolution.
   *
   * @param path decoded relative path
   * @return true when the path contains no traversal or platform separator
   */
  private static boolean safe(String path) {
    if (path.indexOf(HttpCharacters.BACKSLASH) >= 0
        || path.indexOf('\u0000') >= 0
        || path.isEmpty()) {
      return false;
    }

    for (var segment : path.split(HttpCharacters.PATH_SEPARATOR_STRING, -1)) {
      if (segment.isEmpty()
          || HttpCharacters.DOT_STRING.equals(segment)
          || HttpCharacters.PARENT_DIRECTORY.equals(segment)) {
        return false;
      }
    }

    return true;
  }

  /** One open filesystem channel and its captured metadata. */
  private record Selected(
      SeekableByteChannel channel, long length, Instant lastModified, String name)
      implements Closeable {
    /**
     * Validates the captured descriptor and metadata before response ownership changes.
     *
     * @param channel open resource descriptor
     * @param length captured byte length
     * @param lastModified captured modification time
     * @param name selected resource name
     * @throws IllegalArgumentException if length is negative
     */
    private Selected {
      Objects.requireNonNull(channel);
      Objects.requireNonNull(lastModified);
      Objects.requireNonNull(name);
      if (length < 0) {
        throw new IllegalArgumentException("Selected resource length cannot be negative");
      }
    }

    /**
     * Releases the descriptor when a response does not take ownership.
     *
     * @throws IOException if the descriptor cannot be closed
     */
    @Override
    public void close() throws IOException {
      channel.close();
    }
  }
}
