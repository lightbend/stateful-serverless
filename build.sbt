import java.io.File

import sbt.Keys.{developers, scmInfo}
import sbt.url

inThisBuild(
  Seq(
    organization := "io.cloudstate",
    scalaVersion := "2.13.3",
    organizationName := "Lightbend Inc.",
    organizationHomepage := Some(url("https://lightbend.com")),
    startYear := Some(2019),
    licenses += ("Apache-2.0", new URL("https://www.apache.org/licenses/LICENSE-2.0.txt")),
    homepage := Some(url("https://cloudstate.io")),
    scmInfo := Some(
        ScmInfo(
          url("https://github.com/cloudstateio/cloudstate"),
          "scm:git@github.com:cloudstateio/cloudstate.git"
        )
      ),
    developers := List(
        Developer(id = "jroper", name = "James Roper", email = "james@jazzy.id.au", url = url("https://jazzy.id.au")),
        Developer(id = "viktorklang",
                  name = "Viktor Klang",
                  email = "viktor.klang@gmail.com",
                  url = url("https://viktorklang.com"))
      ),
    scalafmtOnCompile := true,
    closeClassLoaders := false
  )
)

name := "cloudstate"

val ProtocolMajorVersion = 0
val ProtocolMinorVersion = 2

val GrpcJavaVersion = "1.30.2" // Note: sync with gRPC version in Akka gRPC
// Unfortunately we need to downgrade grpc-netty-shaded
// in the proxy until we have a fix to make it work with
// native-image
val GrpcNettyShadedVersion = "1.28.1"
val GraalAkkaVersion = "0.5.0"
val AkkaVersion = "2.6.9"
val AkkaHttpVersion = "10.1.12" // Note: sync with Akka HTTP version in Akka gRPC
val AkkaManagementVersion = "1.0.8"
val AkkaPersistenceCassandraVersion = "0.102"
val AkkaPersistenceJdbcVersion = "3.5.2"
val AkkaPersistenceSpannerVersion = "1.0.0-RC5"
val AkkaProjectionsVersion = "1.0.0"
val PrometheusClientVersion = "0.9.0"
val ScalaTestVersion = "3.0.8"
val ProtobufVersion = "3.11.4" // Note: sync with Protobuf version in Akka gRPC and ScalaPB
val JacksonDatabindVersion = "2.9.10.5"
val Slf4jSimpleVersion = "1.7.30"
val GraalVersion = "20.3.1"
val DockerBaseImageVersion = "adoptopenjdk/openjdk11:debianslim-jre"
val DockerBaseImageJavaLibraryPath = "${JAVA_HOME}/lib"

val excludeTheseDependencies: Seq[ExclusionRule] = Seq(
  ExclusionRule("io.netty", "netty"), // grpc-java is using grpc-netty-shaded
  ExclusionRule("io.aeron"), // we're using Artery-TCP
  ExclusionRule("org.agrona") // and we don't need this either
)

def akkaDependency(name: String, excludeThese: ExclusionRule*) =
  "com.typesafe.akka" %% name % AkkaVersion excludeAll ((excludeTheseDependencies ++ excludeThese): _*)

def akkaHttpDependency(name: String, excludeThese: ExclusionRule*) =
  "com.typesafe.akka" %% name % AkkaHttpVersion excludeAll ((excludeTheseDependencies ++ excludeThese): _*)

def akkaManagementDependency(name: String, excludeThese: ExclusionRule*) =
  "com.lightbend.akka.management" %% name % AkkaManagementVersion excludeAll ((excludeTheseDependencies ++ excludeThese): _*)

def akkaDiscoveryDependency(name: String, excludeThese: ExclusionRule*) =
  "com.lightbend.akka.discovery" %% name % AkkaManagementVersion excludeAll ((excludeTheseDependencies ++ excludeThese): _*)

def akkaPersistenceCassandraDependency(name: String, excludeThese: ExclusionRule*) =
  "com.typesafe.akka" %% name % AkkaPersistenceCassandraVersion excludeAll ((excludeTheseDependencies ++ excludeThese): _*)

def akkaProjectionsDependency(name: String, excludeThese: ExclusionRule*) =
  "com.lightbend.akka" %% name % AkkaProjectionsVersion excludeAll ((excludeTheseDependencies ++ excludeThese): _*)

def common: Seq[Setting[_]] = automateHeaderSettings(Compile, Test) ++ Seq(
  headerMappings := headerMappings.value ++ Seq(
      de.heikoseeberger.sbtheader.FileType("proto") -> HeaderCommentStyle.cppStyleLineComment,
      de.heikoseeberger.sbtheader.FileType("js") -> HeaderCommentStyle.cStyleBlockComment
    ),
  // Akka gRPC overrides the default ScalaPB setting including the file base name, let's override it right back.
  akkaGrpcCodeGeneratorSettings := Seq(),
  headerSources / excludeFilter := (headerSources / excludeFilter).value || "package-info.java",
  headerResources / excludeFilter := (headerResources / excludeFilter).value || {
      val googleProtos = ((baseDirectory in ThisBuild).value / "protocols" / "frontend" / "google").getCanonicalPath
      new SimpleFileFilter(_.getCanonicalPath startsWith googleProtos)
    },
  fork in Test := true,
  javaOptions in Test ++= Seq("-Xms1G", "-XX:+CMSClassUnloadingEnabled", "-XX:+UseConcMarkSweepGC")
)

// Include sources from the npm projects
headerSources in Compile ++= {
  val nodeSupport = baseDirectory.value / "node-support"
  val jsShoppingCart = baseDirectory.value / "samples" / "js-shopping-cart"

  Seq(
    nodeSupport / "src" ** "*.js",
    nodeSupport * "*.js",
    jsShoppingCart * "*.js",
    jsShoppingCart / "test" ** "*.js"
  ).flatMap(_.get)
}

