package io.github.suppierk.shoostr;

import io.github.suppierk.shoostr.http.HttpMethods;
import java.io.Closeable;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Codec-backed HTTP registration sharing the existing router, group-depth limits, availability,
 * extension configuration and caller-owned executor contract. No runtime route view is allocated.
 * Use ordinary Routes for SSE, WebSocket and static resources. Closing any scope ends registration
 * for the entire app without stopping its listener.
 */
public final class TypedRoutes implements Closeable {
  private final Routes routes;

  /**
   * Wraps registration only; request/response state is not wrapped or copied.
   *
   * @param routes existing registration scope
   */
  TypedRoutes(Routes routes) {
    this.routes = Objects.requireNonNull(routes);
  }

  /**
   * Registers a nested typed group under the same depth and lifetime guards as ordinary routes.
   *
   * @param path relative prefix
   * @param registration child endpoints
   * @return this scope
   */
  public TypedRoutes path(String path, Consumer<TypedRoutes> registration) {
    Objects.requireNonNull(registration);
    routes.path(path, child -> registration.accept(new TypedRoutes(child)));
    return this;
  }

  /**
   * Registers a typed group with independently inherited extension configuration.
   *
   * @param path relative prefix
   * @param registration child endpoints
   * @param configuration group extension configuration
   * @return this scope
   */
  public TypedRoutes path(
      String path, Consumer<TypedRoutes> registration, Consumer<Extensions> configuration) {
    Objects.requireNonNull(registration);
    routes.path(path, child -> registration.accept(new TypedRoutes(child)), configuration);
    return this;
  }

  /**
   * Evaluates availability at request time, retaining the existing concealed-404 behavior.
   *
   * @param condition shared runtime availability
   * @param registration conditional endpoints
   * @return this scope
   */
  public TypedRoutes when(BooleanSupplier condition, Consumer<TypedRoutes> registration) {
    Objects.requireNonNull(registration);
    routes.when(condition, child -> registration.accept(new TypedRoutes(child)));
    return this;
  }

  /**
   * Evaluates request-dependent availability on the endpoint's matched executor.
   *
   * @param condition runtime request policy
   * @param registration conditional endpoints
   * @return this scope
   */
  public TypedRoutes when(Predicate<Request> condition, Consumer<TypedRoutes> registration) {
    Objects.requireNonNull(registration);
    routes.when(condition, child -> registration.accept(new TypedRoutes(child)));
    return this;
  }

  /**
   * Registers a typed GET endpoint through the existing registration scope.
   *
   * @param handler codec-backed endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public TypedRoutes get(TypedHandler handler, Consumer<Extensions> configuration) {
    routes.get(adapt(handler), configuration);
    return this;
  }

  /**
   * Registers a typed GET endpoint through the existing registration scope.
   *
   * @param path relative endpoint path
   * @param handler codec-backed endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public TypedRoutes get(String path, TypedHandler handler, Consumer<Extensions> configuration) {
    routes.get(path, adapt(handler), configuration);
    return this;
  }

  /**
   * Registers a typed GET endpoint through the existing registration scope.
   *
   * @param handler codec-backed endpoint handler
   * @return this scope
   */
  public TypedRoutes get(TypedHandler handler) {
    routes.get(adapt(handler));
    return this;
  }

  /**
   * Registers a typed GET endpoint through the existing registration scope.
   *
   * @param path relative endpoint path
   * @param handler codec-backed endpoint handler
   * @return this scope
   */
  public TypedRoutes get(String path, TypedHandler handler) {
    routes.get(path, adapt(handler));
    return this;
  }

  /**
   * Registers a typed GET endpoint through the existing registration scope.
   *
   * @param path relative endpoint path
   * @param executor caller-owned asynchronous executor for the matched lifecycle
   * @param handler codec-backed endpoint handler
   * @return this scope
   */
  public TypedRoutes get(String path, ExecutorService executor, TypedHandler handler) {
    routes.get(path, executor, adapt(handler));
    return this;
  }

