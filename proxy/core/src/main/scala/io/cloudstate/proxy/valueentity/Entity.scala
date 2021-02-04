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

import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicLong

import akka.NotUsed
import akka.actor._
import akka.cluster.sharding.ShardRegion
import akka.pattern.pipe
import akka.stream.scaladsl._
import akka.stream.{CompletionStrategy, Materializer, OverflowStrategy}
import akka.util.Timeout
import io.cloudstate.protocol.entity._
import io.cloudstate.protocol.value_entity._
import io.cloudstate.proxy.entity.{EntityCommand, UserFunctionReply}
import io.cloudstate.proxy.telemetry.CloudstateTelemetry
import io.cloudstate.proxy.valueentity.store.Repository
import io.cloudstate.proxy.valueentity.store.Store.Key

import scala.collection.immutable.Queue
import scala.concurrent.Future

object ValueEntitySupervisor {

  private final case class Relay(actorRef: ActorRef)

  def props(client: ValueEntityClient, configuration: ValueEntity.Configuration, repository: Repository)(
      implicit mat: Materializer
  ): Props =
    Props(new ValueEntitySupervisor(client, configuration, repository))
}

/**
 * This serves two purposes.
 *
 * Firstly, when the StateManager crashes, we don't want it restarted. Cluster sharding restarts, and there's no way
 * to customise that.
 *
 * Secondly, we need to ensure that we have an Akka Streams actorRef source to publish messages two before Akka
 * persistence starts feeding us events. There's a race condition if we do this in the same persistent actor. This
 * establishes that connection first.
 */
final class ValueEntitySupervisor(client: ValueEntityClient,
                                  configuration: ValueEntity.Configuration,
                                  repository: Repository)(implicit mat: Materializer)
    extends Actor
    with Stash {

  private val entityId = URLDecoder.decode(self.path.name, "utf-8")
  private val relay = context.watch(context.actorOf(ValueEntityRelay.props(client, configuration), "relay"))
  private val entity =
    context.watch(context.actorOf(ValueEntity.props(configuration, entityId, relay, repository), "entity"))

  private var relayTerminated: Boolean = false
  private var entityTerminated: Boolean = false

  relay ! ValueEntityRelay.Connect(entity)

  override def receive: Receive = {
    case Terminated(`relay`) =>
      relayTerminated = true
      if (entityTerminated) {
        context.stop(self)
      }
    case Terminated(`entity`) =>
      entityTerminated = true
      if (relayTerminated) {
        context.stop(self)
      } else relay ! ValueEntityRelay.Disconnect
    case toParent if sender() == entity =>
      context.parent ! toParent
    case msg =>
      entity forward msg
  }

  override def supervisorStrategy: SupervisorStrategy = SupervisorStrategy.stoppingStrategy
}

object ValueEntityRelay {
  final case class Connect(entity: ActorRef)
  final case class Connected(stream: ActorRef)
  final case object Disconnect
  final case class Fail(cause: Throwable)

  def props(client: ValueEntityClient, configuration: ValueEntity.Configuration)(implicit mat: Materializer): Props =
    Props(new ValueEntityRelay(client, configuration))
}

final class ValueEntityRelay(client: ValueEntityClient, configuration: ValueEntity.Configuration)(
    implicit mat: Materializer
) extends Actor
    with Stash {
  import ValueEntityRelay._

  override def receive: Receive = starting

  def starting: Receive = {
    case Connect(entity) => connect(entity)
    case _ => stash()
  }

  def connect(entity: ActorRef): Unit = {
    client
      .handle(
        Source
          .actorRef[ValueEntityStreamIn](
            { case Disconnect => CompletionStrategy.draining }: PartialFunction[Any, CompletionStrategy],
            { case Fail(cause) => cause }: PartialFunction[Any, Throwable],
            configuration.sendQueueSize,
            OverflowStrategy.fail
          )
          .mapMaterializedValue { ref =>
            self ! Connected(ref)
            NotUsed
          }
      )
      .runWith(Sink.actorRef(self, ValueEntity.StreamClosed, ValueEntity.StreamFailed.apply))

    context.become(connecting(entity))
  }

  def connecting(entity: ActorRef): Receive = {
    case Connected(stream) =>
      context.become(relaying(entity, stream))
      unstashAll()
    case _ => stash()
  }

  def relaying(entity: ActorRef, stream: ActorRef): Receive = {
    case Disconnect =>
      stream ! Disconnect
      context.become(disconnecting)
    case fail: Fail =>
      stream ! fail
      context.become(disconnecting)
    case message if sender() == entity =>
      stream ! message
    case message =>
      entity ! message
      if (streamTerminated(message)) context.stop(self)
  }

  def disconnecting: Receive = {
    case message if streamTerminated(message) => context.stop(self)
    case _ => // ignore
  }

  def streamTerminated(message: Any): Boolean = message match {
    case ValueEntity.StreamClosed => true
    case ValueEntity.StreamFailed => true
    case _ => false
  }
}

object ValueEntity {

  final case object Stop

  final case object StreamClosed extends DeadLetterSuppression
  final case class StreamFailed(cause: Throwable) extends DeadLetterSuppression