lazy val root = (project in file("."))
  .enablePlugins(NoPublish)
  // Don't forget to add your sbt module here!
  // A missing module here can lead to failing Travis test results
  .aggregate(
    `protocols`,
    `proxy`,
    `jvm-support`,
    `java-support`,
    `java-support-docs`,
    `java-support-tck`,
    `java-eventsourced-shopping-cart`,
    `java-shopping-cart`,
    `java-pingpong`,
    `akka-client`,
    operator,
    testkit,
    `tck`,
    `graal-tools`
  )
  .settings(common)

lazy val protocols = (project in file("protocols"))
  .enablePlugins(NoPublish)
  .settings(
    name := "protocols",
    packageBin in Compile := {
      val base = baseDirectory.value
      val targetDir = target.value
      val releaseVersion = version.value
      val cloudstateProtocolsName = s"cloudstate-protocols-$releaseVersion"
      val cloudstateTCKProtocolsName = s"cloudstate-tck-protocols-$releaseVersion"
      val cloudstateProtocolsZip = targetDir / s"$cloudstateProtocolsName.zip"
      val cloudstateTCKProtocolsZip = targetDir / s"$cloudstateTCKProtocolsName.zip"

      def archiveStructure(topDirName: String, files: PathFinder): Seq[(File, String)] =
        files pair Path.relativeTo(base) map {
          case (f, s) => (f, s"$topDirName${File.separator}$s")
        }

      // Common Language Support Proto Dependencies
      IO.zip(
        archiveStructure(
          cloudstateProtocolsName,
          base / "frontend" ** "*.proto" +++
          base / "protocol" ** "*.proto"
        ),
        cloudstateProtocolsZip
      )

      // Common TCK Language Support Proto Dependencies
      IO.zip(
        archiveStructure(
          cloudstateTCKProtocolsName,
          base / "example" ** "*.proto" +++
          base / "tck" ** "*.proto"
        ),
        cloudstateTCKProtocolsZip
      )

      cloudstateProtocolsZip
    }
  )

lazy val proxyDockerBuild = settingKey[Option[String]](
  "Docker artifact name which gets overridden by the buildProxy command"
)

def dockerSettings: Seq[Setting[_]] = Seq(
  proxyDockerBuild := None,
  dockerUpdateLatest := true,
  dockerRepository := sys.props.get("docker.registry"),
  dockerUsername := sys.props.get("docker.username").orElse(Some("cloudstateio")).filter(_ != ""),
  dockerBaseImage := DockerBaseImageVersion,
  // when using tags like latest, uncomment below line, so that local cache will not be used.
  //  dockerBuildOptions += "--no-cache",
  dockerAlias := {
    val old = dockerAlias.value
    proxyDockerBuild.value match {
      case Some(dockerName) => old.withName(dockerName)
      case None => old
    }
  },
  dockerAliases := {
    val old = dockerAliases.value
    val single = dockerAlias.value
    // If a tag is explicitly configured, publish that, otherwise if it's a snapshot, just publish latest, otherwise,
    // publish both latest and the version
    sys.props.get("docker.tag") match {
      case some @ Some(_) => Seq(single.withTag(some))
      case _ if isSnapshot.value => Seq(single.withTag(Some("latest")))
      case _ => old
    }
  },
  // For projects that we publish using Docker, disable the generation of java/scaladocs
  publishArtifact in (Compile, packageDoc) := false
)

def proxySettings: Seq[Setting[_]] = Seq(
  // proxies are published to maven central, re-enable javadocs for sonatype requirements
  Compile / packageDoc / publishArtifact := true,
  Test / packageDoc / publishArtifact := false
)

def buildProxyHelp(commandName: String, name: String) =
  Help(
    (s"$commandName <task>",
     s"Execute the given docker scoped task (eg, publishLocal or publish) for the $name build of the proxy.")
  )

def buildProxyCommand(commandName: String, project: => Project, name: String, native: Boolean): Command = {
  val cn =
    if (native) s"dockerBuildNative$commandName"
    else s"dockerBuild$commandName"
  val imageName =
    if (native) s"native-$name"
    else name
  Command.single(
    cn,
    buildProxyHelp(cn, name)
  ) { (state, command) =>
    List(
      s"""set proxyDockerBuild in `${project.id}` := Some("cloudstate-proxy-$imageName")""",
      s"""set graalVMDockerPublishLocalBuild in ThisBuild := $native""",
      s"${project.id}/docker:$command",
      s"set proxyDockerBuild in `${project.id}` := None"
    ) ::: state
  }
}

commands ++= Seq(
  buildProxyCommand("Core", `proxy-core`, "core", true),
  buildProxyCommand("Core", `proxy-core`, "core", false),
  buildProxyCommand("Cassandra", `proxy-cassandra`, "cassandra", true),
  buildProxyCommand("Cassandra", `proxy-cassandra`, "cassandra", false),
  buildProxyCommand("Spanner", `proxy-spanner`, "spanner", true),
  buildProxyCommand("Spanner", `proxy-spanner`, "spanner", false),
  buildProxyCommand("Postgres", `proxy-postgres`, "postgres", true),
  buildProxyCommand("Postgres", `proxy-postgres`, "postgres", false),
  Command.single("dockerBuildAllNonNative", buildProxyHelp("dockerBuildAllNonNative", "all non native")) {
    (state, command) =>
      List("Core", "Cassandra", "Postgres", "Spanner")
        .map(c => s"dockerBuild$c $command") ::: state
  },
  Command.single("dockerBuildAllNative", buildProxyHelp("dockerBuildAllNative", "all native")) { (state, command) =>
    List("Core", "Cassandra", "Postgres", "Spanner")
      .map(c => s"dockerBuildNative$c $command") ::: state
  }
)