  /**
   * Registers a typed GET endpoint through the existing registration scope.
   *
   * @param path relative endpoint path
   * @param executor caller-owned asynchronous executor for the matched lifecycle
   * @param handler codec-backed endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public TypedRoutes get(
      String path,
      ExecutorService executor,
      TypedHandler handler,
      Consumer<Extensions> configuration) {
    routes.get(path, executor, adapt(handler), configuration);
    return this;
  }

  /**
   * Registers a typed GET endpoint through the existing registration scope.
   *
   * @param executor caller-owned asynchronous executor for the matched lifecycle
   * @param handler codec-backed endpoint handler
   * @return this scope
   */
  public TypedRoutes get(ExecutorService executor, TypedHandler handler) {
    routes.get(executor, adapt(handler));
    return this;
  }

  /**
   * Registers a typed GET endpoint through the existing registration scope.
   *
   * @param executor caller-owned asynchronous executor for the matched lifecycle
   * @param handler codec-backed endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public TypedRoutes get(
      ExecutorService executor, TypedHandler handler, Consumer<Extensions> configuration) {
    routes.get(executor, adapt(handler), configuration);
    return this;
  }

  /**
   * Registers a typed POST endpoint through the existing registration scope.
   *
   * @param handler codec-backed endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public TypedRoutes post(TypedHandler handler, Consumer<Extensions> configuration) {
    routes.post(adapt(handler), configuration);
    return this;
  }

  /**
   * Registers a typed POST endpoint through the existing registration scope.
   *
   * @param path relative endpoint path
   * @param handler codec-backed endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public TypedRoutes post(String path, TypedHandler handler, Consumer<Extensions> configuration) {
    routes.post(path, adapt(handler), configuration);
    return this;
  }

  /**
   * Registers a typed POST endpoint through the existing registration scope.
   *
   * @param handler codec-backed endpoint handler
   * @return this scope
   */
  public TypedRoutes post(TypedHandler handler) {
    routes.post(adapt(handler));
    return this;
  }

  /**
   * Registers a typed POST endpoint through the existing registration scope.
   *
   * @param path relative endpoint path
   * @param handler codec-backed endpoint handler
   * @return this scope
   */
  public TypedRoutes post(String path, TypedHandler handler) {
    routes.post(path, adapt(handler));
    return this;
  }

  /**
   * Registers a typed POST endpoint through the existing registration scope.
   *
   * @param path relative endpoint path
   * @param executor caller-owned asynchronous executor for the matched lifecycle
   * @param handler codec-backed endpoint handler
   * @return this scope
   */
  public TypedRoutes post(String path, ExecutorService executor, TypedHandler handler) {
    routes.post(path, executor, adapt(handler));
    return this;
  }

  /**
   * Registers a typed POST endpoint through the existing registration scope.
   *
   * @param path relative endpoint path
   * @param executor caller-owned asynchronous executor for the matched lifecycle
   * @param handler codec-backed endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public TypedRoutes post(
      String path,
      ExecutorService executor,
      TypedHandler handler,
      Consumer<Extensions> configuration) {
    routes.post(path, executor, adapt(handler), configuration);
    return this;
  }

  /**
   * Registers a typed POST endpoint through the existing registration scope.
   *
   * @param executor caller-owned asynchronous executor for the matched lifecycle
   * @param handler codec-backed endpoint handler
   * @return this scope
   */
  public TypedRoutes post(ExecutorService executor, TypedHandler handler) {
    routes.post(executor, adapt(handler));
    return this;
  }

  /**
   * Registers a typed POST endpoint through the existing registration scope.
   *
   * @param executor caller-owned asynchronous executor for the matched lifecycle
   * @param handler codec-backed endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public TypedRoutes post(
      ExecutorService executor, TypedHandler handler, Consumer<Extensions> configuration) {
    routes.post(executor, adapt(handler), configuration);
    return this;
  }

  /**
   * Registers a typed PUT endpoint through the existing registration scope.
   *
   * @param handler codec-backed endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public TypedRoutes put(TypedHandler handler, Consumer<Extensions> configuration) {
    routes.put(adapt(handler), configuration);
    return this;
  }

  /**
   * Registers a typed PUT endpoint through the existing registration scope.
   *
   * @param path relative endpoint path
   * @param handler codec-backed endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public TypedRoutes put(String path, TypedHandler handler, Consumer<Extensions> configuration) {
    routes.put(path, adapt(handler), configuration);
    return this;
  }

  /**
   * Registers a typed PUT endpoint through the existing registration scope.
   *
   * @param handler codec-backed endpoint handler
   * @return this scope
   */
  public TypedRoutes put(TypedHandler handler) {
    routes.put(adapt(handler));
    return this;
  }

