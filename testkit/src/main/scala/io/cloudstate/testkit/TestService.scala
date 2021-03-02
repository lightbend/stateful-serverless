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

package io.cloudstate.testkit

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.{Config, ConfigFactory}
import io.cloudstate.testkit.discovery.TestEntityDiscoveryService
import io.cloudstate.testkit.eventsourced.TestEventSourcedService
import io.cloudstate.testkit.valueentity.TestValueEntityService

import scala.concurrent.Await
import scala.concurrent.duration._

final class TestService {
  import TestService._

  val port: Int = Sockets.temporaryLocalPort()

  val context = new TestServiceContext(port)

  val entityDiscovery = new TestEntityDiscoveryService(context)

  val eventSourced = new TestEventSourcedService(context)

  val valueEntity = new TestValueEntityService(context)

  import context.system

  Await.result(
    Http()
      .newServerAt("localhost", port)
      .bind(entityDiscovery.handler orElse eventSourced.handler orElse valueEntity.handler),
    10.seconds
  )

  def terminate(): Unit = context.terminate()
}

object TestService {
  def apply(): TestService = new TestService

  final class TestServiceContext(val port: Int) {
    val config: Config = ConfigFactory.load(ConfigFactory.parseString(s"""
      akka.loglevel = ERROR
      akka.http.server {
        preview.enable-http2 = on
        idle-timeout = infinite
      }
    """))

    implicit val system: ActorSystem = ActorSystem("TestService", config)

    val probe: TestProbe = TestProbe("TestServiceProbe")

    def terminate(): Unit = TestKit.shutdownActorSystem(system)
  }
}
