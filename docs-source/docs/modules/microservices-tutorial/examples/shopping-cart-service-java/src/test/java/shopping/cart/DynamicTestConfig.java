package shopping.cart;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import java.sql.SQLOutput;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/** */
public class DynamicTestConfig {

  private static String forGrpc(String serviceName, int grpcPort) {
    return String.format(
        "%s.grpc { interface = \"localhost\" , port = %d }", serviceName, grpcPort);
  }

  /**
   * Prepares config to (1) run multiple actor systems in a single JVM, (2) tune Akka Management to
   * use the provided port, and (3) set up Discovery to locate other nodes,
   *
   * @param managementPorts the management ports for all the nodes
   * @param managementPortIndex the port to be used by _this_ node.
   */
  private static String forCluster(
      String serviceName, List<Integer> managementPorts, int managementPortIndex) {
    int localManagementPort = managementPorts.get(managementPortIndex);
    String endpoints =
        managementPorts.stream()
            .map(i -> i.toString())
            .map(
                portAsString -> String.format("{ host = \"127.0.0.1\" , port = %s }", portAsString))
            .collect(Collectors.joining(", "));
    int nodeCount = managementPorts.size();

    String singleJvmCluster =
        (nodeCount > 1) ? "akka.cluster.jmx.multi-mbeans-in-same-jvm = on" : ""; // (1)

    String management = "akka.management.http.port = " + localManagementPort; // (2)

    String hardcodedDiscovery = hardcodedDiscovery(serviceName, nodeCount, endpoints);

    return singleJvmCluster + "\n\n" + management + "\n\n" + hardcodedDiscovery + "\n\n";
  }

  private static String hardcodedDiscovery(String serviceName, int nodeCount, String endpoints) {
    return String.format(
            "akka.management.cluster.bootstrap.contact-point-discovery { "
                + "\n"
                + "  service-name = \"%s\" "
                + "\n"
                + "  discovery-method = config "
                + "\n"
                + "  "
                + "\n"
                + "  # don't self-join until all %s have been started and probed sucessfully "
                + "\n"
                + "  required-contact-point-nr = %s "
                + "\n" +
            "}"+ "\n\n" , serviceName, nodeCount, nodeCount)
        + String.format(
            "akka.discovery.config.services {"
                + "\n"
                + "  \"%s\" {"
                + "\n"
                + "    endpoints = [ %s ]"
                + "\n"
                + "  }"
                + "\n"
                + "}"
                + "\n\n",
            serviceName, endpoints);
  }

  // --------------------------------------------------------

  public static Config endpointConfig(String serviceName, int grpcPort) {
    return ConfigFactory.parseString(forGrpc(serviceName, grpcPort));
  }

  public static Config clusterConfig(String serviceName, Integer managementPort) {
    return clusterConfig(serviceName, Arrays.asList(managementPort), 0);
  }

  /**
   * Produces all the necessary settings to run a single-node or multi-node
   * Akka cluster in a single JVM. The result looks like the following sample:
   *
   * <pre>
   *   akka.cluster.jmx.multi-mbeans-in-same-jvm = on
   *
   *   akka.management.http.port = 52526
   *
   *   akka.management.cluster.bootstrap.contact-point-discovery {
   *     service-name = "shopping-cart-service"
   *     discovery-method = config
   *
   *     # don't self-join until all 3 have been started and probed sucessfully
   *     required-contact-point-nr = 3
   *   }
   *
   *   akka.discovery.config.services {
   *     "shopping-cart-service" {
   *       endpoints = [
   *          { host = "127.0.0.1" , port = 52526 },
   *          { host = "127.0.0.1" , port = 52527 },
   *          { host = "127.0.0.1" , port = 52528 } ]
   *      }
   *   }
   * </pre>
   *
   */
  public static Config clusterConfig(
      String serviceName, List<Integer> managementPorts, int managementPortIndex) {
    return ConfigFactory.parseString(forCluster(serviceName, managementPorts, managementPortIndex))
        .withFallback(ConfigFactory.parseResources("cluster-test.conf"));
  }
}
