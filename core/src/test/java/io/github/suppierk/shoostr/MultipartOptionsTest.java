package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.beans.FeatureDescriptor;
import java.beans.Introspector;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class MultipartOptionsTest {
  @Test
  void satisfiesEqualsAndHashCodeContract() {
    EqualsVerifier.forClass(MultipartOptions.class).suppress(Warning.NONFINAL_FIELDS).verify();
  }

  @Test
  void comparesTemporaryDirectoryValuesAndRestoresEqualityAfterReset() {
    var first = new MultipartOptions();
    var second = new MultipartOptions();

    assertEquals(first, second);
    assertEquals(first.hashCode(), second.hashCode());

    first.setTemporaryDirectory(Path.of("first-uploads"));
    second.setTemporaryDirectory(Path.of("first-uploads"));

    assertEquals(first, second);
    assertEquals(first.hashCode(), second.hashCode());

    second.setTemporaryDirectory(Path.of("second-uploads"));

    assertNotEquals(first, second);

    first.setTemporaryDirectory(null);
    second.setTemporaryDirectory(null);

    assertEquals(first, second);
    assertEquals(first.hashCode(), second.hashCode());
  }

  private static Stream<Arguments> nullResets() {
    return Stream.of(
        Arguments.of(
            "maxBytes", (Consumer<MultipartOptions>) o -> o.setMaxBytes(null), 10_485_760L),
        Arguments.of(
            "maxFileBytes", (Consumer<MultipartOptions>) o -> o.setMaxFileBytes(null), 5_242_880L),
        Arguments.of("maxParts", (Consumer<MultipartOptions>) o -> o.setMaxParts(null), 100),
        Arguments.of(
            "maxPartHeadersBytes",
            (Consumer<MultipartOptions>) o -> o.setMaxPartHeadersBytes(null),
            8192),
        Arguments.of(
            "maxMemoryPartBytes",
            (Consumer<MultipartOptions>) o -> o.setMaxMemoryPartBytes(null),
            16_384L),
        Arguments.of(
            "temporaryDirectory",
            (Consumer<MultipartOptions>) o -> o.setTemporaryDirectory(null),
            null));
  }

  private static Stream<Consumer<MultipartOptions>> invalidMutations() {
    return Stream.of(
        o -> o.setMaxBytes(0L),
        o -> o.setMaxBytes(-1L),
        o -> o.setMaxBytes(5_242_879L),
        o -> o.setMaxFileBytes(0L),
        o -> o.setMaxFileBytes(-1L),
        o -> o.setMaxFileBytes(10_485_761L),
        o -> o.setMaxParts(0),
        o -> o.setMaxParts(-1),
        o -> o.setMaxPartHeadersBytes(0),
        o -> o.setMaxPartHeadersBytes(-1),
        o -> o.setMaxMemoryPartBytes(-1L));
  }

  @Test
  void initializesEveryMultipartDefaultOnNewInstance() {
    assertDefaults(new MultipartOptions());
  }

  @Test
  void exposesAllSettingsAsReadableAndWritableBeanProperties() throws Exception {
    var properties =
        Introspector.getBeanInfo(MultipartOptions.class, Object.class).getPropertyDescriptors();

    assertEquals(
        Set.of(
            "maxBytes",
            "maxFileBytes",
            "maxParts",
            "maxPartHeadersBytes",
            "maxMemoryPartBytes",
            "temporaryDirectory"),
        Stream.of(properties).map(FeatureDescriptor::getName).collect(Collectors.toSet()));
    for (var property : properties) {
      assertNotNull(property.getReadMethod(), property.getName());
      assertNotNull(property.getWriteMethod(), property.getName());
    }
  }

  @ParameterizedTest
  @MethodSource("invalidMutations")
  void rejectsInvalidMutationsWithoutChangingAnySetting(Consumer<MultipartOptions> mutation) {
    var options = new MultipartOptions();

    assertThrows(IllegalArgumentException.class, () -> mutation.accept(options));

    assertDefaults(options);
  }

  @ParameterizedTest
  @MethodSource("nullResets")
  void nullRestoresOnlyTheSelectedDefault(
      String property, Consumer<MultipartOptions> reset, Object expected) throws Exception {
    var options = customizedOptions();
    var properties =
        Introspector.getBeanInfo(MultipartOptions.class, Object.class).getPropertyDescriptors();
    var previous = new LinkedHashMap<String, Object>();
    for (var descriptor : properties) {
      previous.put(descriptor.getName(), descriptor.getReadMethod().invoke(options));
    }

    reset.accept(options);

    for (var descriptor : properties) {
      assertEquals(
          descriptor.getName().equals(property) ? expected : previous.get(descriptor.getName()),
          descriptor.getReadMethod().invoke(options),
          descriptor.getName());
    }
  }

  @Test
  void acceptsMinimumAndMaximumFiniteLimits() {
    var options = new MultipartOptions();
    options.setMaxFileBytes(1L);
    options.setMaxBytes(1L);
    options.setMaxParts(1);
    options.setMaxPartHeadersBytes(1);
    options.setMaxMemoryPartBytes(0L);

    assertEquals(1L, options.getMaxBytes());
    assertEquals(1L, options.getMaxFileBytes());
    assertEquals(1, options.getMaxParts());
    assertEquals(1, options.getMaxPartHeadersBytes());
    assertEquals(0L, options.getMaxMemoryPartBytes());

    options.setMaxBytes(Long.MAX_VALUE);
    options.setMaxFileBytes(Long.MAX_VALUE);
    options.setMaxParts(Integer.MAX_VALUE);
    options.setMaxPartHeadersBytes(Integer.MAX_VALUE);
    options.setMaxMemoryPartBytes(Long.MAX_VALUE);

    assertEquals(Long.MAX_VALUE, options.getMaxBytes());
    assertEquals(Long.MAX_VALUE, options.getMaxFileBytes());
    assertEquals(Integer.MAX_VALUE, options.getMaxParts());
    assertEquals(Integer.MAX_VALUE, options.getMaxPartHeadersBytes());
    assertEquals(Long.MAX_VALUE, options.getMaxMemoryPartBytes());
  }

  @Test
  void rejectsIncompatibleDefaultResetsAndAllowsOrderedLimitChanges() {
    var options = new MultipartOptions();
    options.setMaxBytes(20_000_000L);
    options.setMaxFileBytes(15_000_000L);

    assertThrows(IllegalArgumentException.class, () -> options.setMaxBytes(null));
    assertEquals(20_000_000L, options.getMaxBytes());
    assertEquals(15_000_000L, options.getMaxFileBytes());

    options.setMaxFileBytes(1024L);
    options.setMaxBytes(8192L);

    assertThrows(IllegalArgumentException.class, () -> options.setMaxFileBytes(null));
    assertEquals(8192L, options.getMaxBytes());
    assertEquals(1024L, options.getMaxFileBytes());

    options.setMaxBytes(null);
    options.setMaxFileBytes(null);

    assertDefaults(options);
  }

  @Test
  void configuresTheTemporaryDirectoryWithoutChangingLimits() {
    var options = new MultipartOptions();
    var directory = Path.of("temporary-uploads");
    options.setTemporaryDirectory(directory);

    assertEquals(directory, options.getTemporaryDirectory());
    assertEquals(10_485_760L, options.getMaxBytes());
    assertEquals(5_242_880L, options.getMaxFileBytes());

    options.setTemporaryDirectory(null);

    assertDefaults(options);
  }

  private static MultipartOptions customizedOptions() {
    var options = new MultipartOptions();
    options.setMaxBytes(20_000_000L);
    options.setMaxFileBytes(6_000_000L);
    options.setMaxParts(7);
    options.setMaxPartHeadersBytes(256);
    options.setMaxMemoryPartBytes(64L);
    options.setTemporaryDirectory(Path.of("temporary-uploads"));
    return options;
  }

  private static void assertDefaults(MultipartOptions options) {
    assertEquals(10_485_760L, options.getMaxBytes());
    assertEquals(5_242_880L, options.getMaxFileBytes());
    assertEquals(100, options.getMaxParts());
    assertEquals(8192, options.getMaxPartHeadersBytes());
    assertEquals(16_384L, options.getMaxMemoryPartBytes());
    assertNull(options.getTemporaryDirectory());
  }
}