// Shared settings for native image and docker builds
def nativeImageDockerSettings: Seq[Setting[_]] = dockerSettings ++ Seq(
  // If this is Some(…): run the native-image generation inside a Docker image
  // If this is None: run the native-image generation using a local GraalVM installation
  graalVMVersion := Some("java11-" + GraalVersion), // make sure we use the java11 version
  graalVMNativeImageOptions ++= sharedNativeImageSettings({
      graalVMVersion.value match {
        case Some(_) => new File("/opt/docker/graal-resources/")
        case None => baseDirectory.value / "src" / "graal"
      }
    }, graalVMBuildServer.value),
  dockerEntrypoint := {
    val old = dockerEntrypoint.value
    if (graalVMDockerPublishLocalBuild.value) {
      old :+ s"-Djava.library.path=${DockerBaseImageJavaLibraryPath}"
    } else old
  }
)

def assemblySettings(jarName: String) =
  Seq(
    mainClass in assembly := (mainClass in Compile).value,
    assemblyJarName in assembly := jarName,
    test in assembly := {},
    // logLevel in assembly := Level.Debug,
    assemblyMergeStrategy in assembly := {
      /*ADD CUSTOMIZATIONS HERE*/
      case PathList("META-INF", "io.netty.versions.properties") => MergeStrategy.last
      case PathList(ps @ _*) if ps.last endsWith ".proto" => MergeStrategy.last
      case "module-info.class" => MergeStrategy.discard
      case "META-INF/native-image/com.typesafe.akka/dynamic-from-reference-conf/reflect-config.json" =>
        MergeStrategy.discard
      case x =>
        val oldStrategy = (assemblyMergeStrategy in assembly).value
        oldStrategy(x)
    }
  )

def sharedNativeImageSettings(targetDir: File, buildServer: Boolean) = Seq(
  //"-O1", // Optimization level
  "-H:ResourceConfigurationFiles=" + targetDir / "resource-config.json",
  "-H:ReflectionConfigurationFiles=" + targetDir / "reflect-config.json",
  "-H:DynamicProxyConfigurationFiles=" + targetDir / "proxy-config.json",
  "-H:IncludeResources=.+\\.conf",
  "-H:IncludeResources=.+\\.properties",
  "-H:+AllowVMInspection",
  // "-H:-RuntimeAssertions", // broken option in GraalVM 20.3
  "-H:+RemoveSaturatedTypeFlows", // GraalVM native-image 20.1 feature which speeds up the build time
  "-H:+ReportExceptionStackTraces",
  // "-H:+PrintAnalysisCallTree", // Uncomment to dump the entire call graph, useful for debugging native-image failing builds
  //"-H:ReportAnalysisForbiddenType=java.lang.invoke.MethodHandleImpl$AsVarargsCollector", // Uncomment and specify a type which will break analysis, useful to figure out reachability
  "-H:-PrintUniverse", // if "+" prints out all classes which are included
  "-H:-NativeArchitecture", // if "+" Compiles the native image to customize to the local CPU arch
  "-H:Class=" + "io.cloudstate.proxy.CloudStateProxyMain",
  // build server is disabled for docker builds by default (and native-image will use 80% of available memory in docker)
  // for local (non-docker) builds, can be disabled with `set graalVMBuildServer := false` to avoid potential cache problems
  if (buildServer) "--verbose-server" else "--no-server",
  //"--debug-attach=5005", // Debugger makes a ton of sense to use to debug SubstrateVM
  "--verbose",
  "--report-unsupported-elements-at-runtime", // Hopefully a self-explanatory flag FIXME comment this option out once AffinityPool is gone
  "--enable-url-protocols=http,https",
  "--allow-incomplete-classpath",
  "--no-fallback",
  "--initialize-at-build-time"
  + Seq(
    "org.slf4j",
    "scala",
    "akka.dispatch.affinity",
    "akka.util",
    "com.google.Protobuf"
  ).mkString("=", ",", ""),
  "--initialize-at-run-time" +
  Seq(
    "com.typesafe.config.impl.ConfigImpl",
    "com.typesafe.config.impl.ConfigImpl$EnvVariablesHolder",
    "com.typesafe.config.impl.ConfigImpl$SystemPropertiesHolder",
    "com.typesafe.config.impl.ConfigImpl$LoaderCacheHolder",
    "akka.actor.ActorCell", // Do not initialize the actor system until runtime (native-image)
    // These are to make up for the lack of shaded configuration for svm/native-image in grpc-netty-shaded
    // "com.sun.jndi.dns.DnsClient", // error: trying to change RUN_TIME from the command line to RERUN Contains Random references
    "com.typesafe.sslconfig.ssl.tracing.TracingSSLContext",
    "io.grpc.netty.shaded.io.netty.handler.codec.http2.Http2CodecUtil",
    "io.grpc.netty.shaded.io.netty.handler.codec.http2.DefaultHttp2FrameWriter",
    "io.grpc.netty.shaded.io.netty.handler.codec.http.HttpObjectEncoder",
    "io.grpc.netty.shaded.io.netty.handler.codec.http.websocketx.WebSocket00FrameEncoder",
    "io.grpc.netty.shaded.io.netty.handler.ssl.util.ThreadLocalInsecureRandom",
    "io.grpc.netty.shaded.io.netty.handler.ssl.ConscryptAlpnSslEngine",
    "io.grpc.netty.shaded.io.netty.handler.ssl.JettyNpnSslEngine",
    "io.grpc.netty.shaded.io.netty.handler.ssl.ReferenceCountedOpenSslEngine",
    "io.grpc.netty.shaded.io.netty.handler.ssl.JdkNpnApplicationProtocolNegotiator",
    "io.grpc.netty.shaded.io.netty.handler.ssl.ReferenceCountedOpenSslServerContext",
    "io.grpc.netty.shaded.io.netty.handler.ssl.ReferenceCountedOpenSslClientContext",
    "io.grpc.netty.shaded.io.netty.handler.ssl.util.BouncyCastleSelfSignedCertGenerator",
    "io.grpc.netty.shaded.io.netty.handler.ssl.ReferenceCountedOpenSslContext",
    "io.grpc.netty.shaded.io.netty.channel.socket.nio.NioSocketChannel"
  ).mkString("=", ",", "")
)

