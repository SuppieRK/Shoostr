package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.beans.Introspector;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.function.Consumer;
import java.util.stream.Stream;
import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class OptionsTest {
  @Test
  void satisfiesEqualsAndHashCodeContract() {
    EqualsVerifier.forClass(Options.class).suppress(Warning.NONFINAL_FIELDS).verify();
  }

  @Test
  void comparesNestedMultipartSettingsByTheirCurrentValues() {
    var first = new Options();
    var second = new Options();

    assertNotSame(first.getMultipart(), second.getMultipart());
    assertEquals(first, second);
    assertEquals(first.hashCode(), second.hashCode());

    second.getMultipart().setMaxParts(7);

    assertNotEquals(first, second);

    second.getMultipart().setMaxParts(null);

    assertEquals(first, second);
    assertEquals(first.hashCode(), second.hashCode());
  }

  private static Stream<Arguments> nullResets() {
    return Stream.of(
        Arguments.of("host", (Consumer<Options>) o -> o.setHost(null), "0.0.0.0"),
        Arguments.of("port", (Consumer<Options>) o -> o.setPort(null), 8080),
        Arguments.of(
            "maxRequestBytes", (Consumer<Options>) o -> o.setMaxRequestBytes(null), 1_048_576),
        Arguments.of(
            "maxResponseBytes", (Consumer<Options>) o -> o.setMaxResponseBytes(null), 1_048_576),
        Arguments.of(
            "maxRequestHeaderBytes",
            (Consumer<Options>) o -> o.setMaxRequestHeaderBytes(null),
            8192),
        Arguments.of(
            "idleTimeout", (Consumer<Options>) o -> o.setIdleTimeout(null), Duration.ofSeconds(30)),
        Arguments.of(
            "shutdownTimeout",
            (Consumer<Options>) o -> o.setShutdownTimeout(null),
            Duration.ofSeconds(5)),
        Arguments.of("http2", (Consumer<Options>) o -> o.setHttp2(null), false),
        Arguments.of("compression", (Consumer<Options>) o -> o.setCompression(null), false),
        Arguments.of("maxParameters", (Consumer<Options>) o -> o.setMaxParameters(null), 1000),
        Arguments.of("multipart", (Consumer<Options>) o -> o.setMultipart(null), null),
        Arguments.of(
            "streamBufferBytes", (Consumer<Options>) o -> o.setStreamBufferBytes(null), 8192),
        Arguments.of("virtualThreads", (Consumer<Options>) o -> o.setVirtualThreads(null), false));
  }

  private static Stream<Arguments> invalidMutations() {
    return Stream.<Consumer<Options>>of(
            o -> o.setHost(""),
            o -> o.setPort(-1),
            o -> o.setPort(65536),
            o -> o.setMaxRequestBytes(0),
            o -> o.setMaxRequestBytes(-1),
            o -> o.setMaxRequestBytes(Integer.MAX_VALUE),
            o -> o.setMaxResponseBytes(0),
            o -> o.setMaxResponseBytes(-1),
            o -> o.setMaxRequestHeaderBytes(0),
            o -> o.setMaxRequestHeaderBytes(-1),
            o -> o.setMaxParameters(0),
            o -> o.setMaxParameters(-1),
            o -> o.setStreamBufferBytes(0),
            o -> o.setStreamBufferBytes(-1),
            o -> o.setShutdownTimeout(Duration.ZERO))
        .map(Arguments::of);
  }

  private static Stream<Duration> invalidTimeouts() {
    return Stream.of(
        Duration.ZERO,
        Duration.ofMillis(-1),
        Duration.ofNanos(1),
        Duration.ofNanos(1_000_001),
        Duration.ofSeconds(Long.MAX_VALUE));
  }

  private static Stream<Duration> validTimeouts() {
    return Stream.of(Duration.ofMillis(1), Duration.ofMillis(Long.MAX_VALUE));
  }

  @Test
  void initializesEveryServingDefaultOnNewInstance() {
    var options = new Options();

    assertEquals("0.0.0.0", options.getHost());
    assertEquals(8080, options.getPort());
    assertEquals(1_048_576, options.getMaxRequestBytes());
    assertEquals(1_048_576, options.getMaxResponseBytes());
    assertEquals(8192, options.getMaxRequestHeaderBytes());
    assertEquals(Duration.ofSeconds(30), options.getIdleTimeout());
    assertEquals(Duration.ofSeconds(5), options.getShutdownTimeout());
    assertFalse(options.getHttp2());
    assertFalse(options.getCompression());
    assertEquals(1000, options.getMaxParameters());
    assertDefaultMultipart(options.getMultipart());
    assertEquals(8192, options.getStreamBufferBytes());
    assertFalse(options.getVirtualThreads());
  }

  @Test
  void configuresExplicitLimitsWithoutChangingTheServingDefaults() {
    var multipart = new MultipartOptions();
    multipart.setMaxFileBytes(500L);
    multipart.setMaxBytes(1000L);
    multipart.setMaxParts(7);
    multipart.setMaxPartHeadersBytes(256);
    multipart.setMaxMemoryPartBytes(64L);
    var options = new Options();
    options.setMaxRequestBytes(64);
    options.setMaxResponseBytes(128);
    options.setStreamBufferBytes(32);
    options.setMaxParameters(4);
    options.setMultipart(multipart);

    assertEquals(64, options.getMaxRequestBytes());
    assertEquals(128, options.getMaxResponseBytes());
    assertEquals(32, options.getStreamBufferBytes());
    assertEquals(4, options.getMaxParameters());
    assertEquals(multipart, options.getMultipart());
    assertEquals("0.0.0.0", options.getHost());
    assertEquals(8080, options.getPort());
    assertEquals(8192, options.getMaxRequestHeaderBytes());
    assertEquals(Duration.ofSeconds(30), options.getIdleTimeout());
    assertEquals(Duration.ofSeconds(5), options.getShutdownTimeout());
    assertFalse(options.getHttp2());
    assertFalse(options.getCompression());
    assertFalse(options.getVirtualThreads());
  }

  @Test
  void acceptsTheSmallestPositiveResourceLimits() {
    var options = new Options();
    options.setMaxRequestBytes(1);
    options.setMaxResponseBytes(1);
    options.setMaxRequestHeaderBytes(1);
    options.setMaxParameters(1);
    options.setStreamBufferBytes(1);

    assertEquals(1, options.getMaxRequestBytes());
    assertEquals(1, options.getMaxResponseBytes());
    assertEquals(1, options.getMaxRequestHeaderBytes());
    assertEquals(1, options.getMaxParameters());
    assertEquals(1, options.getStreamBufferBytes());
  }

  @ParameterizedTest
  @MethodSource("invalidMutations")
  void rejectedMutationsPreserveThePreviousValidSettings(Consumer<Options> mutation) {
    var options = new Options();
    options.setMaxRequestBytes(64);
    options.setMaxResponseBytes(128);
    options.setStreamBufferBytes(32);
    options.setMaxParameters(4);
    options.setMultipart(new MultipartOptions());
    options.setHost("127.0.0.1");
    options.setPort(9090);
    options.setMaxRequestHeaderBytes(1024);
    options.setIdleTimeout(Duration.ofSeconds(11));
    options.setShutdownTimeout(Duration.ofSeconds(2));

    assertThrows(IllegalArgumentException.class, () -> mutation.accept(options));
    assertEquals("127.0.0.1", options.getHost());
    assertEquals(9090, options.getPort());
    assertEquals(64, options.getMaxRequestBytes());
    assertEquals(128, options.getMaxResponseBytes());
    assertEquals(1024, options.getMaxRequestHeaderBytes());
    assertEquals(Duration.ofSeconds(11), options.getIdleTimeout());
    assertEquals(Duration.ofSeconds(2), options.getShutdownTimeout());
    assertEquals(4, options.getMaxParameters());
    assertDefaultMultipart(options.getMultipart());
    assertEquals(32, options.getStreamBufferBytes());
  }

  @ParameterizedTest
  @EmptySource
  @ValueSource(strings = {" ", "\t", "\n"})
  void rejectsEmptyOrBlankHosts(String host) {
    var options = new Options();

    assertThrows(IllegalArgumentException.class, () -> options.setHost(host));
    assertEquals("0.0.0.0", options.getHost());
  }

  @Test
  void leavesHostNamesUnresolvedUntilApplicationStartup() {
    var options = new Options();
    options.setHost("unresolved.invalid");

    assertEquals("unresolved.invalid", options.getHost());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 65535})
  void acceptsEphemeralAndMaximumPorts(int port) {
    var options = new Options();
    options.setPort(port);

    assertEquals(port, options.getPort());
  }

  @ParameterizedTest
  @MethodSource("invalidTimeouts")
  void rejectsTimeoutsThatCannotExpressPositiveWholeMilliseconds(Duration timeout) {
    var options = new Options();

    assertThrows(IllegalArgumentException.class, () -> options.setIdleTimeout(timeout));
    assertThrows(IllegalArgumentException.class, () -> options.setShutdownTimeout(timeout));
    assertEquals(Duration.ofSeconds(30), options.getIdleTimeout());
    assertEquals(Duration.ofSeconds(5), options.getShutdownTimeout());
  }

  @ParameterizedTest
  @MethodSource("validTimeouts")
  void acceptsThePositiveMillisecondTimeoutRange(Duration timeout) {
    var options = new Options();
    options.setIdleTimeout(timeout);
    options.setShutdownTimeout(timeout);

    assertEquals(timeout, options.getIdleTimeout());
    assertEquals(timeout, options.getShutdownTimeout());
  }

  @ParameterizedTest
  @MethodSource("nullResets")
  void nullRestoresOnlyTheSelectedDefault(String property, Consumer<Options> reset, Object expected)
      throws Exception {
    var options = new Options();
    options.setHost("127.0.0.1");
    options.setPort(9090);
    options.setMaxRequestBytes(64);
    options.setMaxResponseBytes(128);
    options.setMaxRequestHeaderBytes(1024);
    options.setIdleTimeout(Duration.ofSeconds(11));
    options.setShutdownTimeout(Duration.ofSeconds(2));
    options.setHttp2(true);
    options.setCompression(true);
    options.setMaxParameters(4);
    var multipart = new MultipartOptions();
    multipart.setMaxFileBytes(1000L);
    multipart.setMaxBytes(2000L);
    multipart.setMaxParts(8);
    multipart.setMaxPartHeadersBytes(256);
    multipart.setMaxMemoryPartBytes(64L);
    options.setMultipart(multipart);
    options.setStreamBufferBytes(32);
    options.setVirtualThreads(true);
    var properties = Introspector.getBeanInfo(Options.class, Object.class).getPropertyDescriptors();
    var previous = new LinkedHashMap<String, Object>();
    for (var descriptor : properties) {
      previous.put(descriptor.getName(), descriptor.getReadMethod().invoke(options));
    }
    reset.accept(options);
    for (var descriptor : properties) {
      if ("multipart".equals(property) && descriptor.getName().equals(property)) {
        assertDefaultMultipart(options.getMultipart());
        assertNotSame(previous.get(property), options.getMultipart());
        continue;
      }

      assertEquals(
          descriptor.getName().equals(property) ? expected : previous.get(descriptor.getName()),
          descriptor.getReadMethod().invoke(options),
          descriptor.getName());
    }
  }

  @Test
  void createsIndependentMultipartDefaultsAndFreshDefaultsAfterReset() {
    var first = new Options();
    var second = new Options();
    var previous = first.getMultipart();
    previous.setMaxParts(7);

    assertNotSame(previous, second.getMultipart());
    assertDefaultMultipart(second.getMultipart());

    first.setMultipart(null);

    assertNotSame(previous, first.getMultipart());
    assertNotSame(second.getMultipart(), first.getMultipart());
    assertDefaultMultipart(first.getMultipart());
    assertEquals(7, previous.getMaxParts());
  }

  @Test
  void retainsCallerMultipartSettingsForSubsequentConfiguration() {
    var multipart = new MultipartOptions();
    var options = new Options();
    options.setMultipart(multipart);

    assertSame(multipart, options.getMultipart());

    options.getMultipart().setMaxParts(7);

    assertEquals(7, multipart.getMaxParts());
  }

  private static void assertDefaultMultipart(MultipartOptions options) {
    assertEquals(10_485_760L, options.getMaxBytes());
    assertEquals(5_242_880L, options.getMaxFileBytes());
    assertEquals(100, options.getMaxParts());
    assertEquals(8192, options.getMaxPartHeadersBytes());
    assertEquals(16_384L, options.getMaxMemoryPartBytes());
    assertNull(options.getTemporaryDirectory());
  }
}