  /**
   * Registers a typed PUT endpoint through the existing registration scope.
   *
   * @param path relative endpoint path
   * @param handler codec-backed endpoint handler
   * @return this scope
   */
  public TypedRoutes put(String path, TypedHandler handler) {
    routes.put(path, adapt(handler));
    return this;
  }

  /**
   * Registers a typed PUT endpoint through the existing registration scope.
   *
   * @param path relative endpoint path
   * @param executor caller-owned asynchronous executor for the matched lifecycle
   * @param handler codec-backed endpoint handler
   * @return this scope
   */
  public TypedRoutes put(String path, ExecutorService executor, TypedHandler handler) {
    routes.put(path, executor, adapt(handler));
    return this;
  }

  /**
   * Registers a typed PUT endpoint through the existing registration scope.
   *
   * @param path relative endpoint path
   * @param executor caller-owned asynchronous executor for the matched lifecycle
   * @param handler codec-backed endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public TypedRoutes put(
      String path,
      ExecutorService executor,
      TypedHandler handler,
      Consumer<Extensions> configuration) {
    routes.put(path, executor, adapt(handler), configuration);
    return this;
  }

  /**
   * Registers a typed PUT endpoint through the existing registration scope.
   *
   * @param executor caller-owned asynchronous executor for the matched lifecycle
   * @param handler codec-backed endpoint handler
   * @return this scope
   */
  public TypedRoutes put(ExecutorService executor, TypedHandler handler) {
    routes.put(executor, adapt(handler));
    return this;
  }

  /**
   * Registers a typed PUT endpoint through the existing registration scope.
   *
   * @param executor caller-owned asynchronous executor for the matched lifecycle
   * @param handler codec-backed endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public TypedRoutes put(
      ExecutorService executor, TypedHandler handler, Consumer<Extensions> configuration) {
    routes.put(executor, adapt(handler), configuration);
    return this;
  }

  /**
   * Registers a typed PATCH endpoint through the existing registration scope.
   *
   * @param handler codec-backed endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public TypedRoutes patch(TypedHandler handler, Consumer<Extensions> configuration) {
    routes.patch(adapt(handler), configuration);
    return this;
  }

  /**
   * Registers a typed PATCH endpoint through the existing registration scope.
   *
   * @param path relative endpoint path
   * @param handler codec-backed endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public TypedRoutes patch(String path, TypedHandler handler, Consumer<Extensions> configuration) {
    routes.patch(path, adapt(handler), configuration);
    return this;
  }

  /**
   * Registers a typed PATCH endpoint through the existing registration scope.
   *
   * @param handler codec-backed endpoint handler
   * @return this scope
   */
  public TypedRoutes patch(TypedHandler handler) {
    routes.patch(adapt(handler));
    return this;
  }

  /**
   * Registers a typed PATCH endpoint through the existing registration scope.
   *
   * @param path relative endpoint path
   * @param handler codec-backed endpoint handler
   * @return this scope
   */
  public TypedRoutes patch(String path, TypedHandler handler) {
    routes.patch(path, adapt(handler));
    return this;
  }

  /**
   * Registers a typed PATCH endpoint through the existing registration scope.
   *
   * @param path relative endpoint path
   * @param executor caller-owned asynchronous executor for the matched lifecycle
   * @param handler codec-backed endpoint handler
   * @return this scope
   */
  public TypedRoutes patch(String path, ExecutorService executor, TypedHandler handler) {
    routes.patch(path, executor, adapt(handler));
    return this;
  }

