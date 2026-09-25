package io.github.suppierk.shoostr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.suppierk.shoostr.http.HttpMethods;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RoutePatternBenchmarkTest {
  @ParameterizedTest
  @MethodSource("cases")
  void everyQueryHasTheIntendedRouteAndDecodedValues(int groups, String workload, String input) {
    var fixture = new RoutePatternBenchmark();
    fixture.groups = groups;
    fixture.workload = workload;
    fixture.input = input;
    fixture.setup();
    for (String path : fixture.paths) {
      var endpoint = fixture.router.match(path, fixture.method);
      if ("wrongMethod".equals(workload) || "notFound".equals(workload)) {
        assertNull(endpoint);
        assertEquals(
            "notFound".equals(workload) ? Set.of() : Set.of(HttpMethods.GET, HttpMethods.POST),
            fixture.router.allowedMethods(path));
      } else {
        var segments = path.split("/");
        int group = Integer.parseInt(segments[3]);
        String patternSuffix =
            switch (workload) {
              case "literal" -> "/latest";
              case "precedence" -> "/shadowed";
              case "twoParameters" -> "/{id}/items/{itemId}";
              case "fallback" -> "/{id}/events";
              default -> "/{id}";
            };
        assertEquals(
            "/api/resources/" + group + "/orders" + patternSuffix, endpoint.routePattern());
        for (int i = 0; i < fixture.parameterNames.length; i++) {
          String value = URLDecoder.decode(segments[5 + i * 2], StandardCharsets.UTF_8);
          assertEquals(value, endpoint.parameter(path, fixture.parameterNames[i]));
        }
      }
    }
  }

  private static Stream<Arguments> cases() {
    return Stream.of(1, 100, 1000)
        .flatMap(
            groups ->
                Stream.of(
                        "literal",
                        "oneParameter",
                        "twoParameters",
                        "encodedParameter",
                        "precedence",
                        "fallback",
                        "notFound",
                        "wrongMethod")
                    .flatMap(
                        workload ->
                            Stream.of("reused", "fresh")
                                .map(input -> Arguments.of(groups, workload, input))));
  }
}
