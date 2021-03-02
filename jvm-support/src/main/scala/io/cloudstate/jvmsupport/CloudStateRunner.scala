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

package io.cloudstate.jvmsupport

import java.util.concurrent.CompletionStage

import com.typesafe.config.{Config, ConfigFactory}
import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl._
import akka.http.scaladsl.model._
import akka.stream.{ActorMaterializer, Materializer}
import com.google.protobuf.Descriptors
import io.cloudstate.jvmsupport.impl.eventsourced.{EventSourcedImpl, EventSourcedStatefulService}
import io.cloudstate.jvmsupport.impl.{EntityDiscoveryImpl, ResolvedServiceCallFactory, ResolvedServiceMethod}
import io.cloudstate.jvmsupport.impl.crdt.{CrdtImpl, CrdtStatefulService}
import io.cloudstate.protocol.crdt.CrdtHandler
import io.cloudstate.protocol.entity.EntityDiscoveryHandler
import io.cloudstate.protocol.event_sourced.EventSourcedHandler

import scala.compat.java8.FutureConverters
import scala.concurrent.Future
import scala.collection.JavaConverters._

object CloudStateRunner {
  final case class Configuration(userFunctionInterface: String, userFunctionPort: Int, snapshotEvery: Int) {
    validate()
    def this(config: Config) = {
      this(
        userFunctionInterface = config.getString("user-function-interface"),
        userFunctionPort = config.getInt("user-function-port"),
        snapshotEvery = config.getInt("eventsourced.snapshot-every")
      )
    }

    private def validate(): Unit = {
      require(userFunctionInterface.length > 0, s"user-function-interface must not be empty")
      require(userFunctionPort > 0, s"user-function-port must be greater than 0")
    }
  }
}

/**
 * The CloudStateRunner is responsible for handle the bootstrap of entities,
 * and is used by [[io.cloudstate.jvmsupport.CloudState.start()]] to set up the local
 * server with the given configuration.
 *
 * CloudStateRunner can be seen as a low-level API for cases where [[io.cloudstate.jvmsupport.CloudState.start()]] isn't enough.
 */
final class CloudStateRunner private[this] (_system: ActorSystem, services: Map[String, StatefulService]) {
  private[jvmsupport] implicit final val system = _system
  private[this] implicit final val materializer: Materializer = ActorMaterializer()

  private[this] final val configuration =
    new CloudStateRunner.Configuration(system.settings.config.getConfig("cloudstate"))

  // TODO JavaDoc
  def this(services: java.util.Map[String, StatefulService]) {
    this(ActorSystem("StatefulService", {
      val conf = ConfigFactory.load()
      conf.getConfig("cloudstate.system").withFallback(conf)
    }), services.asScala.toMap)
  }

  // TODO JavaDoc
  def this(services: java.util.Map[String, StatefulService], config: Config) {
    this(ActorSystem("StatefulService", config), services.asScala.toMap)
  }

  private val rootContext = new Context {
    override val serviceCallFactory: ServiceCallFactory = new ResolvedServiceCallFactory(services)
  }

  private[this] def createRoutes(): PartialFunction[HttpRequest, Future[HttpResponse]] = {

    val serviceRoutes =
      services.groupBy(_._2.getClass).foldLeft(PartialFunction.empty[HttpRequest, Future[HttpResponse]]) {

        case (route, (serviceClass, eventSourcedServices: Map[String, EventSourcedStatefulService] @unchecked))
            if serviceClass == classOf[EventSourcedStatefulService] =>
          val eventSourcedImpl = new EventSourcedImpl(system, eventSourcedServices, rootContext, configuration)
          route orElse EventSourcedHandler.partial(eventSourcedImpl)

        case (route, (serviceClass, crdtServices: Map[String, CrdtStatefulService] @unchecked))
            if serviceClass == classOf[CrdtStatefulService] =>
          val crdtImpl = new CrdtImpl(system, crdtServices, rootContext)
          route orElse CrdtHandler.partial(crdtImpl)

        case (_, (serviceClass, _)) =>
          sys.error(s"Unknown StatefulService: $serviceClass")
      }

    val entityDiscovery = EntityDiscoveryHandler.partial(new EntityDiscoveryImpl(system, services))

    serviceRoutes orElse
    entityDiscovery orElse { case _ => Future.successful(HttpResponse(StatusCodes.NotFound)) }
  }

  /**
   * Starts a server with the configured entities.
   *
   * @return a CompletionStage which will be completed when the server has shut down.
   */
  def run(): CompletionStage[Done] = {
    val serverBindingFuture = Http
      .get(system)
      .bindAndHandleAsync(createRoutes(),
                          configuration.userFunctionInterface,
                          configuration.userFunctionPort,
                          HttpConnectionContext(UseHttp2.Always))
    // FIXME Register an onTerminate callback to unbind the Http server
    FutureConverters
      .toJava(serverBindingFuture)
      .thenCompose(
        binding => system.getWhenTerminated.thenCompose(_ => FutureConverters.toJava(binding.unbind()))
      )
      .thenApply(_ => Done)
  }

  /**
   * Terminates the server.
   *
   * @return a CompletionStage which will be completed when the server has shut down.
   */
  def terminate(): CompletionStage[Done] =
    FutureConverters.toJava(system.terminate()).thenApply(_ => Done)
}

/**
 * StatefulService describes an entitiy type in a way which makes it possible
 * to deploy.
 */
trait StatefulService {

  /**
   * @return a Protobuf ServiceDescriptor of its externally accessible gRPC API
   */
  def descriptor: Descriptors.ServiceDescriptor

  /**
   * Possible values are: "", "", "".
   * @return the type of entity represented by this StatefulService
   */
  def entityType: String

  /**
   * @return the persistence identifier used for the the entities represented by this service
   */
  def persistenceId: String = descriptor.getName

  // TODO JavaDoc
  def resolvedMethods: Option[Map[String, ResolvedServiceMethod[_, _]]]
}