  final case class Configuration(
      serviceName: String,
      userFunctionName: String,
      passivationTimeout: Timeout,
      sendQueueSize: Int
  )

  private final case class OutstandingCommand(
      commandId: Long,
      actionId: String,
      replyTo: ActorRef
  )

  private case class ReadStateSuccess(initialized: Boolean)
  private case class ReadStateFailure(cause: Throwable)

  private sealed trait DatabaseOperationWriteStatus
  private case object WriteStateSuccess extends DatabaseOperationWriteStatus
  private case class WriteStateFailure(cause: Throwable) extends DatabaseOperationWriteStatus

  final def props(configuration: Configuration, entityId: String, relay: ActorRef, repository: Repository): Props =
    Props(new ValueEntity(configuration, entityId, relay, repository))

  /**
   * Used to ensure the number of entity's actor created.
   */
  private val actorCounter = new AtomicLong(0)

}

final class ValueEntity(configuration: ValueEntity.Configuration,
                        entityId: String,
                        relay: ActorRef,
                        repository: Repository)
    extends Actor
    with Stash
    with ActorLogging {

  import io.cloudstate.proxy.telemetry.EntityInstrumentation.StashContext

  private implicit val ec = context.dispatcher

  private val persistenceId: String = configuration.userFunctionName + entityId

  private val actorId = ValueEntity.actorCounter.incrementAndGet()

  private[this] final var stashedCommands = Queue.empty[(EntityCommand, ActorRef, StashContext)] // PERFORMANCE: look at options for data structures
  private[this] final var currentCommand: ValueEntity.OutstandingCommand = null
  private[this] final var stopped = false
  private[this] final var idCounter = 0L
  private[this] final var inited = false
  private[this] final var commandStartTime = 0L

  private[this] val instrumentation =
    CloudstateTelemetry(context.system).valueBasedInstrumentation(configuration.userFunctionName)

  instrumentation.entityActivated()
  instrumentation.recoveryStarted()

  // Set up passivation timer
  context.setReceiveTimeout(configuration.passivationTimeout.duration)

  override final def preStart(): Unit =
    repository
      .get(Key(persistenceId, entityId))
      .map { state =>
        instrumentation.recoveryCompleted()
        state.map(s => instrumentation.stateLoaded(s.serializedSize))

        if (!inited) {
          relay ! ValueEntityStreamIn(
            ValueEntityStreamIn.Message.Init(
              ValueEntityInit(
                serviceName = configuration.serviceName,
                entityId = entityId,
                state = Some(ValueEntityInitState(state))
              )
            )
          )
          ValueEntity.ReadStateSuccess(false) // not initialized yet!
        } else {
          ValueEntity.ReadStateSuccess(true) // already initialized!
        }
      }
      .recover {
        case error =>
          instrumentation.recoveryFailed()
          instrumentation.recoveryCompleted()
          ValueEntity.ReadStateFailure(error)
      }
      .pipeTo(self)

  override final def postStop(): Unit = {
    if (currentCommand != null) {
      log.warning("Stopped but we have a current action id {}", currentCommand.actionId)
    }
    instrumentation.entityPassivated()
  }

  private[this] final def commandHandled(): Unit = {
    currentCommand = null
    instrumentation.commandCompleted()
    if (stashedCommands.nonEmpty) {
      val ((request, sender, stashContext), newStashedCommands) = stashedCommands.dequeue
      stashedCommands = newStashedCommands
      instrumentation.commandUnstashed(stashContext)
      handleCommand(request, sender)
    } else if (stopped) {
      context.stop(self)
    }
  }

  private[this] final def notifyOutstandingRequests(msg: String): Unit = {
    instrumentation.entityFailed()
    currentCommand match {
      case null =>
      case req => req.replyTo ! createFailure(msg)
    }
    val errorNotification = createFailure("Value entity terminated")
    stashedCommands.foreach {
      case (_, replyTo, _) => replyTo ! errorNotification
    }
  }

  // only the msg is returned to the user, while the details are also part of the exception
  private[this] final def crash(msg: String, details: String): Unit = {
    notifyOutstandingRequests(msg)
    throw new Exception(s"$msg - $details")
  }

  private[this] final def handleCommand(entityCommand: EntityCommand, sender: ActorRef): Unit = {
    instrumentation.commandStarted()
    idCounter += 1
    val command = Command(
      entityId = entityId,
      id = idCounter,
      name = entityCommand.name,
      payload = entityCommand.payload
    )
    currentCommand = ValueEntity.OutstandingCommand(idCounter, actorId + ":" + entityId + ":" + idCounter, sender)
    commandStartTime = System.nanoTime()
    relay ! ValueEntityStreamIn(ValueEntityStreamIn.Message.Command(command))
  }

  private final def valueEntityReplyToUfReply(reply: ValueEntityReply) =
    UserFunctionReply(
      clientAction = reply.clientAction,
      sideEffects = reply.sideEffects
    )

  private final def createFailure(message: String) =
    UserFunctionReply(
      clientAction = Some(ClientAction(ClientAction.Action.Failure(Failure(description = message))))
    )

  override final def receive: Receive = {
    case ValueEntity.ReadStateSuccess(initialize) =>
      if (!initialize) {
        inited = true
        context.become(running)
        unstashAll()
      }

    case ValueEntity.ReadStateFailure(error) =>
      throw error

    case _ => stash()
  }

  private def running: Receive = {

    case command: EntityCommand if currentCommand != null =>
      stashedCommands = stashedCommands.enqueue((command, sender(), instrumentation.commandStashed()))

    case command: EntityCommand =>
      log.debug("Value entity [{}] [{}] received command [{}]", configuration.serviceName, entityId, command.name)
      instrumentation.commandReceived()
      handleCommand(command, sender())

    case ValueEntityStreamOut(m, _) =>
      import ValueEntityStreamOut.{Message => ValueEntitySOMsg}
      m match {

        case ValueEntitySOMsg.Reply(r) if currentCommand == null =>
          crash("Unexpected Value entity reply", s"(no current command) - $r")

        case ValueEntitySOMsg.Reply(r) if currentCommand.commandId != r.commandId =>
          crash("Unexpected Value entity reply",
                s"(expected id ${currentCommand.commandId} but got ${r.commandId}) - $r")

        case ValueEntitySOMsg.Reply(r) =>
          instrumentation.commandProcessed()
          r.clientAction match {
            case Some(ClientAction(ClientAction.Action.Failure(_), _)) =>
              instrumentation.commandFailed()
            case _ =>
          }

          val commandId = currentCommand.commandId
          if (r.stateAction.isEmpty) {
            currentCommand.replyTo ! valueEntityReplyToUfReply(r)
            commandHandled()
          } else {
            r.stateAction.map { a =>
              performAction(a) { () =>
                // Make sure that the current request is still ours
                if (currentCommand == null || currentCommand.commandId != commandId) {
                  crash("Unexpected Value entity behavior", "currentRequest changed before the state were persisted")
                }
                currentCommand.replyTo ! valueEntityReplyToUfReply(r)
                commandHandled()
              }.pipeTo(self)
            }
          }

        case ValueEntitySOMsg.Failure(f) if f.commandId == 0 =>
          crash("Unexpected Value entity failure", s"(not command specific) - ${f.description}")

        case ValueEntitySOMsg.Failure(f) if currentCommand == null =>
          crash("Unexpected Value entity failure", s"(no current command) - ${f.description}")

        case ValueEntitySOMsg.Failure(f) if currentCommand.commandId != f.commandId =>
          crash("Unexpected Value entity failure",
                s"(expected id ${currentCommand.commandId} but got ${f.commandId}) - ${f.description}")

        case ValueEntitySOMsg.Failure(f) =>
          instrumentation.commandFailed()
          instrumentation.commandProcessed()
          instrumentation.commandCompleted()
          try crash("Unexpected Value entity failure", f.description)
          finally currentCommand = null // clear command after notifications

        case ValueEntitySOMsg.Empty =>
          // Either the reply/failure wasn't set, or its set to something unknown.
          // todo see if scalapb can give us unknown fields so we can possibly log more intelligently
          crash("Unexpected Value entity failure", "empty or unknown message from entity output stream")
      }

    case ValueEntity.WriteStateSuccess =>
    // Nothing to do, database write access the native crud database was successful

    case ValueEntity.WriteStateFailure(error) =>
      notifyOutstandingRequests("Unexpected Value entity failure")
      throw error

    case ValueEntity.StreamClosed =>
      notifyOutstandingRequests("Unexpected Value entity termination")
      context.stop(self)

    case ValueEntity.StreamFailed(error) =>
      notifyOutstandingRequests("Unexpected Value entity termination")
      throw error

    case ReceiveTimeout =>
      context.parent ! ShardRegion.Passivate(stopMessage = ValueEntity.Stop)

    case ValueEntity.Stop =>
      stopped = true
      if (currentCommand == null) {
        context.stop(self)
      }
  }

  private def performAction(
      action: ValueEntityAction
  )(handler: () => Unit): Future[ValueEntity.DatabaseOperationWriteStatus] = {
    import ValueEntityAction.Action._

    action.action match {
      case Update(ValueEntityUpdate(Some(value), _)) =>
        instrumentation.persistStarted()
        instrumentation.statePersisted(value.serializedSize)
        repository
          .update(Key(persistenceId, entityId), value)
          .map { _ =>
            instrumentation.persistCompleted()
            handler()
            ValueEntity.WriteStateSuccess
          }
          .recover {
            case error =>
              instrumentation.persistFailed()
              instrumentation.persistCompleted()
              ValueEntity.WriteStateFailure(error)
          }

      case Delete(_) =>
        instrumentation.deleteStarted()
        repository
          .delete(Key(persistenceId, entityId))
          .map { _ =>
            instrumentation.deleteCompleted()
            handler()
            ValueEntity.WriteStateSuccess
          }
          .recover {
            case error =>
              instrumentation.deleteFailed()
              instrumentation.deleteCompleted()
              ValueEntity.WriteStateFailure(error)
          }
    }
  }
}
