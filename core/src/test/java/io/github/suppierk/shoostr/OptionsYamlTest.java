package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

class OptionsYamlTest {
  @Test
  void createsFreshOptionsFromAnEmptyMappingWithAllFieldDefaults() {
    var yaml = new Yaml();
    var options = yaml.loadAs("{}", Options.class);
    var another = yaml.loadAs("{}", Options.class);

    assertNotSame(options, another);
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
    assertEquals(MultipartOptions.defaults(), options.getMultipart());
    assertEquals(8192, options.getStreamBufferBytes());
    assertFalse(options.getVirtualThreads());
  }

  @Test
  void appliesOnlySuppliedPropertiesWhilePreservingOmittedDefaults() {
    var options = createOptionsForAppliesOnlySuppliedPropertiesWhilePreservingOmittedDefaults();

    assertEquals("127.0.0.1", options.getHost());
    assertEquals(9090, options.getPort());
    assertEquals(2048, options.getMaxRequestBytes());
    assertTrue(options.getCompression());
    assertEquals(1_048_576, options.getMaxResponseBytes());
    assertEquals(8192, options.getMaxRequestHeaderBytes());
    assertEquals(Duration.ofSeconds(30), options.getIdleTimeout());
    assertEquals(Duration.ofSeconds(5), options.getShutdownTimeout());
    assertFalse(options.getHttp2());
    assertEquals(1000, options.getMaxParameters());
    assertEquals(MultipartOptions.defaults(), options.getMultipart());
    assertEquals(8192, options.getStreamBufferBytes());
    assertFalse(options.getVirtualThreads());
  }

  private Options createOptionsForAppliesOnlySuppliedPropertiesWhilePreservingOmittedDefaults() {
    return new Yaml()
        .loadAs(
            """
        host: 127.0.0.1
        port: 9090
        maxRequestBytes: 2048
        compression: true
        """,
            Options.class);
  }

  @Test
  void explicitNullPropertiesRestoreEveryDefaultThroughNormalSetters() {
    var options = createOptionsForExplicitNullPropertiesRestoreEveryDefaultThroughNormalSetters();

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
    assertEquals(MultipartOptions.defaults(), options.getMultipart());
    assertEquals(8192, options.getStreamBufferBytes());
    assertFalse(options.getVirtualThreads());
  }

  private Options createOptionsForExplicitNullPropertiesRestoreEveryDefaultThroughNormalSetters() {
    return new Yaml()
        .loadAs(
            """
        host: null
        port: null
        maxRequestBytes: null
        maxResponseBytes: null
        maxRequestHeaderBytes: null
        idleTimeout: null
        shutdownTimeout: null
        http2: null
        compression: null
        maxParameters: null
        multipart: null
        streamBufferBytes: null
        virtualThreads: null
        """,
            Options.class);
  }

  @Test
  void mappedInvalidValuesInvokeSetterValidation() {
    var failure =
        assertThrows(YAMLException.class, () -> new Yaml().loadAs("port: -1", Options.class));

    Throwable cause = failure;
    Throwable next = cause.getCause();
    while (next != null) {
      cause = next;
      next = cause.getCause();
    }

    assertInstanceOf(IllegalArgumentException.class, cause);
    assertEquals("Listener port must be between 0 and 65535", cause.getMessage());
  }
}
