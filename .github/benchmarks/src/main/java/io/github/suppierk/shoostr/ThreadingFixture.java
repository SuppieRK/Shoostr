package io.github.suppierk.shoostr;

import com.sun.net.httpserver.HttpServer;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import jdk.management.VirtualThreadSchedulerMXBean;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.strategy.AdaptiveExecutionStrategy;
import org.jspecify.annotations.Nullable;

/** Internal benchmark fixtures, not a supported endpoint execution API. */
public final class ThreadingFixture {
  private static final byte[] PLAIN = "Hello, World!".getBytes(StandardCharsets.UTF_8);
  private static final byte[] HASH_CHUNK = new byte[65536];

  static {
    Arrays.fill(HASH_CHUNK, (byte) 'x');
  }

  /** Prevents utility instances. */
  private ThreadingFixture() {}

  /**
   * Builds identical routes with one experimental execution wiring.
   *
   * @param model A, B, C, D, or inactive (A with an unused selected pool)
   * @param workload tiny, io, cpu, or mixed
   * @param port listener port
   * @param workers shared platform worker count, not additional CPU capacity
   * @return unstarted app, which owns its accepted executors
   * @throws IllegalArgumentException if fixture settings are invalid
   */
  public static Shoostr create(String model, String workload, int port, int workers) {
    if (!Set.of("A", "B", "C", "D", "inactive").contains(model)
        || !Set.of("tiny", "io", "cpu", "mixed").contains(workload)
        || workers < 1) {
      throw new IllegalArgumentException("Unknown model/workload or invalid worker count");
    }

    Shoostr app;
    ExecutionSettings settings;
    if ("A".equals(model)) {
      app = new Shoostr(Options.defaults().withPort(port));
      settings = null;
    } else {
      var transport = new QueuedThreadPool(16, 8);
      transport.setReservedThreads(0);
      var selected = "D".equals(model);
      var paths =
          switch (workload) {
            case "tiny" -> Set.of("/tiny", "/api/users/{id}", "/kind/tiny");
            case "io" -> Set.of("/io", "/kind/io");
            default -> Set.of("/cpu", "/kind/cpu");
          };
      var execution =
          new ExecutionSettings(
              "inactive".equals(model) ? new ProducerThreadPool() : transport,
              !"C".equals(model),
              selected || "inactive".equals(model)
                  ? Executors.newFixedThreadPool(
                      workers,
                      Thread.ofPlatform()
                          .name("matched-worker-", 0)
                          .inheritInheritableThreadLocals(false)
                          .factory())
                  : null,
              selected ? paths : Set.of());
      app = new Shoostr(Options.defaults().withPort(port), execution);
      settings = execution;
    }

    if (Boolean.getBoolean("shoostr.benchmark.diagnostics")) {
      app.modifyServer(
          server ->
              Thread.ofPlatform()
                  .daemon()
                  .name("threading-diagnostics")
                  .start(() -> diagnostics(server, settings)));
    }

    app.routes().get("/tiny", (_, response) -> response.body("text/plain;charset=utf-8", PLAIN));
    app.routes()
        .get(
            "/api/users/{id}",
            (request, response) -> response.text(request.pathParam("id").orElseThrow()));
    app.routes().get("/cpu", (_, response) -> response.text(hash()));
    var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    var downstream =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:18081/io"))
            .timeout(Duration.ofSeconds(5))
            .build();
    app.extensions(
        new Extension<Void>() {
          /** {@inheritDoc} */
          @Override
          public void close() {
            client.close();
          }
        });
    app.routes()
        .get(
            "/io",
            (_, response) -> {
              var result = client.send(downstream, HttpResponse.BodyHandlers.ofByteArray());
              if (result.statusCode() != 200) {
                throw new IllegalStateException("Downstream fixture failed");
              }

              response.body("text/plain;charset=utf-8", result.body());
            });
    for (var name : Set.of("tiny", "io", "cpu")) {
      app.routes()
          .get(
              "/kind/" + name,
              (_, response) -> response.text(Boolean.toString(Thread.currentThread().isVirtual())));
    }
    app.routes().get("/ready", (_, response) -> response.text("ready"));
    return app;
  }