lazy val `proxy` = (project in file("proxy"))
  .enablePlugins(NoPublish)
  .aggregate(
    `proxy-core`,
    `proxy-cassandra`,
    `proxy-jdbc`,
    `proxy-postgres`,
    `proxy-spanner`,
    `proxy-tests`
  )

lazy val `proxy-core` = (project in file("proxy/core"))
  .enablePlugins(DockerPlugin, AkkaGrpcPlugin, AssemblyPlugin, GraalVMPlugin, BuildInfoPlugin)
  .dependsOn(
    `graal-tools` % Provided, // Only needed for compilation
    testkit % Test
  )
  .settings(
    common,
    name := "cloudstate-proxy-core",
    buildInfoKeys := Seq[BuildInfoKey](
        name,
        version,
        "protocolMajorVersion" -> ProtocolMajorVersion,
        "protocolMinorVersion" -> ProtocolMinorVersion
      ),
    buildInfoPackage := "io.cloudstate.proxy",
    dependencyOverrides += "io.grpc" % "grpc-netty-shaded" % GrpcNettyShadedVersion,
    libraryDependencies ++= Seq(
        // Since we exclude Aeron, we also exclude its transitive Agrona dependency, so we need to manually add it HERE
        "org.agrona" % "agrona" % "0.9.29",
        akkaDependency("akka-remote"),
        // For Eventing support of Google Pubsub
        "com.google.api.grpc" % "grpc-google-cloud-pubsub-v1" % "0.12.0" % "protobuf", // ApacheV2
        "io.grpc" % "grpc-auth" % GrpcJavaVersion, // ApacheV2
        "com.google.auth" % "google-auth-library-oauth2-http" % "0.15.0", // BSD 3-clause
        akkaDependency("akka-persistence"),
        akkaDependency("akka-persistence-query"),
        akkaDependency("akka-stream"),
        akkaDependency("akka-slf4j"),
        akkaDependency("akka-discovery"),
        akkaDependency("akka-cluster-typed"),
        akkaHttpDependency("akka-http"),
        akkaHttpDependency("akka-http-spray-json"),
        akkaHttpDependency("akka-http-core"),
        akkaHttpDependency("akka-http2-support"),
        akkaDependency("akka-cluster-sharding", ExclusionRule("org.lmdbjava", "lmdbjava")),
        akkaManagementDependency("akka-management-cluster-bootstrap"),
        akkaDiscoveryDependency("akka-discovery-kubernetes-api"),
        akkaProjectionsDependency("akka-projection-core"),
        akkaProjectionsDependency("akka-projection-eventsourced"),
        akkaProjectionsDependency("akka-projection-testkit"), // Needed for in memory support
        "com.google.protobuf" % "protobuf-java" % ProtobufVersion % "protobuf",
        "com.google.protobuf" % "protobuf-java-util" % ProtobufVersion,
        "org.scalatest" %% "scalatest" % ScalaTestVersion % Test,
        akkaDependency("akka-testkit") % Test,
        akkaDependency("akka-stream-testkit") % Test,
        akkaHttpDependency("akka-http-testkit") % Test,
        "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf",
        "io.prometheus" % "simpleclient" % PrometheusClientVersion,
        "io.prometheus" % "simpleclient_common" % PrometheusClientVersion,
        "org.slf4j" % "slf4j-simple" % Slf4jSimpleVersion
        //"ch.qos.logback"                 % "logback-classic"                   % "1.2.3", // Doesn't work well with SubstrateVM: https://github.com/vmencik/akka-graal-native/blob/master/README.md#logging
      ),
    PB.protoSources in Compile ++= {
      val baseDir = (baseDirectory in ThisBuild).value / "protocols"
      Seq(baseDir / "frontend", baseDir / "protocol")
    },
    // For Google Cloud Pubsub API
    PB.protoSources in Compile += target.value / "protobuf_external" / "google" / "pubsub" / "v1",
    mainClass in Compile := Some("io.cloudstate.proxy.CloudStateProxyMain"),
    fork in run := true,
    // In memory journal by default
    javaOptions in run ++= Seq("-Dconfig.resource=dev-mode.conf"),
    assemblySettings("akka-proxy.jar"),
    nativeImageDockerSettings,
    proxySettings
  )

lazy val `proxy-spanner` = (project in file("proxy/spanner"))
  .enablePlugins(DockerPlugin, GraalVMPlugin)
  .dependsOn(
    `proxy-core`,
    `graal-tools` % Provided // only needed for compilation
  )
  .settings(
    common,
    name := "cloudstate-proxy-spanner",
    dependencyOverrides += "io.grpc" % "grpc-netty-shaded" % GrpcNettyShadedVersion,
    libraryDependencies ++= Seq(
        "com.lightbend.akka" %% "akka-persistence-spanner" % AkkaPersistenceSpannerVersion,
        akkaDependency("akka-cluster-typed"), // Transitive dependency of akka-persistence-spanner
        akkaDependency("akka-persistence-typed"), // Transitive dependency of akka-persistence-spanner
        akkaDependency("akka-actor-testkit-typed") % Test,
        "org.scalatest" %% "scalatest" % ScalaTestVersion % Test
      ),
    fork in run := true,
    mainClass in Compile := Some("io.cloudstate.proxy.spanner.CloudstateSpannerProxyMain"),
    assemblySettings("akka-proxy.jar"),
    nativeImageDockerSettings,
    graalVMNativeImageOptions ++= Seq(),
    proxySettings
  )

