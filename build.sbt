import java.io.File
import java.util.Date

import com.typesafe.sbt.packager.docker.DockerChmodType
import sbt.Keys.{developers, scmInfo}
import sbt.url

inThisBuild(
  Seq(
    organization := "io.cloudstate",
    version := dynverGitDescribeOutput.value.mkVersion(versionFmt, "latest"),
    dynver := sbtdynver.DynVer.getGitDescribeOutput(new Date).mkVersion(versionFmt, "latest"),
    scalaVersion := "2.12.9",
    // Needed for our fork of skuber
    resolvers += Resolver.bintrayRepo("jroper", "maven"), // TODO: Remove once skuber has the required functionality
    // Needed for the fixed HTTP/2 connection cleanup version of akka-http
    resolvers += Resolver.bintrayRepo("akka", "snapshots"), // TODO: Remove once we're switching to akka-http 10.1.11
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
    sonatypeProfileName := "io.cloudstate",
    scalafmtOnCompile := true
  )
)

// Make sure the version doesn't change each time it gets built, this ensures we don't rebuild the native image
// every time we build a docker image based on it, since we actually build 3 different docker images for the proxy
// command.
def versionFmt(out: sbtdynver.GitDescribeOutput): String = {
  val dirtySuffix = if (out.isDirty()) "-dev" else ""
  if (out.isCleanAfterTag) out.ref.dropV.value
  else out.ref.dropV.value + out.commitSuffix.mkString("-", "-", "") + dirtySuffix
}

name := "cloudstate"

val GrpcJavaVersion = "1.22.1"
val GraalAkkaVersion = "0.4.1"
val AkkaVersion = "2.5.25"
val AkkaHttpVersion = "10.1.10+124-779795c4" // TODO: Remove once we're switching to akka-http 10.1.11
val AkkaManagementVersion = "1.0.1"
val AkkaPersistenceCassandraVersion = "0.96"
val PrometheusClientVersion = "0.6.0"
val ScalaTestVersion = "3.0.5"
val ProtobufVersion = "3.9.0"

