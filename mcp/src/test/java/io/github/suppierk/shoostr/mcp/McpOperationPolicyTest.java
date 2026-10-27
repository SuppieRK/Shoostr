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
    var timeout = Duration.ofSeconds(1);
    assertThrows(
        IllegalArgumentException.class,
        () -> new McpOperationLimits(total, owner, session, timeout));
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1})
  void rejectsNonPositiveTimeout(long seconds) {
    var timeout = Duration.ofSeconds(seconds);
    assertThrows(IllegalArgumentException.class, () -> new McpOperationLimits(1, 1, 1, timeout));
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
    var selectedSecurity = "security".equals(absent) ? null : security;
    var selectedSessions = "sessions".equals(absent) ? null : McpSessionLimits.defaults();
    var selectedOrdinary = "ordinary".equals(absent) ? null : McpOperationLimits.defaults();
    var selectedControl = "control".equals(absent) ? null : McpOperationLimits.defaults();
    var selectedFactory = "factory".equals(absent) ? null : factory;
    assertThrows(
        NullPointerException.class,
        () ->
            Mcp.sync(
                selectedSecurity,
                selectedSessions,
                selectedOrdinary,
                selectedControl,
                selectedFactory));
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
    var work = mcp.work();
    var timeout = Duration.ofSeconds(1);

    try {
      assertThrows(NullPointerException.class, () -> work.call(null));
      var action = work.call(invoked::incrementAndGet);
      assertThrows(IllegalStateException.class, () -> action.block(timeout));
      assertEquals(0, invoked.get());
    } finally {
      mcp.close();
    }

    var action = work.call(invoked::incrementAndGet);
    assertThrows(IllegalStateException.class, () -> action.block(timeout));
    assertEquals(0, invoked.get());
  }
}