lazy val `proxy-cassandra` = (project in file("proxy/cassandra"))
  .enablePlugins(DockerPlugin, GraalVMPlugin)
  .dependsOn(
    `proxy-core`,
    `graal-tools` % Provided // only needed for compilation
  )
  .settings(
    common,
    name := "cloudstate-proxy-cassandra",
    dependencyOverrides += "io.grpc" % "grpc-netty-shaded" % GrpcNettyShadedVersion,
    libraryDependencies ++= Seq(
        akkaPersistenceCassandraDependency("akka-persistence-cassandra", ExclusionRule("com.github.jnr")),
        akkaPersistenceCassandraDependency("akka-persistence-cassandra-launcher") % Test,
        "com.lightbend.akka" %% "akka-projection-cassandra" % AkkaProjectionsVersion
      ),
    fork in run := true,
    mainClass in Compile := Some("io.cloudstate.proxy.CloudStateProxyMain"),
    nativeImageDockerSettings,
    graalVMNativeImageOptions ++= Seq(
        "-H:IncludeResourceBundles=com.datastax.driver.core.Driver"
      ),
    proxySettings
  )

lazy val `proxy-jdbc` = (project in file("proxy/jdbc"))
  .dependsOn(
    `proxy-core`,
    `graal-tools` % Provided // only needed for compilation
  )
  .settings(
    common,
    name := "cloudstate-proxy-jdbc",
    dependencyOverrides += "io.grpc" % "grpc-netty-shaded" % GrpcNettyShadedVersion,
    libraryDependencies ++= Seq(
        "com.github.dnvriend" %% "akka-persistence-jdbc" % AkkaPersistenceJdbcVersion,
        "com.lightbend.akka" %% "akka-projection-slick" % AkkaProjectionsVersion,
        "org.scalatest" %% "scalatest" % ScalaTestVersion % Test
      ),
    fork in run := true,
    mainClass in Compile := Some("io.cloudstate.proxy.CloudStateProxyMain"),
    proxySettings
  )

lazy val `proxy-postgres` = (project in file("proxy/postgres"))
  .enablePlugins(DockerPlugin, GraalVMPlugin, AssemblyPlugin)
  .dependsOn(
    `proxy-jdbc`,
    `graal-tools` % Provided // only needed for compilation
  )
  .settings(
    common,
    name := "cloudstate-proxy-postgres",
    dependencyOverrides += "io.grpc" % "grpc-netty-shaded" % GrpcNettyShadedVersion,
    libraryDependencies ++= Seq(
        "org.postgresql" % "postgresql" % "42.2.6"
      ),
    fork in run := true,
    mainClass in Compile := Some("io.cloudstate.proxy.jdbc.CloudStateJdbcProxyMain"),
    // If run by sbt, run in dev mode
    javaOptions in run += "-Dcloudstate.proxy.dev-mode-enabled=true",
    assemblySettings("akka-proxy.jar"),
    nativeImageDockerSettings,
    graalVMNativeImageOptions ++= Seq(
        "--initialize-at-build-time"
        + Seq(
          "java.sql.DriverManager",
          "org.postgresql.Driver",
          "org.postgresql.util.SharedTimer"
        ).mkString("=", ",", "")
      ),
    proxySettings
  )

lazy val `proxy-tests` = (project in file("proxy/proxy-tests"))
  .enablePlugins(NoPublish)
  .dependsOn(`proxy-core`, `akka-client`, `java-pingpong`)
  .settings(
    common,
    name := "cloudstate-proxy-tests",
    fork in Test := System.getProperty("RUN_STRESS_TESTS", "false") == "true",
    parallelExecution in Test := false,
    baseDirectory in Test := (baseDirectory in ThisBuild).value,
    libraryDependencies ++= Seq(
        "org.scalatest" %% "scalatest" % ScalaTestVersion % Test,
        akkaDependency("akka-testkit") % Test
      )
  )

val compileK8sDescriptors = taskKey[File]("Compile the K8s descriptors into one")

lazy val operator = (project in file("operator"))
  .enablePlugins(JavaAppPackaging, DockerPlugin, NoPublish)
  .settings(
    common,
    name := "cloudstate-operator",
    libraryDependencies ++= Seq(
        akkaDependency("akka-stream"),
        akkaDependency("akka-slf4j"),
        akkaHttpDependency("akka-http"),
        "io.skuber" %% "skuber" % "2.4.0",
        "ch.qos.logback" % "logback-classic" % "1.2.3" // Doesn't work well with SubstrateVM, use slf4j-simple instead
      ),
    dockerSettings,
    dockerExposedPorts := Nil,
    compileK8sDescriptors := {
      val tag = version.value
      doCompileK8sDescriptors(
        baseDirectory.value / "deploy",
        baseDirectory.value,
        dockerRepository.value,
        dockerUsername.value,
        sys.props.get("docker.tag").getOrElse { if (isSnapshot.value) "latest" else tag },
        streams.value
      )
    }
  )