def common: Seq[Setting[_]] = Seq(
  headerMappings := headerMappings.value ++ Seq(
      de.heikoseeberger.sbtheader.FileType("proto") -> HeaderCommentStyle.cppStyleLineComment,
      de.heikoseeberger.sbtheader.FileType("js") -> HeaderCommentStyle.cStyleBlockComment
    ),
  // Akka gRPC adds all protobuf files from the classpath to this, which we don't want because it includes
  // all the Google protobuf files which are already compiled and on the classpath by ScalaPB. So we set it
  // back to just our source directory.
  PB.protoSources in Compile := Seq(),
  PB.protoSources in Test := Seq(),
  // Akka gRPC overrides the default ScalaPB setting including the file base name, let's override it right back.
  akkaGrpcCodeGeneratorSettings := Seq(),
  excludeFilter in headerResources := HiddenFileFilter || GlobFilter("reflection.proto")
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
// Don't forget to add your sbt module here!
// A missing module here can lead to failing Travis test results
  .aggregate(`proxy-core`,
             `proxy-cassandra`,
             `proxy-postgres`,
             `java-support`,
             `scala-support`,
             `java-shopping-cart`,
             `akka-client`,
             operator,
             `tck`,
             docs)
  .settings(common)

lazy val protocols = (project in file("protocols"))
  .enablePlugins(ProtocPlugin)
  .settings(
    common,
    name := "protocols",
    libraryDependencies ++= Seq(
        "com.google.protobuf" % "protobuf-java" % ProtobufVersion % "protobuf"
      ),
    PB.protocOptions in Compile := Seq(
        "--doc_opt=markdown,index.md"
      ),
    PB.includePaths in Compile ++= Seq(), // Not used, for now
    PB.targets in Compile := Seq(
        PB.gens.plugin("doc") -> (sourceManaged in Compile).value // Requires that this is installed and on PATH: https://github.com/pseudomuto/protoc-gen-doc/releases/tag/v1.3.0
      ),
    PB.protoSources in Compile ++= {
      Seq(baseDirectory.value / "protocol")
    }
  )

lazy val docs = (project in file("docs"))
  .enablePlugins(ParadoxPlugin, ProtocPlugin)
  .dependsOn(`java-support` % Test, `protocols` % Compile)
  .settings(
    common,
    name := "Cloudstate Documentation",
    paradoxTheme := Some(builtinParadoxTheme("generic")),
    mappings in (Compile, paradox) ++= {
      val javaApiDocs = (doc in (`java-support`, Compile)).value

      // Run the npm docs build
      val nodeSupportDir = (baseDirectory in ThisBuild).value / "node-support"
      import sys.process._
      val rc = Process("npm run jsdoc", nodeSupportDir).run(streams.value.log).exitValue()
      if (rc != 0) sys.error(s"jsdoc failed with return code $rc")
      val javaScriptApiDocs = nodeSupportDir / "apidocs"

      ((javaApiDocs ** "*") pair Path.relativeTo(javaApiDocs)).map {
        case (file, path) => file -> s"user/lang/java/api/$path"
      } ++
      ((javaScriptApiDocs ** "*") pair Path.relativeTo(javaScriptApiDocs)).map {
        case (file, path) => file -> s"user/lang/javascript/api/$path"
      }
    },
    paradoxProperties in Compile ++= Map(
        "canonical.base_url" -> "https://cloudstate.io/docs/",
        "javadoc.io.cloudstate.javasupport.base_url" -> ".../user/lang/java/api/",
        "javadoc.link_style" -> "direct",
        "extref.jsdoc.base_url" -> ".../user/lang/javascript/api/module-cloudstate.%s",
        "cloudstate.version" -> "0.4.3", // hardcode, otherwise we'll end up with the wrong version in the docs
        "cloudstate.java-support.version" -> "0.4.3",
        "cloudstate.node-support.version" -> "0.0.1",
        "cloudstate.go-support.version" -> "0.1.0",
        "cloudstate.go.version" -> "1.13"
      ),
    paradoxNavigationDepth := 3,
    inConfig(Test)(
      sbtprotoc.ProtocPlugin.protobufConfigSettings ++ Seq(
        PB.protoSources += sourceDirectory.value / "proto",
        PB.targets += PB.gens.java -> sourceManaged.value
      )
    )
  )

lazy val proxyDockerBuild = settingKey[Option[(String, Option[String])]](
  "Docker artifact name and configuration file which gets overridden by the buildProxy command"
)
lazy val nativeImageDockerBuild =
  settingKey[Boolean]("Whether the docker image should be based on the native image or not.")

def dockerSettings: Seq[Setting[_]] = Seq(
  proxyDockerBuild := None,
  dockerUpdateLatest := true,
  dockerRepository := sys.props.get("docker.registry"),
  dockerUsername := sys.props.get("docker.username").orElse(Some("cloudstateio")).filter(_ != ""),
  dockerAlias := {
    val old = dockerAlias.value
    proxyDockerBuild.value match {
      case Some((dockerName, _)) => old.withName(dockerName)
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

def buildProxyHelp(commandName: String, name: String) =
  Help(
    (s"$commandName <task>",
     s"Execute the given docker scoped task (eg, publishLocal or publish) for the the $name build of the proxy.")
  )

def buildProxyCommand(commandName: String,
                      project: => Project,
                      name: String,
                      configResource: Option[String],
                      native: Boolean): Command = {
  val cn =
    if (native) s"dockerBuildNative$commandName"
    else s"dockerBuild$commandName"
  val imageName =
    if (native) s"native-$name"
    else name
  val configResourceSetting = configResource match {
    case Some(resource) => "Some(\"" + resource + "\")"
    case None => "None"
  }
  Command.single(
    cn,
    buildProxyHelp(cn, name)
  ) { (state, command) =>
    List(
      s"project ${project.id}",
      s"""set proxyDockerBuild := Some(("cloudstate-proxy-$imageName", $configResourceSetting))""",
      s"""set nativeImageDockerBuild := $native""",
      s"docker:$command",
      "set proxyDockerBuild := None",
      "project root"
    ) ::: state
  }
}

commands ++= Seq(
  buildProxyCommand("DevMode", `proxy-core`, "dev-mode", Some("dev-mode.conf"), true),
  buildProxyCommand("DevMode", `proxy-core`, "dev-mode", Some("dev-mode.conf"), false),
  buildProxyCommand("NoStore", `proxy-core`, "no-store", Some("no-store.conf"), true),
  buildProxyCommand("NoStore", `proxy-core`, "no-store", Some("no-store.conf"), false),
  buildProxyCommand("InMemory", `proxy-core`, "in-memory", Some("in-memory.conf"), true),
  buildProxyCommand("InMemory", `proxy-core`, "in-memory", Some("in-memory.conf"), false),
  buildProxyCommand("Cassandra", `proxy-cassandra`, "cassandra", None, true),
  buildProxyCommand("Cassandra", `proxy-cassandra`, "cassandra", None, false),
  buildProxyCommand("Postgres", `proxy-postgres`, "postgres", None, true),
  buildProxyCommand("Postgres", `proxy-postgres`, "postgres", None, false),
  Command.single("dockerBuildAllNonNative", buildProxyHelp("dockerBuildAllNonNative", "all non native")) {
    (state, command) =>
      List("DevMode", "NoStore", "InMemory", "Cassandra", "Postgres")
        .map(c => s"dockerBuild$c $command") ::: state
  },
  Command.single("dockerBuildAllNative", buildProxyHelp("dockerBuildAllNative", "all native")) { (state, command) =>
    List("DevMode", "NoStore", "InMemory", "Cassandra", "Postgres")
      .map(c => s"dockerBuildNative$c $command") ::: state
  }
)

// Shared settings for native image and docker builds
def nativeImageDockerSettings: Seq[Setting[_]] = dockerSettings ++ Seq(
  nativeImageDockerBuild := false,
  graalVMVersion := Some("19.1.1"), // FYI: Set this to None to make a local only native-image build
  graalVMNativeImageOptions ++= sharedNativeImageSettings,
  (mappings in Docker) := Def.taskDyn {
      if (nativeImageDockerBuild.value) {
        Def.task {
          Seq(
            (packageBin in GraalVMNativeImage).value -> s"${(defaultLinuxInstallLocation in Docker).value}/bin/${executableScriptName.value}"
          )
        }
      } else {
        Def.task {
          // This is copied from the native packager DockerPlugin, because I don't think a dynamic task can reuse the
          // old value of itself in the dynamic part.
          def renameDests(from: Seq[(File, String)], dest: String) =
            for {
              (f, path) <- from
              newPath = "%s/%s" format (dest, path)
            } yield (f, newPath)

          renameDests((mappings in Universal).value, (defaultLinuxInstallLocation in Docker).value)
        }
      }
    }.value,
  dockerBaseImage := "bitnami/java:11-prod",
  // Need to make sure it has group execute permission
  // Note I think this is leading to quite large docker images :(
  dockerChmodType := {
    val old = dockerChmodType.value
    if (nativeImageDockerBuild.value) {
      DockerChmodType.Custom("u+x,g+x")
    } else {
      old
    }
  },
  dockerEntrypoint := {
    val old = dockerEntrypoint.value
    val withLibraryPath = if (nativeImageDockerBuild.value) {
      old :+ "-Djava.library.path=/opt/bitnami/java/lib"
    } else old
    proxyDockerBuild.value match {
      case Some((_, Some(configResource))) => withLibraryPath :+ s"-Dconfig.resource=$configResource"
      case _ => withLibraryPath
    }
  }
)

def sharedNativeImageSettings = Seq(
  //"-O1", // Optimization level
  "-H:ResourceConfigurationFiles=/opt/graalvm/stage/resources/resource-config.json",
  "-H:ReflectionConfigurationFiles=/opt/graalvm/stage/resources/reflect-config.json",
  "-H:DynamicProxyConfigurationFiles=/opt/graalvm/stage/resources/proxy-config.json",
  "-H:IncludeResources=.+\\.conf",
  "-H:IncludeResources=.+\\.properties",
  "-H:+AllowVMInspection",
  "-H:-RuntimeAssertions",
  "-H:+ReportExceptionStackTraces",
  "-H:-PrintUniverse", // if "+" prints out all classes which are included
  "-H:-NativeArchitecture", // if "+" Compiles the native image to customize to the local CPU arch
  "-H:Class=" + "io.cloudstate.proxy.CloudStateProxyMain",
  "--verbose",
  //"--no-server", // Uncomment to not use the native-image build server, to avoid potential cache problems with builds
  //"--report-unsupported-elements-at-runtime", // Hopefully a self-explanatory flag
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
  "--initialize-at-run-time=" +
  Seq(
    "akka.protobuf.DescriptorProtos",
    // We want to delay initialization of these to load the config at runtime
    "com.typesafe.config.impl.ConfigImpl$EnvVariablesHolder",
    "com.typesafe.config.impl.ConfigImpl$SystemPropertiesHolder",
    // These are to make up for the lack of shaded configuration for svm/native-image in grpc-netty-shaded
    "com.sun.jndi.dns.DnsClient",
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
  ).mkString(",")
)

lazy val `proxy-core` = (project in file("proxy/core"))
  .enablePlugins(DockerPlugin, AkkaGrpcPlugin, JavaAgent, AssemblyPlugin, GraalVMPlugin, BuildInfoPlugin)
  .settings(
    common,
    name := "cloudstate-proxy-core",
    buildInfoKeys := Seq[BuildInfoKey](name, version),
    buildInfoPackage := "io.cloudstate.proxy",
    libraryDependencies ++= Seq(
        // Remove these explicit gRPC/netty dependencies once akka-grpc 0.7.1 is released and we've upgraded to using that
        "io.grpc" % "grpc-core" % GrpcJavaVersion,
        "io.grpc" % "grpc-netty-shaded" % GrpcJavaVersion,
        // Since we exclude Aeron, we also exclude its transitive Agrona dependency, so we need to manually add it HERE
        "org.agrona" % "agrona" % "0.9.29",
        // FIXME REMOVE THIS ONCE WE CAN HAVE OUR DEPS (grpc-netty-shaded, agrona, and protobuf-java respectively) DO THIS PROPERLY
        "org.graalvm.sdk" % "graal-sdk" % "19.1.1" % "provided", // Only needed for compilation
        "com.oracle.substratevm" % "svm" % "19.1.1" % "provided", // Only needed for compilation

        // Adds configuration to let Graal Native Image (SubstrateVM) work
        "com.github.vmencik" %% "graal-akka-actor" % GraalAkkaVersion % "provided", // Only needed for compilation
        "com.github.vmencik" %% "graal-akka-stream" % GraalAkkaVersion % "provided", // Only needed for compilation
        "com.github.vmencik" %% "graal-akka-http" % GraalAkkaVersion % "provided", // Only needed for compilation
        "com.typesafe.akka" %% "akka-remote" % AkkaVersion excludeAll (
          ExclusionRule("io.netty", "netty"), // grpc-java is using grpc-netty-shaded
          ExclusionRule("io.aeron"), // we're using Artery-TCP
          ExclusionRule("org.agrona"), // and we don't need this either
        ),
        "com.typesafe.akka" %% "akka-persistence" % AkkaVersion,
        "com.typesafe.akka" %% "akka-persistence-query" % AkkaVersion,
        "com.typesafe.akka" %% "akka-stream" % AkkaVersion,
        "com.typesafe.akka" %% "akka-slf4j" % AkkaVersion,
        "com.typesafe.akka" %% "akka-http" % AkkaHttpVersion,
        "com.typesafe.akka" %% "akka-http-spray-json" % AkkaHttpVersion,
        "com.typesafe.akka" %% "akka-http-core" % AkkaHttpVersion,
        "com.typesafe.akka" %% "akka-http2-support" % AkkaHttpVersion,
        "com.typesafe.akka" %% "akka-cluster-sharding" % AkkaVersion exclude ("org.lmdbjava", "lmdbjava"),
        "com.lightbend.akka.management" %% "akka-management-cluster-bootstrap" % AkkaManagementVersion excludeAll (
          ExclusionRule("io.netty", "netty"), // grpc-java is using grpc-netty-shaded
          ExclusionRule("io.aeron"), // we're using Artery-TCP
          ExclusionRule("org.agrona"), // and we don't need this either
        ),
        "com.lightbend.akka.discovery" %% "akka-discovery-kubernetes-api" % AkkaManagementVersion excludeAll (
          ExclusionRule("io.netty", "netty"), // grpc-java is using grpc-netty-shaded
          ExclusionRule("io.aeron"), // we're using Artery-TCP
          ExclusionRule("org.agrona"), // and we don't need this either
        ),
        "com.google.protobuf" % "protobuf-java" % ProtobufVersion % "protobuf",
        "com.google.protobuf" % "protobuf-java-util" % ProtobufVersion,
        "org.scalatest" %% "scalatest" % ScalaTestVersion % Test,
        "com.typesafe.akka" %% "akka-testkit" % AkkaVersion % Test,
        "com.typesafe.akka" %% "akka-stream-testkit" % AkkaVersion % Test,
        "com.typesafe.akka" %% "akka-http-testkit" % AkkaHttpVersion % Test,
        "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf",
        "io.prometheus" % "simpleclient" % PrometheusClientVersion,
        "io.prometheus" % "simpleclient_common" % PrometheusClientVersion,
        "org.slf4j" % "slf4j-simple" % "1.7.26"
        //"ch.qos.logback"                 % "logback-classic"                   % "1.2.3", // Doesn't work well with SubstrateVM: https://github.com/vmencik/akka-graal-native/blob/master/README.md#logging
      ),
    // Work around for https://github.com/akka/akka-grpc/pull/673
    (PB.targets in Compile) := {
      val old = (PB.targets in Compile).value
      val ct = crossTarget.value

      old.map(_.copy(outputPath = ct / "akka-grpc" / "main"))
    },
    PB.protoSources in Compile ++= {
      val baseDir = (baseDirectory in ThisBuild).value / "protocols"
      Seq(baseDir / "proxy", baseDir / "frontend", baseDir / "protocol", (sourceDirectory in Compile).value / "protos")
    },
    // This adds the test/protos dir and enables the ProtocPlugin to generate protos in the Test scope
    inConfig(Test)(
      sbtprotoc.ProtocPlugin.protobufConfigSettings ++ Seq(
        PB.protoSources ++= Seq(sourceDirectory.value / "protos"),
        akkaGrpcCodeGeneratorSettings := Seq(),
        akkaGrpcGeneratedSources := Seq(AkkaGrpc.Server, AkkaGrpc.Client)
      )
    ),
    javaAgents += "org.mortbay.jetty.alpn" % "jetty-alpn-agent" % "2.0.9" % "runtime;test",
    dockerSettings,
    fork in run := true,
    // In memory journal by default
    javaOptions in run ++= Seq("-Dconfig.resource=dev-mode.conf"),
    mainClass in assembly := Some("io.cloudstate.proxy.CloudStateProxyMain"),
    assemblyJarName in assembly := "akka-proxy.jar",
    test in assembly := {},
    // logLevel in assembly := Level.Debug,
    assemblyMergeStrategy in assembly := {
      /*ADD CUSTOMIZATIONS HERE*/
      case PathList("META-INF", "io.netty.versions.properties") => MergeStrategy.last
      case x =>
        val oldStrategy = (assemblyMergeStrategy in assembly).value
        oldStrategy(x)
    },
    nativeImageDockerSettings
  )

lazy val `proxy-cassandra` = (project in file("proxy/cassandra"))
  .enablePlugins(DockerPlugin, JavaAgent, GraalVMPlugin)
  .dependsOn(`proxy-core`)
  .settings(
    common,
    name := "cloudstate-proxy-cassandra",
    libraryDependencies ++= Seq(
        "com.typesafe.akka" %% "akka-persistence-cassandra" % AkkaPersistenceCassandraVersion excludeAll (
          ExclusionRule("io.aeron"), // we're using Artery-TCP
          ExclusionRule("org.agrona"), // and we don't need this either
          ExclusionRule("com.github.jnr"), // Can't native-image this, so we don't need this either
        ),
        "com.typesafe.akka" %% "akka-persistence-cassandra-launcher" % AkkaPersistenceCassandraVersion % Test,
        // FIXME REMOVE THIS ONCE WE CAN HAVE OUR DEPS (grpc-netty-shaded, agrona, and protobuf-java respectively) DO THIS PROPERLY
        "org.graalvm.sdk" % "graal-sdk" % "19.1.1" % "provided", // Only needed for compilation
        "com.oracle.substratevm" % "svm" % "19.1.1" % "provided", // Only needed for compilation

        // Adds configuration to let Graal Native Image (SubstrateVM) work
        "com.github.vmencik" %% "graal-akka-actor" % GraalAkkaVersion % "provided", // Only needed for compilation
        "com.github.vmencik" %% "graal-akka-stream" % GraalAkkaVersion % "provided", // Only needed for compilation
        "com.github.vmencik" %% "graal-akka-http" % GraalAkkaVersion % "provided" // Only needed for compilation
      ),
    fork in run := true,
    mainClass in Compile := Some("io.cloudstate.proxy.CloudStateProxyMain"),
    nativeImageDockerSettings,
    graalVMNativeImageOptions ++= Seq(
        "-H:IncludeResourceBundles=com.datastax.driver.core.Driver"
      )
  )

lazy val `proxy-jdbc` = (project in file("proxy/jdbc"))
  .dependsOn(`proxy-core`)
  .settings(
    common,
    name := "cloudstate-proxy-jdbc",
    libraryDependencies ++= Seq(
        "com.github.dnvriend" %% "akka-persistence-jdbc" % "3.5.2"
      ),
    fork in run := true,
    mainClass in Compile := Some("io.cloudstate.proxy.CloudStateProxyMain")
  )

lazy val `proxy-postgres` = (project in file("proxy/postgres"))
  .enablePlugins(DockerPlugin, JavaAgent, GraalVMPlugin, AssemblyPlugin)
  .dependsOn(`proxy-jdbc`)
  .settings(
    common,
    name := "cloudstate-proxy-postgres",
    libraryDependencies ++= Seq(
        "org.postgresql" % "postgresql" % "42.2.6",
        // FIXME REMOVE THIS ONCE WE CAN HAVE OUR DEPS (grpc-netty-shaded, agrona, and protobuf-java respectively) DO THIS PROPERLY
        "org.graalvm.sdk" % "graal-sdk" % "19.1.1" % "provided", // Only needed for compilation
        "com.oracle.substratevm" % "svm" % "19.1.1" % "provided", // Only needed for compilation

        // Adds configuration to let Graal Native Image (SubstrateVM) work
        "com.github.vmencik" %% "graal-akka-actor" % GraalAkkaVersion % "provided", // Only needed for compilation
        "com.github.vmencik" %% "graal-akka-stream" % GraalAkkaVersion % "provided", // Only needed for compilation
        "com.github.vmencik" %% "graal-akka-http" % GraalAkkaVersion % "provided" // Only needed for compilation
      ),
    fork in run := true,
    mainClass in Compile := Some("io.cloudstate.proxy.jdbc.CloudStateJdbcProxyMain"),
    // If run by sbt, run in dev mode
    javaOptions in run += "-Dcloudstate.proxy.dev-mode-enabled=true",
    mainClass in assembly := (mainClass in Compile).value,
    assemblyJarName in assembly := "akka-proxy-postgres.jar",
    test in assembly := {},
    // logLevel in assembly := Level.Debug,
    assemblyMergeStrategy in assembly := {
      /*ADD CUSTOMIZATIONS HERE*/
      case PathList("META-INF", "io.netty.versions.properties") => MergeStrategy.last
      case x =>
        val oldStrategy = (assemblyMergeStrategy in assembly).value
        oldStrategy(x)
    },
    nativeImageDockerSettings,
    graalVMNativeImageOptions ++= Seq(
        "--initialize-at-build-time"
        + Seq(
          "org.postgresql.Driver",
          "org.postgresql.util.SharedTimer"
        ).mkString("=", ",", "")
      )
  )

lazy val `proxy-tests` = (project in file("proxy/proxy-tests"))
  .dependsOn(`proxy-core`, `akka-client`)
  .settings(
    common,
    name := "cloudstate-proxy-tests",
    fork in Test := true,
    baseDirectory in Test := (baseDirectory in ThisBuild).value,
    libraryDependencies ++= Seq(
        "org.scalatest" %% "scalatest" % ScalaTestVersion % Test,
        "com.typesafe.akka" %% "akka-testkit" % AkkaVersion % Test
      )
  )

val compileK8sDescriptors = taskKey[File]("Compile the K8s descriptors into one")

lazy val operator = (project in file("operator"))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(
    common,
    name := "cloudstate-operator",
    // This is a publishLocal build of this PR https://github.com/doriordan/skuber/pull/268
    libraryDependencies ++= Seq(
        "com.typesafe.akka" %% "akka-stream" % AkkaVersion,
        "com.typesafe.akka" %% "akka-slf4j" % AkkaVersion,
        "com.typesafe.akka" %% "akka-http" % AkkaHttpVersion,
        "io.skuber" %% "skuber" % "2.2.0-jroper-1",
        "ch.qos.logback" % "logback-classic" % "1.2.3" // Doesn't work well with SubstrateVM, use "org.slf4j"           % "slf4j-simple"     % "1.7.26" instead
      ),
    dockerSettings,
    dockerBaseImage := "adoptopenjdk/openjdk8",
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

lazy val `java-support` = (project in file("java-support"))
  .enablePlugins(AkkaGrpcPlugin, BuildInfoPlugin)
  .settings(
    name := "cloudstate-java-support",
    common,
    crossPaths := false,
    publishMavenStyle := true,
    publishTo := sonatypePublishTo.value,
    buildInfoKeys := Seq[BuildInfoKey](name, version),
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
        "-notimestamp",
        "-doctitle",
        "Cloudstate Java Support"
      ),
    libraryDependencies ++= Seq(
        // Remove these explicit gRPC/netty dependencies once akka-grpc 0.7.1 is released and we've upgraded to using that
        "io.grpc" % "grpc-core" % GrpcJavaVersion,
        "io.grpc" % "grpc-netty-shaded" % GrpcJavaVersion,
        "com.typesafe.akka" %% "akka-stream" % AkkaVersion,
        "com.typesafe.akka" %% "akka-slf4j" % AkkaVersion,
        "com.typesafe.akka" %% "akka-discovery" % AkkaVersion,
        "com.typesafe.akka" %% "akka-http" % AkkaHttpVersion,
        "com.typesafe.akka" %% "akka-http-spray-json" % AkkaHttpVersion,
        "com.typesafe.akka" %% "akka-http-core" % AkkaHttpVersion,
        "com.typesafe.akka" %% "akka-http2-support" % AkkaHttpVersion,
        "com.google.protobuf" % "protobuf-java" % ProtobufVersion % "protobuf",
        "com.google.protobuf" % "protobuf-java-util" % ProtobufVersion,
        "org.scalatest" %% "scalatest" % ScalaTestVersion % Test,
        "com.typesafe.akka" %% "akka-testkit" % AkkaVersion % Test,
        "com.typesafe.akka" %% "akka-stream-testkit" % AkkaVersion % Test,
        "com.typesafe.akka" %% "akka-http-testkit" % AkkaHttpVersion % Test,
        "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf",
        "org.slf4j" % "slf4j-simple" % "1.7.26",
        "com.fasterxml.jackson.core" % "jackson-databind" % "2.9.9.3"
      ),
    javacOptions in Compile ++= Seq("-encoding", "UTF-8"),
    javacOptions in (Compile, compile) ++= Seq("-source", "1.8", "-target", "1.8"),
    akkaGrpcGeneratedSources in Compile := Seq(AkkaGrpc.Server),
    akkaGrpcGeneratedLanguages in Compile := Seq(AkkaGrpc.Scala), // FIXME should be Java, but here be dragons

    // Work around for https://github.com/akka/akka-grpc/pull/673
    (PB.targets in Compile) := {
      val old = (PB.targets in Compile).value
      val ct = crossTarget.value

      old.map(_.copy(outputPath = ct / "akka-grpc" / "main"))
    },
    PB.protoSources in Compile ++= {
      val baseDir = (baseDirectory in ThisBuild).value / "protocols"
      Seq(baseDir / "protocol", baseDir / "frontend")
    },
    // We need to generate the java files for things like entity_key.proto so that downstream libraries can use them
    // without needing to generate them themselves
    PB.targets in Compile += PB.gens.java -> crossTarget.value / "akka-grpc" / "main",
    inConfig(Test)(
      sbtprotoc.ProtocPlugin.protobufConfigSettings ++ Seq(
        PB.protoSources ++= {
          val baseDir = (baseDirectory in ThisBuild).value / "protocols"
          Seq(baseDir / "example")
        },
        PB.targets := Seq(
            PB.gens.java -> crossTarget.value / "akka-grpc" / "test"
          )
      )
    )
  )

lazy val `scala-support` = (project in file("scala-support"))
  .enablePlugins(AkkaGrpcPlugin, BuildInfoPlugin)
  .settings(
    name := "cloudstate-scala-support",
    common,
    crossPaths := false,
    publishMavenStyle := true,
    publishTo := sonatypePublishTo.value,
    buildInfoKeys := Seq[BuildInfoKey](name, version),
    buildInfoPackage := "io.cloudstate.scalasupport",
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
        "CloudState Scala Support"
      ),
    libraryDependencies ++= Seq(
        // Remove these explicit gRPC/netty dependencies once akka-grpc 0.7.1 is released and we've upgraded to using that
        "io.grpc" % "grpc-core" % GrpcJavaVersion,
        "io.grpc" % "grpc-netty-shaded" % GrpcJavaVersion,
        "com.typesafe.akka" %% "akka-stream" % AkkaVersion,
        "com.typesafe.akka" %% "akka-slf4j" % AkkaVersion,
        "com.typesafe.akka" %% "akka-discovery" % AkkaVersion,
        "com.typesafe.akka" %% "akka-http" % AkkaHttpVersion,
        "com.typesafe.akka" %% "akka-http-spray-json" % AkkaHttpVersion,
        "com.typesafe.akka" %% "akka-http-core" % AkkaHttpVersion,
        "com.typesafe.akka" %% "akka-http2-support" % AkkaHttpVersion,
        "com.google.protobuf" % "protobuf-java" % ProtobufVersion % "protobuf",
        "com.google.protobuf" % "protobuf-java-util" % ProtobufVersion,
        "org.scalatest" %% "scalatest" % ScalaTestVersion % Test,
        "com.typesafe.akka" %% "akka-testkit" % AkkaVersion % Test,
        "com.typesafe.akka" %% "akka-stream-testkit" % AkkaVersion % Test,
        "com.typesafe.akka" %% "akka-http-testkit" % AkkaHttpVersion % Test,
        "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf",
        "org.slf4j" % "slf4j-simple" % "1.7.26",
        "com.fasterxml.jackson.core" % "jackson-databind" % "2.9.9.3"
      ),
    javacOptions in Compile ++= Seq("-encoding", "UTF-8"),
    akkaGrpcGeneratedSources in Compile := Seq(AkkaGrpc.Server),
    akkaGrpcGeneratedLanguages in Compile := Seq(AkkaGrpc.Scala), // FIXME should be Java, but here be dragons

    // Work around for https://github.com/akka/akka-grpc/pull/673
    (PB.targets in Compile) := {
      val old = (PB.targets in Compile).value
      val ct = crossTarget.value

      old.map(_.copy(outputPath = ct / "akka-grpc" / "main"))
    },
    PB.protoSources in Compile ++= {
      val baseDir = (baseDirectory in ThisBuild).value / "protocols"
      Seq(baseDir / "protocol", baseDir / "frontend")
    }
  )

lazy val `java-shopping-cart` = (project in file("samples/java-shopping-cart"))
  .dependsOn(`java-support`)
  .enablePlugins(AkkaGrpcPlugin, AssemblyPlugin, JavaAppPackaging, DockerPlugin)
  .settings(
    name := "java-shopping-cart",
    dockerSettings,
    dockerBaseImage := "adoptopenjdk/openjdk8",
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
    javacOptions in Compile ++= Seq("-encoding", "UTF-8", "-source", "1.8", "-target", "1.8"),
    mainClass in assembly := (mainClass in Compile).value,
    assemblyJarName in assembly := "java-shopping-cart.jar",
    test in assembly := {},
    // logLevel in assembly := Level.Debug,
    assemblyMergeStrategy in assembly := {
      /*ADD CUSTOMIZATIONS HERE*/
      //case PathList("META-INF", "io.netty.versions.properties") => MergeStrategy.last
      case x =>
        val oldStrategy = (assemblyMergeStrategy in assembly).value
        oldStrategy(x)
    }
  )

lazy val `scala-shopping-cart` = (project in file("samples/scala-shopping-cart"))
  .dependsOn(`scala-support`)
  .enablePlugins(AkkaGrpcPlugin)
  .settings(
    name := "scala-shopping-cart",
    PB.generate in Compile := (PB.generate in Compile).dependsOn(PB.generate in (`scala-support`, Compile)).value,
    PB.protoSources in Compile ++= {
      val baseDir = (baseDirectory in ThisBuild).value / "protocols"
      Seq(baseDir / "frontend", baseDir / "example")
    },
    mainClass in assembly := Some("io.cloudstate.samples.shoppingcart.Main"),
    assemblyJarName in assembly := "scala-shopping-cart.jar",
    test in assembly := {},
    // logLevel in assembly := Level.Debug,
    assemblyMergeStrategy in assembly := {
      /*ADD CUSTOMIZATIONS HERE*/
      //case PathList("META-INF", "io.netty.versions.properties") => MergeStrategy.last
      case x =>
        val oldStrategy = (assemblyMergeStrategy in assembly).value
        oldStrategy(x)
    }
  )

lazy val `akka-client` = (project in file("samples/akka-client"))
  .enablePlugins(AkkaGrpcPlugin)
  .settings(
    common,
    name := "akka-client",
    fork in run := true,
    libraryDependencies ++= Seq(
        // Remove these explicit gRPC/netty dependencies once akka-grpc 0.7.1 is released and we've upgraded to using that
        "io.grpc" % "grpc-netty-shaded" % GrpcJavaVersion,
        "io.grpc" % "grpc-core" % GrpcJavaVersion,
        "com.typesafe.akka" %% "akka-persistence" % AkkaVersion,
        "com.typesafe.akka" %% "akka-stream" % AkkaVersion,
        "com.typesafe.akka" %% "akka-http" % AkkaHttpVersion,
        "com.typesafe.akka" %% "akka-http-spray-json" % AkkaHttpVersion,
        "com.typesafe.akka" %% "akka-http-core" % AkkaHttpVersion,
        "com.typesafe.akka" %% "akka-http2-support" % AkkaHttpVersion,
        "com.google.protobuf" % "protobuf-java" % ProtobufVersion % "protobuf",
        "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf"
      ),
    PB.protoSources in Compile ++= {
      val baseDir = (baseDirectory in ThisBuild).value / "protocols"
      Seq(baseDir / "frontend", baseDir / "example")
    }
  )

lazy val `load-generator` = (project in file("samples/js-shopping-cart-load-generator"))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .dependsOn(`akka-client`)
  .settings(
    common,
    name := "js-shopping-cart-load-generator",
    dockerSettings,
    dockerExposedPorts := Nil
  )

lazy val `tck` = (project in file("tck"))
  .enablePlugins(AkkaGrpcPlugin)
  .configs(IntegrationTest)
  .dependsOn(`akka-client`)
  .settings(
    Defaults.itSettings,
    common,
    name := "tck",
    libraryDependencies ++= Seq(
        // Remove these explicit gRPC/netty dependencies once akka-grpc 0.7.1 is released and we've upgraded to using that
        "io.grpc" % "grpc-netty-shaded" % GrpcJavaVersion,
        "io.grpc" % "grpc-core" % GrpcJavaVersion,
        "com.typesafe.akka" %% "akka-stream" % AkkaVersion,
        "com.typesafe.akka" %% "akka-http" % AkkaHttpVersion,
        "com.typesafe.akka" %% "akka-http-spray-json" % AkkaHttpVersion,
        "com.google.protobuf" % "protobuf-java" % ProtobufVersion % "protobuf",
        "org.scalatest" %% "scalatest" % ScalaTestVersion,
        "com.typesafe.akka" %% "akka-testkit" % AkkaVersion
      ),
    PB.protoSources in Compile ++= {
      val baseDir = (baseDirectory in ThisBuild).value / "protocols"
      Seq(baseDir / "proxy", baseDir / "protocol")
    },
    fork in test := true,
    parallelExecution in IntegrationTest := false,
    executeTests in IntegrationTest := (executeTests in IntegrationTest)
        .dependsOn(`proxy-core` / assembly, `java-shopping-cart` / assembly, `scala-shopping-cart` / assembly)
        .value
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
