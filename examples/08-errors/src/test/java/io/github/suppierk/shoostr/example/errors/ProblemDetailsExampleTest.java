package io.github.suppierk.shoostr.example.errors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.suppierk.shoostr.testing.TestServer;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class ProblemDetailsExampleTest {
  @Test
  void returnsProblemJsonWithMatchingStatusAndSafePublicMembers() throws Exception {
    try (var test = TestServer.start(ProblemDetailsExample.application())) {
      var response =
          test.send(request -> request.path("/items/42"), HttpResponse.BodyHandlers.ofString());
      assertEquals(404, response.statusCode());
      assertEquals(
          "application/problem+json", response.headers().firstValue("Content-Type").orElseThrow());
      var problem = new ObjectMapper().readTree(response.body());
      assertEquals(6, problem.size());
      assertEquals("about:blank", problem.path("type").asText());
      assertEquals("Not Found", problem.path("title").asText());
      assertTrue(problem.path("status").isIntegralNumber());
      assertEquals(404, problem.path("status").asInt());
      assertEquals("Item \"42\" was not found.", problem.path("detail").asText());
      assertEquals("/items/42", problem.path("instance").asText());
      assertTrue(problem.path("itemId").isIntegralNumber());
      assertEquals(42, problem.path("itemId").asInt());
      assertFalse(response.body().contains("Private"));
    }
  }
}