lazy val `jvm-support` = (project in file("jvm-support"))
  .enablePlugins(AkkaGrpcPlugin, BuildInfoPlugin)
  .dependsOn(testkit % Test)
  .settings(
    name := "cloudstate-jvm-support",
    dynverTagPrefix := "jvm-support-",
    common,
    crossPaths := false,
    publishMavenStyle := true,
    bintrayPackage := name.value,
    buildInfoKeys := Seq[BuildInfoKey](name, version),
    buildInfoPackage := "io.cloudstate.jvmsupport",
    // Generate javadocs by just including non generated Java sources
    sourceDirectories in (Compile, doc) := Seq((javaSource in Compile).value),
    sources in (Compile, doc) := {
      val javaSourceDir = (javaSource in Compile).value.getAbsolutePath
      (sources in (Compile, doc)).value.filter(_.getAbsolutePath.startsWith(javaSourceDir))
    },
    // javadoc (I think java 9 onwards) refuses to compile javadocs if it can't compile the entire source path.
    // but since we have java files depending on Scala files, we need to include ourselves on the classpath.
    dependencyClasspath in (Compile, doc) := (fullClasspath in Compile).value,
    javacOptions in (Compile, doc) ++= Seq(
        "-overview",
        ((javaSource in Compile).value / "overview.html").getAbsolutePath,
        "-notimestamp",
        "-doctitle",
        "Cloudstate Java Support"
      ),
    libraryDependencies ++= Seq(
        akkaDependency("akka-stream"),
        akkaDependency("akka-slf4j"),
        akkaDependency("akka-discovery"),
        akkaHttpDependency("akka-http"),
        akkaHttpDependency("akka-http-spray-json"),
        akkaHttpDependency("akka-http-core"),
        akkaHttpDependency("akka-http2-support"),
        "com.google.protobuf" % "protobuf-java" % ProtobufVersion % "protobuf",
        "com.google.protobuf" % "protobuf-java-util" % ProtobufVersion,
        "org.scalatest" %% "scalatest" % ScalaTestVersion % Test,
        akkaDependency("akka-testkit") % Test,
        akkaDependency("akka-stream-testkit") % Test,
        akkaHttpDependency("akka-http-testkit") % Test,
        "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf",
        "org.slf4j" % "slf4j-simple" % "1.7.26",
        "com.fasterxml.jackson.core" % "jackson-databind" % "2.9.9.3"
      ),
    javacOptions in Compile ++= Seq("-encoding", "UTF-8"),
    javacOptions in (Compile, compile) ++= Seq("-source", "1.8", "-target", "1.8"),
    akkaGrpcGeneratedSources in Compile := Seq(AkkaGrpc.Server),
    akkaGrpcGeneratedLanguages in Compile := Seq(AkkaGrpc.Scala), // FIXME should be Java, but here be dragons
    PB.protoSources in Compile ++= {
      val baseDir = (baseDirectory in ThisBuild).value / "protocols"
      Seq(baseDir / "protocol", baseDir / "frontend")
    },
    // We need to generate the java files for things like entity_key.proto so that downstream libraries can use them
    // without needing to generate them themselves
    PB.targets in Compile += PB.gens.java -> crossTarget.value / "akka-grpc" / "main",
    inConfig(Test)(
      Seq(
        akkaGrpcGeneratedSources := Seq(AkkaGrpc.Client),
        PB.protoSources += (baseDirectory in ThisBuild).value / "protocols" / "example",
        PB.targets += PB.gens.java -> crossTarget.value / "akka-grpc" / "test"
      )
    )
  )

lazy val `java-support` = (project in file("java-support"))
  .enablePlugins(AkkaGrpcPlugin, BuildInfoPlugin)
  .dependsOn(testkit % Test)
  .settings(
    name := "cloudstate-java-support",
    dynverTagPrefix := "java-support-",
    common,
    crossPaths := false,
    publishMavenStyle := true,
    bintrayPackage := name.value,
    buildInfoKeys := Seq[BuildInfoKey](
        name,
        version,
        "protocolMajorVersion" -> ProtocolMajorVersion,
        "protocolMinorVersion" -> ProtocolMinorVersion
      ),
    buildInfoPackage := "io.cloudstate.javasupport",
    // Generate javadocs by just including non generated Java sources
    sourceDirectories in (Compile, doc) := Seq((javaSource in Compile).value),
    sources in (Compile, doc) := {
      val javaSourceDir = (javaSource in Compile).value.getAbsolutePath
      (sources in (Compile, doc)).value.filter(_.getAbsolutePath.startsWith(javaSourceDir))
    },
    // javadoc (I think java 9 onwards) refuses to compile javadocs if it can't compile the entire source path.
    // but since we have java files depending on Scala files, we need to include ourselves on the classpath.
    dependencyClasspath in (Compile, doc) := (fullClasspath in Compile).value,
    javacOptions in (Compile, doc) ++= Seq(
        "-overview",
        ((javaSource in Compile).value / "overview.html").getAbsolutePath,
        "--no-module-directories",
        "-notimestamp",
        "-doctitle",
        "Cloudstate Java Support"
      ),
    libraryDependencies ++= Seq(
        akkaDependency("akka-stream"),
        akkaDependency("akka-slf4j"),
        akkaDependency("akka-discovery"),
        akkaHttpDependency("akka-http"),
        akkaHttpDependency("akka-http-spray-json"),
        akkaHttpDependency("akka-http-core"),
        akkaHttpDependency("akka-http2-support"),
        "com.google.protobuf" % "protobuf-java" % ProtobufVersion % "protobuf",
        "com.google.protobuf" % "protobuf-java-util" % ProtobufVersion,
        "org.scalatest" %% "scalatest" % ScalaTestVersion % Test,
        akkaDependency("akka-testkit") % Test,
        akkaDependency("akka-stream-testkit") % Test,
        akkaHttpDependency("akka-http-testkit") % Test,
        "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf",
        "org.slf4j" % "slf4j-simple" % Slf4jSimpleVersion,
        "com.fasterxml.jackson.core" % "jackson-databind" % JacksonDatabindVersion
      ),
    javacOptions in Compile ++= Seq("-encoding", "UTF-8"),
    javacOptions in (Compile, compile) ++= Seq("-source", "11", "-target", "11"),
    akkaGrpcGeneratedSources in Compile := Seq(AkkaGrpc.Server),
    akkaGrpcGeneratedLanguages in Compile := Seq(AkkaGrpc.Scala), // FIXME should be Java, but here be dragons
    PB.protoSources in Compile ++= {
      val baseDir = (baseDirectory in ThisBuild).value / "protocols"
      Seq(baseDir / "protocol", baseDir / "frontend")
    },
    // We need to generate the java files for things like entity_key.proto so that downstream libraries can use them
    // without needing to generate them themselves
    PB.targets in Compile += PB.gens.java -> crossTarget.value / "akka-grpc" / "main",
    inConfig(Test)(
      Seq(
        akkaGrpcGeneratedSources := Seq(AkkaGrpc.Client),
        PB.protoSources += (baseDirectory in ThisBuild).value / "protocols" / "example",
        PB.targets += PB.gens.java -> crossTarget.value / "akka-grpc" / "test"
      )
    )
  )

