package io.github.suppierk.shoostr.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class McpOperationPolicyTest {
  @Test
  void exposesTheApprovedFiniteDefaults() {
    var limits = McpOperationLimits.defaults();
    assertEquals(256, limits.maxOperations());
    assertEquals(64, limits.maxOperationsPerOwner());
    assertEquals(16, limits.maxOperationsPerSession());
    assertEquals(Duration.ofMinutes(5), limits.timeout());
  }

  @ParameterizedTest
  @CsvSource({"0,1,1", "-1,1,1", "1,0,1", "1,-1,1", "1,1,0", "1,1,-1"})
  void rejectsNonPositiveCapacity(int total, int owner, int session) {
    assertThrows(
        IllegalArgumentException.class,
        () -> new McpOperationLimits(total, owner, session, Duration.ofSeconds(1)));
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1})
  void rejectsNonPositiveTimeout(long seconds) {
    assertThrows(
        IllegalArgumentException.class,
        () -> new McpOperationLimits(1, 1, 1, Duration.ofSeconds(seconds)));
  }

  @Test
  void rejectsAnAbsentTimeout() {
    assertThrows(NullPointerException.class, () -> new McpOperationLimits(1, 1, 1, null));
  }

  @ParameterizedTest
  @ValueSource(strings = {"security", "sessions", "ordinary", "control", "factory"})
  void validatesConfigurationBeforeInvokingTheNativeFactory(String absent) {
    var invoked = new AtomicInteger();
    var security =
        new McpHttpSecurity(
            Set.of(),
            ignored -> new McpIdentity("public", McpTransportContext.EMPTY, null),
            (identity, message) -> {});
    Function<McpStreamableServerTransportProvider, McpSyncServer> factory =
        transport -> {
          invoked.incrementAndGet();
          return McpServer.sync(transport).build();
        };
    assertThrows(
        NullPointerException.class,
        () ->
            Mcp.sync(
                "security".equals(absent) ? null : security,
                "sessions".equals(absent) ? null : McpSessionLimits.defaults(),
                "ordinary".equals(absent) ? null : McpOperationLimits.defaults(),
                "control".equals(absent) ? null : McpOperationLimits.defaults(),
                "factory".equals(absent) ? null : factory));
    assertEquals(0, invoked.get());
  }

  @Test
  void rejectsManagedExecutionWithoutAnAdmittedOperationBeforeInvokingTheCallable() {
    var invoked = new AtomicInteger();
    var security =
        new McpHttpSecurity(
            Set.of(),
            ignored -> new McpIdentity("public", McpTransportContext.EMPTY, null),
            (identity, message) -> {});
    var mcp = Mcp.async(security, transport -> McpServer.async(transport).build());

    try {
      assertThrows(NullPointerException.class, () -> mcp.work().call(null));
      assertThrows(
          IllegalStateException.class,
          () -> mcp.work().call(invoked::incrementAndGet).block(Duration.ofSeconds(1)));
      assertEquals(0, invoked.get());
    } finally {
      mcp.close();
    }

    assertThrows(
        IllegalStateException.class,
        () -> mcp.work().call(invoked::incrementAndGet).block(Duration.ofSeconds(1)));
    assertEquals(0, invoked.get());
  }
}
