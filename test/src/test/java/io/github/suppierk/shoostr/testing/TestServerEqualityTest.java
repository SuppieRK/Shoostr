package io.github.suppierk.shoostr.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suppierk.shoostr.Shoostr;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import org.junit.jupiter.api.Test;

class TestServerEqualityTest {
  @Test
  void retainsLiveServerIdentityAndBuilderMembershipThroughSendingAndClosing() throws Exception {
    var builders = new ArrayList<TestRequest>();
    var builderHashes = new ArrayList<Integer>();
    var builderSet = new HashSet<TestRequest>();
    var serverSet = new HashSet<TestServer>();

    try (var firstApp = new Shoostr();
        var secondApp = new Shoostr();
        var firstReservation = ServerSocketChannel.open();
        var secondReservation = ServerSocketChannel.open()) {
      firstReservation.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
      secondReservation.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
      firstApp.routes().post("/same", (request, response) -> response.text(request.bodyText()));
      secondApp.routes().post("/same", (request, response) -> response.text(request.bodyText()));

      try (var first = TestServer.start(firstApp);
          var second = TestServer.start(secondApp)) {
        var firstHash = first.hashCode();
        var secondHash = second.hashCode();
        assertTrue(serverSet.add(first));
        assertTrue(serverSet.add(second));
        assertNotEquals(first, second);
        EqualsVerifier.forClass(TestServer.class)
            .withPrefabValues(Shoostr.class, firstApp, secondApp)
            .withPrefabValues(HttpClient.class, first.httpClient(), second.httpClient())
            .withPrefabValues(ServerSocketChannel.class, firstReservation, secondReservation)
            .suppress(Warning.INHERITED_DIRECTLY_FROM_OBJECT, Warning.ALL_FIELDS_SHOULD_BE_USED)
            .verify();
        for (var server : new TestServer[] {first, second}) {
          var response =
              server.send(
                  request -> {
                    builders.add(request);
                    builderHashes.add(request.hashCode());
                    assertTrue(builderSet.add(request));
                    request
                        .path("/same")
                        .method("POST")
                        .body("payload".getBytes(StandardCharsets.UTF_8));
                    assertTrue(builderSet.contains(request));
                  },
                  HttpResponse.BodyHandlers.ofString());
          assertEquals(200, response.statusCode());
          assertEquals("payload", response.body());
        }
        first.close();
        second.close();
        assertEquals(firstHash, first.hashCode());
        assertEquals(secondHash, second.hashCode());
        assertTrue(serverSet.remove(first));
        assertTrue(serverSet.remove(second));
      }
    }

    assertNotEquals(builders.get(0), builders.get(1));
    for (int index = 0; index < builders.size(); index++) {
      var builder = builders.get(index);
      assertEquals(builderHashes.get(index).intValue(), builder.hashCode());
      assertTrue(builderSet.remove(builder));
    }
    assertTrue(builderSet.isEmpty());
    assertTrue(serverSet.isEmpty());
  }
}
