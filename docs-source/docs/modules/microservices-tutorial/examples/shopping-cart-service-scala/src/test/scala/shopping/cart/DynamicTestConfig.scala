package shopping.cart

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

object DynamicTestConfig {

  private def forGrpc(serviceName:String, grpcPort:Int) =
    s"""
     $serviceName.grpc {
         interface = "localhost"
         port = $grpcPort
     }
     """

  /** Binds artery to 127.0.0.1 on a random port */
  private def forArtery() =
   """
   akka.remote.artery.canonical {
     hostname = "127.0.0.1"
     port = 0
   }
   """

  /**
   * Prepares config to (1) run multiple actor systems in a single JVM,
   * (2) tune Akka Management to use the provided port,
   * (3) set up Discovery to locate other nodes, and
   * (4) wait cluster formation until all nodes are ready.
   *
   * @param managementPorts the management ports for all the nodes
   * @param managementPortIndex the port to be used by _this_ node.
   */
  def forCluster(serviceName:String, managementPorts: Seq[Int], managementPortIndex: Int) = {

    val localManagementPort = managementPorts(managementPortIndex)
    val endpoints = managementPorts
      .map { port => s"""{host = "127.0.0.1", port = $port }""" }
      .mkString(", ")

    val nodeCount = managementPorts.size

    val singleJvmCluster = // (1)
      if (managementPorts.size > 1)
        "akka.cluster.jmx.multi-mbeans-in-same-jvm = on"
      else ""

    val management = s"akka.management.http.port = $localManagementPort" // (2)

    val hardcodedDiscovery = // (3)
      s"""
       akka.management.cluster.bootstrap.contact-point-discovery {
         service-name = "$serviceName"
         discovery-method = config

         # don't self-join until all $nodeCount have been started and probed sucessfully
         required-contact-point-nr = $nodeCount
       }

       akka.discovery.config.services {
         "$serviceName" {
           endpoints = [ $endpoints ]
         }
       }
       """

    val bootstrap = // and (4)
      s"""
       akka.management.cluster.bootstrap.contact-point-discovery {
         # boostrap filters ports with the same IP assuming they are
         # previous instances running on the same node
         # unless a port is specified
         port-name = "management"

         # config service discovery never changes
         stable-margin = 1 ms

         # bootstrap without all the nodes being up
         contact-with-all-contact-points = true
       }
       """

    s"""
      ${forArtery()}
      $singleJvmCluster
      $management
      $hardcodedDiscovery
      $bootstrap
      """
  }

  // -------------------------------------------------

  def clusteringConfig(serviceName: String,
                       managementPorts: Seq[Int],
                       managementPortIndex: Int): Config =
    ConfigFactory.parseString(forCluster(serviceName, managementPorts, managementPortIndex))


  // This can be extended with TLS ports, HTTP, etc.. and any port
  // binding open for blocking calls
  def endpointConfig(serviceName: String,
                     grpcPort: Int): Config =
    ConfigFactory.parseString(forGrpc(serviceName, grpcPort))

}