lazy val `java-support-docs` = (project in file("java-support/docs"))
  .dependsOn(`java-support` % Test)
  .enablePlugins(AkkaGrpcPlugin, AutomateHeaderPlugin, NoPublish)
  .settings(
    name := "cloudstate-java-docs",
    akkaGrpcGeneratedLanguages := Seq(AkkaGrpc.Java),
    Test / unmanagedSourceDirectories += sourceDirectory.value / "modules" / "java" / "examples",
    Test / PB.protoSources += (baseDirectory in ThisBuild).value / "protocols" / "frontend",
    Test / PB.protoSources += sourceDirectory.value / "modules" / "java" / "examples" / "proto",
    Test / PB.targets := Seq(PB.gens.java -> (Test / sourceManaged).value),
    Compile / javacOptions ++= Seq("-encoding", "UTF-8", "-source", "11", "-target", "11")
  )

lazy val `java-support-tck` = (project in file("java-support/tck"))
  .dependsOn(`java-support`)
  .enablePlugins(AkkaGrpcPlugin, AssemblyPlugin, JavaAppPackaging, DockerPlugin, AutomateHeaderPlugin, NoPublish)
  .settings(
    name := "cloudstate-java-tck",
    dynverTagPrefix := "java-support-",
    dockerSettings,
    mainClass in Compile := Some("io.cloudstate.javasupport.tck.JavaSupportTck"),
    akkaGrpcGeneratedLanguages := Seq(AkkaGrpc.Java),
    PB.protoSources in Compile += (baseDirectory in ThisBuild).value / "protocols" / "tck",
    javacOptions in Compile ++= Seq("-encoding", "UTF-8", "-source", "11", "-target", "11"),
    assemblySettings("cloudstate-java-tck.jar")
  )

lazy val `java-eventsourced-shopping-cart` = (project in file("samples/java-eventsourced-shopping-cart"))
  .dependsOn(`java-support`)
  .enablePlugins(AkkaGrpcPlugin, AssemblyPlugin, JavaAppPackaging, DockerPlugin, AutomateHeaderPlugin, NoPublish)
  .settings(
    name := "java-eventsourced-shopping-cart",
    dockerSettings,
    mainClass in Compile := Some("io.cloudstate.samples.eventsourced.shoppingcart.Main"),
    PB.generate in Compile := (PB.generate in Compile).dependsOn(PB.generate in (`java-support`, Compile)).value,
    akkaGrpcGeneratedLanguages := Seq(AkkaGrpc.Java),
    PB.protoSources in Compile ++= {
      val baseDir = (baseDirectory in ThisBuild).value / "protocols"
      Seq(baseDir / "frontend", baseDir / "example")
    },
    PB.targets in Compile := Seq(
        PB.gens.java -> (sourceManaged in Compile).value
      ),
    javacOptions in Compile ++= Seq("-encoding", "UTF-8", "-source", "11", "-target", "11"),
    assemblySettings("java-eventsourced-shopping-cart.jar")
  )

lazy val `java-shopping-cart` = (project in file("samples/java-shopping-cart"))
  .dependsOn(`java-support`)
  .enablePlugins(AkkaGrpcPlugin, AssemblyPlugin, JavaAppPackaging, DockerPlugin, AutomateHeaderPlugin, NoPublish)
  .settings(
    name := "java-shopping-cart",
    dockerSettings,
    mainClass in Compile := Some("io.cloudstate.samples.shoppingcart.Main"),
    PB.generate in Compile := (PB.generate in Compile).dependsOn(PB.generate in (`java-support`, Compile)).value,
    akkaGrpcGeneratedLanguages := Seq(AkkaGrpc.Java),
    PB.protoSources in Compile ++= {
      val baseDir = (baseDirectory in ThisBuild).value / "protocols"
      Seq(baseDir / "frontend", baseDir / "example")
    },
    PB.targets in Compile := Seq(
        PB.gens.java -> (sourceManaged in Compile).value
      ),
    javacOptions in Compile ++= Seq("-encoding", "UTF-8", "-source", "11", "-target", "11"),
    assemblySettings("java-shopping-cart.jar")
  )

lazy val `java-pingpong` = (project in file("samples/java-pingpong"))
  .dependsOn(`java-support`)
  .enablePlugins(AkkaGrpcPlugin, AssemblyPlugin, JavaAppPackaging, DockerPlugin, AutomateHeaderPlugin, NoPublish)
  .settings(
    name := "java-pingpong",
    dockerSettings,
    mainClass in Compile := Some("io.cloudstate.samples.pingpong.Main"),
    PB.generate in Compile := (PB.generate in Compile).dependsOn(PB.generate in (`java-support`, Compile)).value,
    akkaGrpcGeneratedLanguages := Seq(AkkaGrpc.Java),
    PB.protoSources in Compile ++= {
      val baseDir = (baseDirectory in ThisBuild).value / "protocols"
      Seq(baseDir / "frontend", (sourceDirectory in Compile).value / "protos")
    },
    PB.targets in Compile := Seq(
        PB.gens.java -> (sourceManaged in Compile).value
      ),
    javacOptions in Compile ++= Seq("-encoding", "UTF-8", "-source", "11", "-target", "11"),
    assemblySettings("java-pingpong.jar")
  )

