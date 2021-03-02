addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.7.4")

addSbtPlugin("com.dwijnand" % "sbt-dynver" % "4.1.1")

addSbtPlugin("com.lightbend.akka.grpc" % "sbt-akka-grpc" % "1.1.0-M2") // When updating, also update GrpcJavaVersion in build.sbt to be in sync

addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.15.0")

addSbtPlugin("com.lightbend.sbt" % "sbt-java-formatter" % "0.5.1")

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.4.2")

addSbtPlugin("de.heikoseeberger" % "sbt-header" % "5.6.0")

addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.10.0")

addSbtPlugin("org.foundweekends" % "sbt-bintray" % "0.5.6")

addSbtPlugin("io.cloudstate" % "sbt-cloudstate-paradox" % "0.1.3")

addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.10.0-RC1")