  /**
   * Registers a typed PATCH endpoint through the existing registration scope.
   *
   * @param path relative endpoint path
   * @param executor caller-owned asynchronous executor for the matched lifecycle
   * @param handler codec-backed endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public TypedRoutes patch(
      String path,
      ExecutorService executor,
      TypedHandler handler,
      Consumer<Extensions> configuration) {
    routes.patch(path, executor, adapt(handler), configuration);
    return this;
  }

  /**
   * Registers a typed PATCH endpoint through the existing registration scope.
   *
   * @param executor caller-owned asynchronous executor for the matched lifecycle
   * @param handler codec-backed endpoint handler
   * @return this scope
   */
  public TypedRoutes patch(ExecutorService executor, TypedHandler handler) {
    routes.patch(executor, adapt(handler));
    return this;
  }

  /**
   * Registers a typed PATCH endpoint through the existing registration scope.
   *
   * @param executor caller-owned asynchronous executor for the matched lifecycle
   * @param handler codec-backed endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public TypedRoutes patch(
      ExecutorService executor, TypedHandler handler, Consumer<Extensions> configuration) {
    routes.patch(executor, adapt(handler), configuration);
    return this;
  }

  /**
   * Registers a typed DELETE endpoint through the existing registration scope.
   *
   * @param handler codec-backed endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public TypedRoutes delete(TypedHandler handler, Consumer<Extensions> configuration) {
    routes.delete(adapt(handler), configuration);
    return this;
  }

  /**
   * Registers a typed DELETE endpoint through the existing registration scope.
   *
   * @param path relative endpoint path
   * @param handler codec-backed endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public TypedRoutes delete(String path, TypedHandler handler, Consumer<Extensions> configuration) {
    routes.delete(path, adapt(handler), configuration);
    return this;
  }

  /**
   * Registers a typed DELETE endpoint through the existing registration scope.
   *
   * @param handler codec-backed endpoint handler
   * @return this scope
   */
  public TypedRoutes delete(TypedHandler handler) {
    routes.delete(adapt(handler));
    return this;
  }

  /**
   * Registers a typed DELETE endpoint through the existing registration scope.
   *
   * @param path relative endpoint path
   * @param handler codec-backed endpoint handler
   * @return this scope
   */
  public TypedRoutes delete(String path, TypedHandler handler) {
    routes.delete(path, adapt(handler));
    return this;
  }

  /**
   * Registers a typed DELETE endpoint through the existing registration scope.
   *
   * @param path relative endpoint path
   * @param executor caller-owned asynchronous executor for the matched lifecycle
   * @param handler codec-backed endpoint handler
   * @return this scope
   */
  public TypedRoutes delete(String path, ExecutorService executor, TypedHandler handler) {
    routes.delete(path, executor, adapt(handler));
    return this;
  }

  /**
   * Registers a typed DELETE endpoint through the existing registration scope.
   *
   * @param path relative endpoint path
   * @param executor caller-owned asynchronous executor for the matched lifecycle
   * @param handler codec-backed endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public TypedRoutes delete(
      String path,
      ExecutorService executor,
      TypedHandler handler,
      Consumer<Extensions> configuration) {
    routes.delete(path, executor, adapt(handler), configuration);
    return this;
  }

  /**
   * Registers a typed DELETE endpoint through the existing registration scope.
   *
   * @param executor caller-owned asynchronous executor for the matched lifecycle
   * @param handler codec-backed endpoint handler
   * @return this scope
   */
  public TypedRoutes delete(ExecutorService executor, TypedHandler handler) {
    routes.delete(executor, adapt(handler));
    return this;
  }

  /**
   * Registers a typed DELETE endpoint through the existing registration scope.
   *
   * @param executor caller-owned asynchronous executor for the matched lifecycle
   * @param handler codec-backed endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public TypedRoutes delete(
      ExecutorService executor, TypedHandler handler, Consumer<Extensions> configuration) {
    routes.delete(executor, adapt(handler), configuration);
    return this;
  }

  /**
   * Registers a typed HEAD endpoint through the existing registration scope.
   *
   * @param handler codec-backed endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public TypedRoutes head(TypedHandler handler, Consumer<Extensions> configuration) {
    routes.head(adapt(handler), configuration);
    return this;
  }

  /**
   * Registers a typed HEAD endpoint through the existing registration scope.
   *
   * @param path relative endpoint path
   * @param handler codec-backed endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public TypedRoutes head(String path, TypedHandler handler, Consumer<Extensions> configuration) {
    routes.head(path, adapt(handler), configuration);
    return this;
  }

  /**
   * Registers a typed HEAD endpoint through the existing registration scope.
   *
   * @param handler codec-backed endpoint handler
   * @return this scope
   */
  public TypedRoutes head(TypedHandler handler) {
    routes.head(adapt(handler));
    return this;
  }

