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

package io.cloudstate.proxy.valueentity

import akka.actor.ActorRef
import akka.grpc.GrpcClientSettings
import akka.testkit.EventFilter
import akka.testkit.TestEvent.Mute
import com.google.protobuf.ByteString
import com.google.protobuf.any.{Any => ProtoAny}
import io.cloudstate.protocol.value_entity.ValueEntityClient
import io.cloudstate.proxy.entity.{EntityCommand, UserFunctionReply}
import io.cloudstate.proxy.telemetry.AbstractTelemetrySpec
import io.cloudstate.proxy.valueentity.store.{InMemoryStore, RepositoryImpl}
import io.cloudstate.testkit.TestService
import io.cloudstate.testkit.valueentity.ValueEntityMessages

import scala.concurrent.duration._

class EntityPassivateSpec extends AbstractTelemetrySpec {

  "ValueEntity" should {

    "access the state after passivation" in withTestRegistry(
      """
        | include "test-in-memory"
        | akka {
        |   loglevel = DEBUG
        |   loggers = ["akka.testkit.TestEventListener"]
        |   remote.artery.canonical.port = 0
        |   remote.artery.bind.port = ""
        | }
      """
    ) { testKit =>
      import ValueEntityMessages._
      import testKit._
      import testKit.system.dispatcher

      // silence any dead letters or unhandled messages during shutdown (when using test event listener)
      system.eventStream.publish(Mute(EventFilter.warning(pattern = ".*received dead letter.*")))
      system.eventStream.publish(Mute(EventFilter.warning(pattern = ".*unhandled message.*")))

      implicit val replyTo: ActorRef = testActor

      val service = TestService()
      val client =
        ValueEntityClient(GrpcClientSettings.connectToServiceAt("localhost", service.port).withTls(false))

      val entityConfiguration = ValueEntity.Configuration(
        serviceName = "service",
        userFunctionName = "test",
        passivationTimeout = 30.seconds,
        sendQueueSize = 100
      )

      val repository = new RepositoryImpl(new InMemoryStore(system))
      val entity =
        watch(system.actorOf(ValueEntitySupervisor.props(client, entityConfiguration, repository), "entity"))
      val emptyCommand = Some(protobufAny(EmptyJavaMessage))

      // init with empty state
      val connection = service.valueEntity.expectConnection()
      connection.expect(init("service", "entity"))

      // update entity state
      entity ! EntityCommand(entityId = "test", name = "command1", emptyCommand)
      connection.expect(command(1, "entity", "command1"))
      val entityState = ProtoAny("state", ByteString.copyFromUtf8("state"))
      connection.send(reply(1, EmptyJavaMessage, update(entityState)))
      expectMsg(UserFunctionReply(clientActionReply(messagePayload(EmptyJavaMessage))))

      // passivate
      entity ! ValueEntity.Stop
      connection.expectClosed()
      expectTerminated(entity)

      // reactivate the entity
      val reactivatedEntity =
        system.actorOf(ValueEntitySupervisor.props(client, entityConfiguration, repository), "entity")
      val connection2 = service.valueEntity.expectConnection()
      connection2.expect(init("service", "entity", state(entityState)))
      connection2.close()
    }

  }
}
