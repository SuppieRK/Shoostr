package io.github.suppierk.shoostr.openapi;

import io.github.suppierk.shoostr.RouteInfo;
import io.github.suppierk.shoostr.http.HttpMethods;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.servers.Server;
import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import org.junit.jupiter.api.Test;

class OpenApiEqualityTest {
  @Test
  void documentCollectorRetainsBuilderIdentity() {
    EqualsVerifier.forClass(OpenApi.class)
        .withPrefabValues(
            Server.class,
            new Server().url("https://first.example"),
            new Server().url("https://second.example"))
        .withPrefabValues(
            OpenApiUi.class,
            new OpenApiUi("/first/", "/first.json"),
            new OpenApiUi("/second/", "/second.json"))
        .withPrefabValues(
            RouteInfo.class,
            new RouteInfo(HttpMethods.GET, "/first", false),
            new RouteInfo(HttpMethods.POST, "/second", true))
        .withPrefabValues(
            Operation.class, new Operation().summary("first"), new Operation().summary("second"))
        .withPrefabValues(
            Components.class,
            new Components().addSchemas("First", new JsonSchema()),
            new Components().addSchemas("Second", new JsonSchema()))
        .withNonnullFields(
            "info", "bindings", "components", "servers", "tags", "interfaces", "document")
        .suppress(Warning.INHERITED_DIRECTLY_FROM_OBJECT, Warning.ALL_FIELDS_SHOULD_BE_USED)
        .verify();
  }

  @Test
  void operationDefaultsRetainBuilderIdentity() {
    EqualsVerifier.forClass(OpenApiOperation.class)
        .withPrefabValues(OpenApi.class, new OpenApi("First", "1"), new OpenApi("Second", "2"))
        .withPrefabValues(
            Operation.class, new Operation().summary("first"), new Operation().summary("second"))
        .withNonnullFields("operation")
        .suppress(Warning.INHERITED_DIRECTLY_FROM_OBJECT, Warning.ALL_FIELDS_SHOULD_BE_USED)
        .verify();
  }

  @Test
  void bindingComparesItsRouteAndSwaggerOperation() throws ClassNotFoundException {
    EqualsVerifier.forClass(Class.forName(OpenApi.class.getName() + "$Binding"))
        .withPrefabValues(
            RouteInfo.class,
            new RouteInfo(HttpMethods.GET, "/first", false),
            new RouteInfo(HttpMethods.POST, "/second", true))
        .withPrefabValues(
            Operation.class, new Operation().summary("first"), new Operation().summary("second"))
        .withNonnullFields("route", "operation")
        .verify();
  }
}
