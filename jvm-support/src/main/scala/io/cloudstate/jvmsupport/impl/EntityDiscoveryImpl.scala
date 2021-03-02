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

package io.cloudstate.jvmsupport.impl

import io.cloudstate.protocol.entity._

import scala.concurrent.Future
import akka.actor.ActorSystem
import com.google.protobuf.DescriptorProtos
import io.cloudstate.jvmsupport.{BuildInfo, StatefulService}

class EntityDiscoveryImpl(system: ActorSystem, services: Map[String, StatefulService]) extends EntityDiscovery {

  private def configuredOrElse(key: String, default: String): String =
    if (system.settings.config.hasPath(key)) system.settings.config.getString(key) else default

  private val serviceInfo = ServiceInfo(
    serviceRuntime = sys.props.getOrElse("java.runtime.name", "")
      + " " + sys.props.getOrElse("java.runtime.version", ""),
    supportLibraryName = configuredOrElse("cloudstate.library.name", BuildInfo.name),
    supportLibraryVersion = configuredOrElse("cloudstate.library.version", BuildInfo.version)
  )

  /**
   * Discover what entities the user function wishes to serve.
   */
  override def discover(in: ProxyInfo): scala.concurrent.Future[EntitySpec] = {
    system.log.info(
      s"Received discovery call from sidecar [${in.proxyName} ${in.proxyVersion}] supporting CloudState ${in.protocolMajorVersion}.${in.protocolMinorVersion}"
    )
    system.log.debug(s"Supported sidecar entity types: ${in.supportedEntityTypes.mkString("[", ",", "]")}")

    val unsupportedServices = services.values.filterNot { service =>
      in.supportedEntityTypes.contains(service.entityType)
    }

    if (unsupportedServices.nonEmpty) {
      system.log.error(
        "Proxy doesn't support the entity types for the following services: " + unsupportedServices
          .map(s => s.descriptor.getFullName + ": " + s.entityType)
          .mkString(", ")
      )
      // Don't fail though. The proxy may give us more information as to why it doesn't support them if we send back unsupported services.
      // eg, the proxy doesn't have a configured journal, and so can't support event sourcing.
    }

    if (false) // TODO verify compatibility with in.protocolMajorVersion & in.protocolMinorVersion
      Future.failed(new Exception("Proxy version not compatible with library protocol support version"))
    else {
      val allDescriptors = AnySupport.flattenDescriptors(services.values.map(_.descriptor.getFile).toSeq)
      val builder = DescriptorProtos.FileDescriptorSet.newBuilder()
      allDescriptors.values.foreach(fd => builder.addFile(fd.toProto))
      val fileDescriptorSet = builder.build().toByteString

      val entities = services.map {
        case (name, service) =>
          Entity(service.entityType, name, service.persistenceId)
      }.toSeq

      Future.successful(EntitySpec(fileDescriptorSet, entities, Some(serviceInfo)))
    }
  }

  /**
   * Report an error back to the user function. This will only be invoked to tell the user function
   * that it has done something wrong, eg, violated the protocol, tried to use an entity type that
   * isn't supported, or attempted to forward to an entity that doesn't exist, etc. These messages
   * should be logged clearly for debugging purposes.
   */
  override def reportError(in: UserFunctionError): scala.concurrent.Future[com.google.protobuf.empty.Empty] = {
    system.log.error(s"Error reported from sidecar: ${in.message}")
    Future.successful(com.google.protobuf.empty.Empty.defaultInstance)
  }
}
