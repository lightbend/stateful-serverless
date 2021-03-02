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

import io.cloudstate.protocol.function._
import akka.actor.ActorSystem

// FIXME Implement support for this
class StatelessFunctionImpl(system: ActorSystem) extends StatelessFunction {
  override def handleUnary(
      in: io.cloudstate.protocol.function.FunctionCommand
  ): scala.concurrent.Future[io.cloudstate.protocol.function.FunctionReply] = ???
  override def handleStreamedIn(
      in: akka.stream.scaladsl.Source[io.cloudstate.protocol.function.FunctionCommand, akka.NotUsed]
  ): scala.concurrent.Future[io.cloudstate.protocol.function.FunctionReply] = ???
  override def handleStreamedOut(
      in: io.cloudstate.protocol.function.FunctionCommand
  ): akka.stream.scaladsl.Source[io.cloudstate.protocol.function.FunctionReply, akka.NotUsed] = ???
  override def handleStreamed(
      in: akka.stream.scaladsl.Source[io.cloudstate.protocol.function.FunctionCommand, akka.NotUsed]
  ): akka.stream.scaladsl.Source[io.cloudstate.protocol.function.FunctionReply, akka.NotUsed] = ???
}