lazy val `akka-client` = (project in file("samples/akka-client"))
  .enablePlugins(AkkaGrpcPlugin, NoPublish)
  .settings(
    common,
    name := "akka-client",
    fork in run := true,
    libraryDependencies ++= Seq(
        akkaDependency("akka-persistence"),
        akkaDependency("akka-stream"),
        akkaDependency("akka-discovery"),
        akkaHttpDependency("akka-http"),
        akkaHttpDependency("akka-http-spray-json"),
        akkaHttpDependency("akka-http-core"),
        akkaHttpDependency("akka-http2-support"),
        "com.google.protobuf" % "protobuf-java" % ProtobufVersion % "protobuf",
        "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf"
      ),
    PB.protoSources in Compile ++= {
      val baseDir = (baseDirectory in ThisBuild).value / "protocols"
      Seq(baseDir / "frontend", baseDir / "example")
    }
  )

lazy val `load-generator` = (project in file("samples/js-shopping-cart-load-generator"))
  .enablePlugins(JavaAppPackaging, DockerPlugin, NoPublish)
  .dependsOn(`akka-client`)
  .settings(
    common,
    name := "js-shopping-cart-load-generator",
    dockerSettings,
    dockerExposedPorts := Nil
  )

lazy val `testkit` = (project in file("testkit"))
  .enablePlugins(AkkaGrpcPlugin, BuildInfoPlugin)
  .settings(
    common,
    name := "cloudstate-testkit",
    buildInfoKeys := Seq[BuildInfoKey](
        name,
        version,
        "protocolMajorVersion" -> ProtocolMajorVersion,
        "protocolMinorVersion" -> ProtocolMinorVersion
      ),
    buildInfoPackage := "io.cloudstate.testkit",
    libraryDependencies ++= Seq(
        akkaDependency("akka-stream-testkit"),
        "com.google.protobuf" % "protobuf-java" % ProtobufVersion % "protobuf",
        "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf"
      ),
    PB.protoSources in Compile += (baseDirectory in ThisBuild).value / "protocols" / "protocol"
  )

lazy val `tck` = (project in file("tck"))
  .enablePlugins(AkkaGrpcPlugin, JavaAppPackaging, DockerPlugin, NoPublish)
  .configs(IntegrationTest)
  .dependsOn(testkit)
  .settings(
    Defaults.itSettings,
    common,
    name := "cloudstate-tck",
    libraryDependencies ++= Seq(
        akkaDependency("akka-stream"),
        akkaDependency("akka-discovery"),
        akkaHttpDependency("akka-http"),
        akkaHttpDependency("akka-http-spray-json"),
        "com.google.protobuf" % "protobuf-java" % ProtobufVersion % "protobuf",
        "org.scalatest" %% "scalatest" % ScalaTestVersion,
        akkaDependency("akka-testkit")
      ),
    PB.protoSources in Compile ++= {
      val baseDir = (baseDirectory in ThisBuild).value / "protocols"
      Seq(baseDir / "protocol", baseDir / "frontend", baseDir / "tck")
    },
    dockerSettings,
    Compile / bashScriptDefines / mainClass := Some("org.scalatest.run"),
    bashScriptExtraDefines += "addApp io.cloudstate.tck.ConfiguredCloudstateTCK",
    headerSettings(IntegrationTest),
    automateHeaderSettings(IntegrationTest),
    fork in IntegrationTest := true,
    javaOptions in IntegrationTest := Seq("config.resource", "user.dir")
        .flatMap(key => sys.props.get(key).map(value => s"-D$key=$value")),
    parallelExecution in IntegrationTest := false,
    executeTests in IntegrationTest := (executeTests in IntegrationTest)
        .dependsOn(`proxy-core` / assembly, `java-support-tck` / assembly)
        .value
  )

lazy val `graal-tools` = (project in file("graal-tools"))
  .enablePlugins(GraalVMPlugin, AutomateHeaderPlugin, NoPublish)
  .settings(
    libraryDependencies ++= List(
        "org.graalvm.nativeimage" % "svm" % GraalVersion % "provided",
        // Adds configuration to let Graal Native Image (SubstrateVM) work
        "com.github.vmencik" %% "graal-akka-actor" % GraalAkkaVersion,
        "com.github.vmencik" %% "graal-akka-stream" % GraalAkkaVersion,
        "com.github.vmencik" %% "graal-akka-http" % GraalAkkaVersion,
        akkaDependency("akka-actor"),
        "com.google.protobuf" % "protobuf-java" % ProtobufVersion,
        "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion
      )
  )

def doCompileK8sDescriptors(dir: File,
                            targetDir: File,
                            registry: Option[String],
                            username: Option[String],
                            tag: String,
                            streams: TaskStreams): File = {

  val targetFileName = if (tag != "latest") s"cloudstate-$tag.yaml" else "cloudstate.yaml"
  val target = targetDir / targetFileName
  val useNativeBuilds = sys.props.get("use.native.builds").forall(_ == "true")

  val files = ((dir / "crds") * "*.yaml").get ++
    (dir * "*.yaml").get.sortBy(_.getName)

  val fullDescriptor = files.map(IO.read(_)).mkString("\n---\n")

  val registryAndUsername = (registry.toSeq ++ username :+ "").mkString("/")
  val substitutedDescriptor = "cloudstateio/(cloudstate-.*):latest".r.replaceAllIn(fullDescriptor, m => {
    val artifact =
      if (useNativeBuilds) m.group(1)
      else m.group(1).replace("-native", "")
    s"$registryAndUsername$artifact:$tag"
  })

  IO.write(target, substitutedDescriptor)
  streams.log.info("Generated YAML descriptor in " + target)
  target
}