  /**
   * Registers a typed HEAD endpoint through the existing registration scope.
   *
   * @param path relative endpoint path
   * @param handler codec-backed endpoint handler
   * @return this scope
   */
  public TypedRoutes head(String path, TypedHandler handler) {
    routes.head(path, adapt(handler));
    return this;
  }

  /**
   * Registers a typed HEAD endpoint through the existing registration scope.
   *
   * @param path relative endpoint path
   * @param executor caller-owned asynchronous executor for the matched lifecycle
   * @param handler codec-backed endpoint handler
   * @return this scope
   */
  public TypedRoutes head(String path, ExecutorService executor, TypedHandler handler) {
    routes.head(path, executor, adapt(handler));
    return this;
  }

  /**
   * Registers a typed HEAD endpoint through the existing registration scope.
   *
   * @param path relative endpoint path
   * @param executor caller-owned asynchronous executor for the matched lifecycle
   * @param handler codec-backed endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public TypedRoutes head(
      String path,
      ExecutorService executor,
      TypedHandler handler,
      Consumer<Extensions> configuration) {
    routes.head(path, executor, adapt(handler), configuration);
    return this;
  }

  /**
   * Registers a typed HEAD endpoint through the existing registration scope.
   *
   * @param executor caller-owned asynchronous executor for the matched lifecycle
   * @param handler codec-backed endpoint handler
   * @return this scope
   */
  public TypedRoutes head(ExecutorService executor, TypedHandler handler) {
    routes.head(executor, adapt(handler));
    return this;
  }

  /**
   * Registers a typed HEAD endpoint through the existing registration scope.
   *
   * @param executor caller-owned asynchronous executor for the matched lifecycle
   * @param handler codec-backed endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public TypedRoutes head(
      ExecutorService executor, TypedHandler handler, Consumer<Extensions> configuration) {
    routes.head(executor, adapt(handler), configuration);
    return this;
  }

  /**
   * Registers a typed OPTIONS endpoint through the existing registration scope.
   *
   * @param handler codec-backed endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public TypedRoutes options(TypedHandler handler, Consumer<Extensions> configuration) {
    routes.options(adapt(handler), configuration);
    return this;
  }

  /**
   * Registers a typed OPTIONS endpoint through the existing registration scope.
   *
   * @param path relative endpoint path
   * @param handler codec-backed endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public TypedRoutes options(
      String path, TypedHandler handler, Consumer<Extensions> configuration) {
    routes.options(path, adapt(handler), configuration);
    return this;
  }

  /**
   * Registers a typed OPTIONS endpoint through the existing registration scope.
   *
   * @param handler codec-backed endpoint handler
   * @return this scope
   */
  public TypedRoutes options(TypedHandler handler) {
    routes.options(adapt(handler));
    return this;
  }

  /**
   * Registers a typed OPTIONS endpoint through the existing registration scope.
   *
   * @param path relative endpoint path
   * @param handler codec-backed endpoint handler
   * @return this scope
   */
  public TypedRoutes options(String path, TypedHandler handler) {
    routes.options(path, adapt(handler));
    return this;
  }

  /**
   * Registers a typed OPTIONS endpoint through the existing registration scope.
   *
   * @param path relative endpoint path
   * @param executor caller-owned asynchronous executor for the matched lifecycle
   * @param handler codec-backed endpoint handler
   * @return this scope
   */
  public TypedRoutes options(String path, ExecutorService executor, TypedHandler handler) {
    routes.options(path, executor, adapt(handler));
    return this;
  }

