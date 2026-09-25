package io.github.suppierk.shoostr;

import org.openjdk.jol.info.GraphLayout;
import org.openjdk.jol.vm.VM;

/** Prints retained route-table graphs using JOL rather than an allocation-rate estimate. */
public final class RouteFootprint {
  /** Prevents instances of this command-line footprint utility. */
  private RouteFootprint() {}

  /**
   * Reports each index independently, including its reachable endpoint data.
   *
   * @param args unused
   */
  static void main(String[] args) {
    System.out.println(VM.current().details());
    for (String shape : new String[] {"shared", "divergent", "prefix"}) {
      for (int count : new int[] {5, 50, 500, 5000}) {
        var map = RouteLookupBenchmark.registrations(count, shape);
        System.out.println("shape=" + shape + ", routes=" + count + ", index=map");
        System.out.println(GraphLayout.parseInstance(map).toFootprint());
        // Build from independent registrations so lazy map views cannot inflate the baseline.
        var radix =
            RadixRoutes.from(
                RouteLookupBenchmark.endpoints(RouteLookupBenchmark.registrations(count, shape)));
        System.out.println("shape=" + shape + ", routes=" + count + ", index=radix");
        System.out.println(GraphLayout.parseInstance(radix).toFootprint());
      }
    }
  }
}