  /**
   * Hashes exactly four MiB of ASCII x, with a fresh digest and consumed output per operation.
   *
   * @return SHA-256 hex result
   * @throws java.security.NoSuchAlgorithmException if the JDK provider lacks SHA-256
   */
  public static String hash() throws NoSuchAlgorithmException {
    var digest = MessageDigest.getInstance("SHA-256");
    for (int index = 0; index < 64; index++) {
      digest.update(HASH_CHUNK);
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  /**
   * Samples supported native scheduler/pool/strategy counters only during profiling trials.
   *
   * @param server native transport container
   * @param settings selected experiment, or null for A
   */
  private static void diagnostics(Server server, @Nullable ExecutionSettings settings) {
    var scheduler = ManagementFactory.getPlatformMXBean(VirtualThreadSchedulerMXBean.class);

    try {
      do {
        Thread.sleep(1000);
        var pool = server.getThreadPool();
        var poolDetail =
            pool instanceof QueuedThreadPool queued
                ? " queue="
                    + queued.getQueueSize()
                    + " leased="
                    + queued.getLeasedThreads()
                    + " max="
                    + queued.getMaxThreads()
                : " queue=unsupported leased=unsupported";
        var workerDetail =
            settings != null && settings.platformWorkers() instanceof ThreadPoolExecutor workers
                ? " workers="
                    + workers.getPoolSize()
                    + " active="
                    + workers.getActiveCount()
                    + " workerQueue="
                    + workers.getQueue().size()
                : " workers=none";
        System.out.println(
            "SCHEDULER timestamp="
                + System.currentTimeMillis()
                + " parallelism="
                + scheduler.getParallelism()
                + " carriers="
                + scheduler.getPoolSize()
                + " mounted="
                + scheduler.getMountedVirtualThreadCount()
                + " queued="
                + scheduler.getQueuedVirtualThreadCount()
                + " transport="
                + pool.getThreads()
                + poolDetail
                + workerDetail);
        var strategies = server.getContainedBeans(AdaptiveExecutionStrategy.class);
        System.out.println("ADAPTIVE strategies=" + strategies.size());
        for (var strategy : strategies) {
          System.out.println(
              "ADAPTIVE PC="
                  + strategy.getPCTasksConsumed()
                  + " PIC="
                  + strategy.getPICTasksExecuted()
                  + " PEC="
                  + strategy.getPECTasksExecuted()
                  + " EPC="
                  + strategy.getEPCTasksConsumed());
        }
      } while (!server.isStopped());
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Starts one server or the independently monitored, fixed-delay downstream process.
   *
   * @param args model/workload/port/workers, or downstream/port
   * @throws Exception if startup or processing fails
   */
  static void main(String[] args) throws Exception {
    if ("downstream".equals(args[0])) {
      var server =
          HttpServer.create(
              new InetSocketAddress(InetAddress.ofLiteral("127.0.0.1"), Integer.parseInt(args[1])),
              0);
      var executor = Executors.newVirtualThreadPerTaskExecutor();
      server.setExecutor(executor);
      server.createContext(
          "/io",
          exchange -> {
            try (exchange) {
              try {
                Thread.sleep(20);
              } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                return;
              }

              var bytes = "pong".getBytes(StandardCharsets.UTF_8);
              exchange.sendResponseHeaders(200, bytes.length);
              exchange.getResponseBody().write(bytes);
            }
          });
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    server.stop(0);
                    executor.shutdownNow();
                  }));
      server.start();
      System.out.println("READY " + args[1]);
      new CountDownLatch(1).await();
      return;
    }

    try (var app = create(args[0], args[1], Integer.parseInt(args[2]), Integer.parseInt(args[3]))) {
      app.start();
      System.out.println("READY " + args[2] + " MODEL=" + args[0] + " WORKLOAD=" + args[1]);
      new CountDownLatch(1).await();
    }
  }
}
