/*
 * Copyright 2019 Lightbend Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.cloudstate.proxy

import akka.Done
import akka.actor.{Actor, ActorLogging, CoordinatedShutdown, Props, Status}
import akka.actor.typed.{ActorSystem => TypedActorSystem}
import akka.actor.typed.scaladsl.adapter._
import akka.cluster.Cluster
import akka.util.Timeout
import akka.pattern.pipe
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.grpc.GrpcClientSettings
import akka.stream.Materializer
import com.google.protobuf.DescriptorProtos
import com.google.protobuf.Descriptors.{FileDescriptor, ServiceDescriptor}
import com.typesafe.config.Config
import io.cloudstate.protocol.action.ActionProtocol
import io.cloudstate.protocol.entity._
import io.cloudstate.protocol.crdt.Crdt
import io.cloudstate.protocol.value_entity.ValueEntity
import io.cloudstate.protocol.event_sourced.EventSourced
import io.cloudstate.proxy.action.ActionProtocolSupportFactory
import io.cloudstate.proxy.crdt.CrdtSupportFactory
import io.cloudstate.proxy.eventing.{EventLogEventing, EventingManager, EventingSupport, ProjectionSupport}
import io.cloudstate.proxy.eventsourced.EventSourcedSupportFactory
import io.cloudstate.proxy.valueentity.EntitySupportFactory

import scala.concurrent.duration._

object EntityDiscoveryManager {
  final case class Configuration(
      devMode: Boolean,
      httpInterface: String,
      httpPort: Int,
      userFunctionHost: String,
      userFunctionPort: Int,
      protocolCompatibilityCheck: Boolean,
      relayTimeout: Timeout,
      relayOutputBufferSize: Int,
      maxInboundMessageSize: Long,
      gracefulTerminationTimeout: Timeout,
      numberOfShards: Int,
      proxyParallelism: Int,
      valueEntitySettings: ValueEntitySettings,
      eventSourcedSettings: EventSourcedSettings,
      crdtSettings: CrdtSettings,
      config: Config
  ) {
    validate()
    def this(config: Config) = {
      this(devMode = config.getBoolean("dev-mode-enabled"),
           httpInterface = config.getString("http-interface"),
           httpPort = config.getInt("http-port"),
           userFunctionHost = config.getString("user-function-host"),
           userFunctionPort = config.getInt("user-function-port"),
           protocolCompatibilityCheck = config.getBoolean("protocol-compatibility-check"),
           relayTimeout = Timeout(config.getDuration("relay-timeout").toMillis.millis),
           maxInboundMessageSize = config.getBytes("max-inbound-message-size"),
           relayOutputBufferSize = config.getInt("relay-buffer-size"),
           gracefulTerminationTimeout = Timeout(config.getDuration("graceful-termination-timeout").toMillis.millis),
           numberOfShards = config.getInt("number-of-shards"),
           proxyParallelism = config.getInt("proxy-parallelism"),
           valueEntitySettings = new ValueEntitySettings(config),
           eventSourcedSettings = EventSourcedSettings(config),
           crdtSettings = new CrdtSettings(config),
           config = config)
    }

    final def getConfig(path: String): Config =
      config.getConfig(path)

    private def validate(): Unit = {
      require(proxyParallelism > 0, s"proxy-parallelism must be greater than 0 but was $proxyParallelism")
      require(numberOfShards > 0, s"number-of-shards must be greater than 0 but was $numberOfShards")
      require(relayOutputBufferSize > 0, "relay-buffer-size must be greater than 0 but was $relayOutputBufferSize")
      require(maxInboundMessageSize > 0,
              "max-inbound-message-size must be greater than 0 but was $maxInboundMessageSize")
      require(maxInboundMessageSize <= Int.MaxValue,
              s"max-inbound-message-size exceeds the maximum allowed value of: ${Int.MaxValue}")
    }
  }

  final case class ValueEntitySettings(enabled: Boolean, passivationTimeout: Timeout) {
    def this(config: Config) = {
      this(
        enabled = config.getBoolean("value-entity.enabled"),
        passivationTimeout = Timeout(config.getDuration("value-entity.passivation-timeout").toMillis.millis)
      )
    }
  }

  final case class EventSourcedSettings(journalEnabled: Boolean,
                                        passivationTimeout: Timeout,
                                        readJournal: String,
                                        projectionSupport: Option[String])

  object EventSourcedSettings {
    def apply(config: Config): EventSourcedSettings = {
      val esConfig = config.getConfig("eventsourced-entity")
      val journalEnabled = esConfig.getBoolean("journal-enabled")
      val passivationTimeout = Timeout(esConfig.getDuration("passivation-timeout").toMillis.millis)
      val readJournal = esConfig.getString("read-journal")
      val psConfig = esConfig.getConfig("projection-support")
      val projectionSupport = if (psConfig.getBoolean("enabled")) {
        Some(psConfig.getString("class"))
      } else None
      EventSourcedSettings(journalEnabled, passivationTimeout, readJournal, projectionSupport)
    }
  }

  final case class CrdtSettings(passivationTimeout: Timeout) {
    def this(config: Config) = {
      this(
        passivationTimeout = Timeout(config.getDuration("crdt-entity.passivation-timeout").toMillis.millis)
      )
    }
  }

  def props(config: Configuration)(implicit mat: Materializer): Props =
    Props(new EntityDiscoveryManager(config))

  final case object Ready // Responds with true / false

  final def proxyInfo(supportedEntityTypes: Seq[String]) = ProxyInfo(
    protocolMajorVersion = BuildInfo.protocolMajorVersion,
    protocolMinorVersion = BuildInfo.protocolMinorVersion,
    proxyName = BuildInfo.name,
    proxyVersion = BuildInfo.version,
    supportedEntityTypes = supportedEntityTypes
  )

  final case class ServableEntity(serviceName: String,
                                  serviceDescriptor: ServiceDescriptor,
                                  userFunctionTypeSupport: UserFunctionTypeSupport)
}

class EntityDiscoveryManager(config: EntityDiscoveryManager.Configuration)(
    implicit mat: Materializer
) extends Actor
    with ActorLogging {

  private[this] implicit val system = context.system
  private[this] implicit val ec = context.dispatcher
  import EntityDiscoveryManager.Ready

  private[this] final val clientSettings =
    GrpcClientSettings
      .connectToServiceAt(config.userFunctionHost, config.userFunctionPort)
      .withChannelBuilderOverrides(_.maxInboundMessageSize(config.maxInboundMessageSize.toInt))
      .withTls(false)
  private[this] final val entityDiscoveryClient = EntityDiscoveryClient(clientSettings)

  private final val supportFactories: Map[String, UserFunctionTypeSupportFactory] = Map(
      Crdt.name -> new CrdtSupportFactory(system, config, entityDiscoveryClient, clientSettings),
      ActionProtocol.name -> new ActionProtocolSupportFactory(system, config, clientSettings)
    ) ++ {
      if (config.eventSourcedSettings.journalEnabled)
        Map(
          EventSourced.name -> new EventSourcedSupportFactory(system, config, clientSettings)
        )
      else Map.empty
    } ++ {
      if (config.valueEntitySettings.enabled)
        Map(
          ValueEntity.name -> new EntitySupportFactory(system, config, clientSettings)
        )
      else Map.empty
    }

  private final val elAndPs: Option[(EventingSupport, ProjectionSupport)] = for {
    projectionSupportClass <- config.eventSourcedSettings.projectionSupport
  } yield {
    val projectionSupport: ProjectionSupport = getClass.getClassLoader
      .loadClass(projectionSupportClass)
      .asSubclass(classOf[ProjectionSupport])
      .getDeclaredConstructor(classOf[TypedActorSystem[_]])
      .newInstance(system.toTyped)

    (new EventLogEventing(projectionSupport, config.eventSourcedSettings.readJournal, system.toTyped),
     projectionSupport)
  }
  private final val eventLogEventing = elAndPs.map(_._1)
  private final val projectionSupport = elAndPs.map(_._2)

  entityDiscoveryClient.discover(EntityDiscoveryManager.proxyInfo(supportFactories.keys.toSeq)) pipeTo self

  val supportedProtocolMajorVersion: Int = BuildInfo.protocolMajorVersion
  val supportedProtocolMinorVersion: Int = BuildInfo.protocolMinorVersion
  val supportedProtocolVersionString: String = s"${supportedProtocolMajorVersion}.${supportedProtocolMinorVersion}"

  def compatibleProtocol(majorVersion: Int, minorVersion: Int): Boolean =
    // currently strict matching of protocol versions
    ((majorVersion == supportedProtocolMajorVersion) && (minorVersion == supportedProtocolMinorVersion))

  override def receive: Receive = {
    case spec: EntitySpec =>
      log.info("Received EntitySpec from user function with info: {}", spec.getServiceInfo)

      try {
        if (!config.protocolCompatibilityCheck)
          log.warning("Protocol version compatibility is configured to be ignored")
        else if (!compatibleProtocol(spec.getServiceInfo.protocolMajorVersion,
                                     spec.getServiceInfo.protocolMinorVersion))
          throw EntityDiscoveryException(
            s"Incompatible protocol version ${spec.getServiceInfo.protocolMajorVersion}.${spec.getServiceInfo.protocolMinorVersion}, only $supportedProtocolVersionString is supported"
          )

        val descriptorSet = DescriptorProtos.FileDescriptorSet.parseFrom(spec.proto)
        val descriptors = FileDescriptorBuilder.build(descriptorSet)

        if (spec.entities.isEmpty) {
          throw EntityDiscoveryException("No entities were reported by the discover call!")
        }

        val entities = spec.entities.map { entity =>
          val serviceDescriptor = descriptors
            .collectFirst(Function.unlift(descriptor => extractService(entity.serviceName, descriptor)))
            .getOrElse(throw EntityDiscoveryException(s"Service [${entity.serviceName}] not found in descriptors!"))

          supportFactories.get(entity.entityType) match {
            case Some(factory) =>
              EntityDiscoveryManager.ServableEntity(entity.serviceName,
                                                    serviceDescriptor,
                                                    factory.build(entity, serviceDescriptor))
            case None if entity.entityType == EventSourced.name =>
              throw EntityDiscoveryException(
                s"Service [${entity.serviceName}] has declared an event sourced entity, however, this proxy does not have a configured store, or is using a store that doesn't support event sourced journals. A store that supports journals must be configured in this stateful services resource if event sourcing is to be used."
              )
            case None =>
              throw EntityDiscoveryException(
                s"Service [${entity.serviceName}] has declared an unsupported entity type [${entity.entityType}]. Supported types are ${supportFactories.keys
                  .mkString(",")}"
              )
          }
        }.toList

        val router = new UserFunctionRouter(entities, entityDiscoveryClient)

        val topicSupport = EventingManager.createSupport(config.getConfig("eventing"))
        val emitters = EventingManager.createEmitters(entities, topicSupport)

        val route = Serve.createRoute(entities, router, entityDiscoveryClient, descriptors, emitters)

        log.debug("Starting gRPC proxy")

        // Don't actually bind until we have a cluster
        Cluster(context.system).registerOnMemberUp {
          Http()
            .newServerAt(config.httpInterface, config.httpPort)
            .bind(route) pipeTo self

          EventingManager.startConsumers(router, entities, topicSupport, eventLogEventing, projectionSupport)
        }

        context.become(binding)

      } catch {
        case e @ EntityDiscoveryException(message) =>
          entityDiscoveryClient.reportError(UserFunctionError(message))
          throw e
      }

    case Ready => sender ! false
    case Status.Failure(cause) =>
      // Failure to load the entity spec is not fatal, simply crash and let the backoff supervisor restart us
      throw cause
  }

  private[this] final def extractService(serviceName: String, descriptor: FileDescriptor): Option[ServiceDescriptor] = {
    val (pkg, name) = Names.splitPrev(serviceName)
    Some(descriptor).filter(_.getPackage == pkg).flatMap(descriptor => Option(descriptor.findServiceByName(name)))
  }

  private[this] final def binding: Receive = {
    case sb: ServerBinding =>
      log.info(s"CloudState proxy online at ${sb.localAddress}")

      // These can be removed if https://github.com/akka/akka-http/issues/1210 ever gets implemented
      val shutdown = CoordinatedShutdown(system)

      shutdown.addTask(CoordinatedShutdown.PhaseServiceUnbind, "http-unbind") { () =>
        sb.unbind().map(_ => Done)
      }

      shutdown.addTask(CoordinatedShutdown.PhaseServiceRequestsDone, "http-graceful-terminate") { () =>
        sb.terminate(config.gracefulTerminationTimeout.duration).map(_ => Done)
      }

      shutdown.addTask(CoordinatedShutdown.PhaseServiceStop, "http-shutdown") { () =>
        Http().shutdownAllConnectionPools().map(_ => Done)
      }

      context.become(running)

    case Status.Failure(cause) => // Failure to bind the HTTP server is fatal, terminate
      log.error(cause, "Failed to bind HTTP server")
      system.terminate()

    case Ready => sender ! false
  }

  /** Nothing to do when running */
  private[this] final def running: Receive = {
    case Ready =>
      sender ! true
    case Done =>
      system.terminate() // FIXME context.become(dead)
  }

  override final def postStop(): Unit =
    entityDiscoveryClient.close()
}

final case class EntityDiscoveryException(message: String) extends RuntimeException(message)
