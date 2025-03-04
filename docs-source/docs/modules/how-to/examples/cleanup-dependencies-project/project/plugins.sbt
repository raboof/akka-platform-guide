// tag::remove-grpc-plugin[]
addSbtPlugin("com.lightbend.akka.grpc" % "sbt-akka-grpc" % "2.1.2")
// end::remove-grpc-plugin[]

addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.8.1")
addSbtPlugin("com.dwijnand" % "sbt-dynver" % "4.1.1")

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.4.2")
