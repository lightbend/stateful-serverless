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

package io.cloudstate.proxy.telemetry

import akka.actor.ActorRef
import akka.grpc.GrpcClientSettings
import akka.testkit.EventFilter
import akka.testkit.TestEvent.Mute
import com.google.protobuf.ByteString
import com.google.protobuf.any.{Any => ProtoAny}
import io.cloudstate.protocol.entity.{ClientAction, Failure}
import io.cloudstate.protocol.value_entity.ValueEntityClient
import io.cloudstate.proxy.entity.{EntityCommand, UserFunctionReply}
import io.cloudstate.proxy.valueentity.store.{InMemoryStore, RepositoryImpl}
import io.cloudstate.proxy.valueentity.{ValueEntity, ValueEntitySupervisor}
import io.cloudstate.testkit.TestService
import io.cloudstate.testkit.valueentity.ValueEntityMessages
import io.prometheus.client.CollectorRegistry

import scala.concurrent.duration._

class EntityInstrumentationSpec extends AbstractTelemetrySpec {

  "EntityInstrumentation" should {

    "record value-based entity metrics" in withTestRegistry(
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
      import PrometheusEntityInstrumentation.MetricLabel._
      import PrometheusEntityInstrumentation.MetricName._
      import ValueEntityMessages._
      import testKit._
      import testKit.system.dispatcher

      // silence any dead letters or unhandled messages during shutdown (when using test event listener)
      system.eventStream.publish(Mute(EventFilter.warning(pattern = ".*received dead letter.*")))
      system.eventStream.publish(Mute(EventFilter.warning(pattern = ".*unhandled message.*")))

      // simulate user function interaction with value-based entity to validate instrumentation
      implicit val registry: CollectorRegistry = CloudstateTelemetry(system).prometheusRegistry

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

      val entity = system.actorOf(ValueEntitySupervisor.props(client, entityConfiguration, repository), "entity")
      watch(entity)

      val emptyCommand = Some(protobufAny(EmptyJavaMessage))
      val entityState = ProtoAny("state", ByteString.copyFromUtf8("state"))

      // init with empty state
      val connection = service.valueEntity.expectConnection()
      connection.expect(init("service", "entity"))

      metricValue(ActivatedEntitiesTotal, EntityName -> "test") shouldBe 1
      metricValue(LoadedStatesTotal, EntityName -> "test") shouldBe 0.0 // empty state
      metricValue(LoadedStateBytesTotal, EntityName -> "test") shouldBe 0.0 // empty state
      metricValue(RecoveryTimeSeconds + "_count", EntityName -> "test") shouldBe 1
      metricValue(RecoveryTimeSeconds + "_sum", EntityName -> "test") should be > 0.0

      // update command is executed
      entity ! EntityCommand(entityId = "test", name = "updateCommand", emptyCommand)
      connection.expect(command(1, "entity", "updateCommand"))
      metricValue(ReceivedCommandsTotal, EntityName -> "test") shouldBe 1

      // get command is stashed
      entity ! EntityCommand(entityId = "test", name = "getCommand", emptyCommand)

      eventually(timeout(5.seconds), interval(100.millis)) {
        metricValue(ReceivedCommandsTotal, EntityName -> "test") shouldBe 2
        metricValue(StashedCommandsTotal, EntityName -> "test") shouldBe 1
      }

      // send reply for update command
      connection.send(reply(1, EmptyJavaMessage, update(entityState)))
      expectMsg(UserFunctionReply(clientActionReply(messagePayload(EmptyJavaMessage))))

      metricValue(CommandProcessingTimeSeconds + "_count", EntityName -> "test") shouldBe 1
      metricValue(CommandProcessingTimeSeconds + "_sum", EntityName -> "test") should be > 0.0
      metricValue(CommandTotalTimeSeconds + "_count", EntityName -> "test") shouldBe 1
      metricValue(CommandTotalTimeSeconds + "_sum", EntityName -> "test") should be > 0.0
      metricValue(PersistedStatesTotal, EntityName -> "test") should be > 0.0
      metricValue(PersistedStateBytesTotal, EntityName -> "test") should be > 0.0
      metricValue(PersistTimeSeconds + "_count", EntityName -> "test") shouldBe 1
      metricValue(PersistTimeSeconds + "_sum", EntityName -> "test") should be > 0.0

      // get command command is unstashed
      metricValue(CompletedCommandsTotal, EntityName -> "test") shouldBe 1
      metricValue(UnstashedCommandsTotal, EntityName -> "test") shouldBe 1
      metricValue(CommandStashTimeSeconds + "_count", EntityName -> "test") shouldBe 1
      metricValue(CommandStashTimeSeconds + "_sum", EntityName -> "test") should be > 0.0

      connection.expect(command(2, "entity", "getCommand"))

      // send reply for get command
      connection.send(reply(2, EmptyJavaMessage))
      expectMsg(UserFunctionReply(clientActionReply(messagePayload(EmptyJavaMessage))))

      metricValue(CompletedCommandsTotal, EntityName -> "test") shouldBe 2
      metricValue(CommandProcessingTimeSeconds + "_count", EntityName -> "test") shouldBe 2
      metricValue(CommandProcessingTimeSeconds + "_sum", EntityName -> "test") should be > 0.0
      metricValue(CommandTotalTimeSeconds + "_count", EntityName -> "test") shouldBe 2
      metricValue(CommandTotalTimeSeconds + "_sum", EntityName -> "test") should be > 0.0

      // passivate the entity
      entity ! ValueEntity.Stop
      connection.expectClosed()
      expectTerminated(entity)

      metricValue(PassivatedEntitiesTotal, EntityName -> "test") shouldBe 1
      metricValue(EntityActiveTimeSeconds + "_count", EntityName -> "test") shouldBe 1
      metricValue(EntityActiveTimeSeconds + "_sum", EntityName -> "test") should be > 0.0

      // reactivate the entity
      val reactivatedEntity =
        system.actorOf(ValueEntitySupervisor.props(client, entityConfiguration, repository), "entity")
      watch(reactivatedEntity)
      val connection2 = service.valueEntity.expectConnection()

      connection2.expect(init("service", "entity", state(entityState)))

      metricValue(ActivatedEntitiesTotal, EntityName -> "test") shouldBe 2
      metricValue(LoadedStatesTotal, EntityName -> "test") shouldBe 1
      metricValue(LoadedStateBytesTotal, EntityName -> "test") should be > 0.0
      metricValue(RecoveryTimeSeconds + "_count", EntityName -> "test") shouldBe 2
      metricValue(RecoveryTimeSeconds + "_sum", EntityName -> "test") should be > 0.0

      // send delete command
      reactivatedEntity ! EntityCommand(entityId = "test", name = "deleteCommand", emptyCommand)
      connection2.expect(command(1, "entity", "deleteCommand"))
      connection2.send(reply(1, EmptyJavaMessage, delete()))
      expectMsg(UserFunctionReply(clientActionReply(messagePayload(EmptyJavaMessage))))

      metricValue(ReceivedCommandsTotal, EntityName -> "test") shouldBe 3
      metricValue(CompletedCommandsTotal, EntityName -> "test") shouldBe 3
      metricValue(CommandProcessingTimeSeconds + "_count", EntityName -> "test") shouldBe 3
      metricValue(CommandProcessingTimeSeconds + "_sum", EntityName -> "test") should be > 0.0
      metricValue(CommandTotalTimeSeconds + "_count", EntityName -> "test") shouldBe 3
      metricValue(CommandTotalTimeSeconds + "_sum", EntityName -> "test") should be > 0.0
      metricValue(DeleteTimeSeconds + "_count", EntityName -> "test") shouldBe 1
      metricValue(DeleteTimeSeconds + "_sum", EntityName -> "test") should be > 0.0

      // send a command that fails
      reactivatedEntity ! EntityCommand(entityId = "test", name = "command1", emptyCommand)
      connection2.expect(command(2, "entity", "command1"))
      connection2.send(actionFailure(2, "failure"))
      expectMsg(UserFunctionReply(Some(ClientAction(ClientAction.Action.Failure(Failure(2, "failure"))))))

      metricValue(ReceivedCommandsTotal, EntityName -> "test") shouldBe 4
      metricValue(FailedCommandsTotal, EntityName -> "test") shouldBe 1
      metricValue(CompletedCommandsTotal, EntityName -> "test") shouldBe 4
      metricValue(CommandProcessingTimeSeconds + "_count", EntityName -> "test") shouldBe 4
      metricValue(CommandProcessingTimeSeconds + "_sum", EntityName -> "test") should be > 0.0
      metricValue(CommandTotalTimeSeconds + "_count", EntityName -> "test") shouldBe 4
      metricValue(CommandTotalTimeSeconds + "_sum", EntityName -> "test") should be > 0.0

      // create unexpected entity failure
      reactivatedEntity ! EntityCommand(entityId = "test", name = "command2", emptyCommand)
      connection2.expect(command(3, "entity", "command2"))
      connection2.send(failure(3, "boom"))
      expectMsg(UserFunctionReply(clientActionFailure("Unexpected Value entity failure")))
      connection2.expectClosed()
      expectTerminated(reactivatedEntity)

      metricValue(ReceivedCommandsTotal, EntityName -> "test") shouldBe 5
      metricValue(FailedCommandsTotal, EntityName -> "test") shouldBe 2
      metricValue(CompletedCommandsTotal, EntityName -> "test") shouldBe 5
      metricValue(CommandProcessingTimeSeconds + "_count", EntityName -> "test") shouldBe 5
      metricValue(CommandProcessingTimeSeconds + "_sum", EntityName -> "test") should be > 0.0
      metricValue(CommandTotalTimeSeconds + "_count", EntityName -> "test") shouldBe 5
      metricValue(CommandTotalTimeSeconds + "_sum", EntityName -> "test") should be > 0.0

      metricValue(PassivatedEntitiesTotal, EntityName -> "test") shouldBe 2
      metricValue(EntityActiveTimeSeconds + "_count", EntityName -> "test") shouldBe 2
      metricValue(EntityActiveTimeSeconds + "_sum", EntityName -> "test") should be > 0.0
    }

  }
}