  /**
   * Registers a typed OPTIONS endpoint through the existing registration scope.
   *
   * @param path relative endpoint path
   * @param executor caller-owned asynchronous executor for the matched lifecycle
   * @param handler codec-backed endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public TypedRoutes options(
      String path,
      ExecutorService executor,
      TypedHandler handler,
      Consumer<Extensions> configuration) {
    routes.options(path, executor, adapt(handler), configuration);
    return this;
  }

  /**
   * Registers a typed OPTIONS endpoint through the existing registration scope.
   *
   * @param executor caller-owned asynchronous executor for the matched lifecycle
   * @param handler codec-backed endpoint handler
   * @return this scope
   */
  public TypedRoutes options(ExecutorService executor, TypedHandler handler) {
    routes.options(executor, adapt(handler));
    return this;
  }

  /**
   * Registers a typed OPTIONS endpoint through the existing registration scope.
   *
   * @param executor caller-owned asynchronous executor for the matched lifecycle
   * @param handler codec-backed endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public TypedRoutes options(
      ExecutorService executor, TypedHandler handler, Consumer<Extensions> configuration) {
    routes.options(executor, adapt(handler), configuration);
    return this;
  }

  /**
   * Registers a typed HTTP endpoint through the existing registration scope.
   *
   * @param method recognized HTTP method
   * @param path relative endpoint path
   * @param handler codec-backed endpoint handler
   * @return this scope
   */
  public TypedRoutes route(HttpMethods method, String path, TypedHandler handler) {
    routes.route(method, path, adapt(handler));
    return this;
  }

  /**
   * Registers a typed HTTP endpoint through the existing registration scope.
   *
   * @param method recognized HTTP method
   * @param path relative endpoint path
   * @param handler codec-backed endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public TypedRoutes route(
      HttpMethods method, String path, TypedHandler handler, Consumer<Extensions> configuration) {
    routes.route(method, path, adapt(handler), configuration);
    return this;
  }

  /**
   * Registers a typed HTTP endpoint through the existing registration scope.
   *
   * @param method recognized HTTP method
   * @param path relative endpoint path
   * @param handler codec-backed endpoint handler
   * @return this scope
   */
  public TypedRoutes route(String method, String path, TypedHandler handler) {
    routes.route(method, path, adapt(handler));
    return this;
  }

  /**
   * Registers a typed HTTP endpoint through the existing registration scope.
   *
   * @param method recognized HTTP method
   * @param path relative endpoint path
   * @param handler codec-backed endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public TypedRoutes route(
      String method, String path, TypedHandler handler, Consumer<Extensions> configuration) {
    routes.route(method, path, adapt(handler), configuration);
    return this;
  }

  /**
   * Registers a typed HTTP endpoint through the existing registration scope.
   *
   * @param method recognized HTTP method
   * @param handler codec-backed endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public TypedRoutes route(
      HttpMethods method, TypedHandler handler, Consumer<Extensions> configuration) {
    routes.route(method, adapt(handler), configuration);
    return this;
  }

  /**
   * Registers a typed HTTP endpoint through the existing registration scope.
   *
   * @param method recognized HTTP method
   * @param handler codec-backed endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public TypedRoutes route(
      String method, TypedHandler handler, Consumer<Extensions> configuration) {
    routes.route(method, adapt(handler), configuration);
    return this;
  }

  /**
   * Registers a typed HTTP endpoint through the existing registration scope.
   *
   * @param method recognized HTTP method
   * @param handler codec-backed endpoint handler
   * @return this scope
   */
  public TypedRoutes route(HttpMethods method, TypedHandler handler) {
    routes.route(method, adapt(handler));
    return this;
  }

  /**
   * Registers a typed HTTP endpoint through the existing registration scope.
   *
   * @param method recognized HTTP method
   * @param handler codec-backed endpoint handler
   * @return this scope
   */
  public TypedRoutes route(String method, TypedHandler handler) {
    routes.route(method, adapt(handler));
    return this;
  }

  /**
   * Registers a typed HTTP endpoint through the existing registration scope.
   *
   * @param method recognized HTTP method
   * @param path relative endpoint path
   * @param executor caller-owned asynchronous executor for the matched lifecycle
   * @param handler codec-backed endpoint handler
   * @return this scope
   */
  public TypedRoutes route(
      HttpMethods method, String path, ExecutorService executor, TypedHandler handler) {
    routes.route(method, path, executor, adapt(handler));
    return this;
  }

  /**
   * Registers a typed HTTP endpoint through the existing registration scope.
   *
   * @param method recognized HTTP method
   * @param path relative endpoint path
   * @param executor caller-owned asynchronous executor for the matched lifecycle
   * @param handler codec-backed endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public TypedRoutes route(
      HttpMethods method,
      String path,
      ExecutorService executor,
      TypedHandler handler,
      Consumer<Extensions> configuration) {
    routes.route(method, path, executor, adapt(handler), configuration);
    return this;
  }

  /**
   * Registers a typed HTTP endpoint through the existing registration scope.
   *
   * @param method recognized HTTP method
   * @param executor caller-owned asynchronous executor for the matched lifecycle
   * @param handler codec-backed endpoint handler
   * @return this scope
   */
  public TypedRoutes route(HttpMethods method, ExecutorService executor, TypedHandler handler) {
    routes.route(method, executor, adapt(handler));
    return this;
  }

  /**
   * Registers a typed HTTP endpoint through the existing registration scope.
   *
   * @param method recognized HTTP method
   * @param executor caller-owned asynchronous executor for the matched lifecycle
   * @param handler codec-backed endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public TypedRoutes route(
      HttpMethods method,
      ExecutorService executor,
      TypedHandler handler,
      Consumer<Extensions> configuration) {
    routes.route(method, executor, adapt(handler), configuration);
    return this;
  }

  /**
   * Registers a typed HTTP endpoint through the existing registration scope.
   *
   * @param method recognized HTTP method
   * @param path relative endpoint path
   * @param executor caller-owned asynchronous executor for the matched lifecycle
   * @param handler codec-backed endpoint handler
   * @return this scope
   */
  public TypedRoutes route(
      String method, String path, ExecutorService executor, TypedHandler handler) {
    routes.route(method, path, executor, adapt(handler));
    return this;
  }

  /**
   * Registers a typed HTTP endpoint through the existing registration scope.
   *
   * @param method recognized HTTP method
   * @param path relative endpoint path
   * @param executor caller-owned asynchronous executor for the matched lifecycle
   * @param handler codec-backed endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public TypedRoutes route(
      String method,
      String path,
      ExecutorService executor,
      TypedHandler handler,
      Consumer<Extensions> configuration) {
    routes.route(method, path, executor, adapt(handler), configuration);
    return this;
  }

  /**
   * Registers a typed HTTP endpoint through the existing registration scope.
   *
   * @param method recognized HTTP method
   * @param executor caller-owned asynchronous executor for the matched lifecycle
   * @param handler codec-backed endpoint handler
   * @return this scope
   */
  public TypedRoutes route(String method, ExecutorService executor, TypedHandler handler) {
    routes.route(method, executor, adapt(handler));
    return this;
  }

  /**
   * Registers a typed HTTP endpoint through the existing registration scope.
   *
   * @param method recognized HTTP method
   * @param executor caller-owned asynchronous executor for the matched lifecycle
   * @param handler codec-backed endpoint handler
   * @param configuration local extension configuration
   * @return this scope
   */
  public TypedRoutes route(
      String method,
      ExecutorService executor,
      TypedHandler handler,
      Consumer<Extensions> configuration) {
    routes.route(method, executor, adapt(handler), configuration);
    return this;
  }

  /**
   * Ends registration for the original shared root; does not close the app or borrowed executors.
   */
  @Override
  public void close() {
    routes.close();
  }

  /**
   * Adapts only handler static types; both callbacks see the same exchange objects.
   *
   * @param handler typed endpoint handler
   * @return ordinary registration handler
   */
  private static Handler adapt(TypedHandler handler) {
    Objects.requireNonNull(handler);
    return (request, response) -> handler.handle((TypedRequest) request, (TypedResponse) response);
  }
}
