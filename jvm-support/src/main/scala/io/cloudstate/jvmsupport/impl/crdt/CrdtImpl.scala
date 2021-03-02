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

package io.cloudstate.jvmsupport.impl.crdt

import java.util.{function, Optional}
import java.util.function.Consumer

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.{Flow, Source}
import com.google.protobuf.Descriptors
import io.cloudstate.jvmsupport.{Context, ServiceCallFactory, StatefulService}
import io.cloudstate.jvmsupport.crdt.{
  CommandContext,
  CrdtContext,
  CrdtCreationContext,
  CrdtEntityFactory,
  StreamCancelledContext,
  StreamedCommandContext,
  SubscriptionContext
}
import io.cloudstate.jvmsupport.impl.{
  AbstractClientActionContext,
  AbstractEffectContext,
  ActivatableContext,
  AnySupport,
  FailInvoked,
  ResolvedEntityFactory,
  ResolvedServiceMethod
}
import io.cloudstate.protocol.crdt._
import io.cloudstate.protocol.crdt.CrdtStreamIn.{Message => In}
import io.cloudstate.protocol.entity.{Command, Failure, StreamCancelled}
import com.google.protobuf.any.{Any => ScalaPbAny}
import com.google.protobuf.{Any => JavaPbAny}

import scala.compat.java8.OptionConverters._
import scala.collection.JavaConverters._

final class CrdtStatefulService(val factory: CrdtEntityFactory,
                                override val descriptor: Descriptors.ServiceDescriptor,
                                val anySupport: AnySupport)
    extends StatefulService {
  override final val entityType = Crdt.name

  override def resolvedMethods: Option[Map[String, ResolvedServiceMethod[_, _]]] =
    factory match {
      case resolved: ResolvedEntityFactory => Some(resolved.resolvedMethods)
      case _ => None
    }

  private val streamed = descriptor.getMethods.asScala.filter(_.toProto.getServerStreaming).map(_.getName).toSet
  def isStreamed(command: String): Boolean = streamed(command)
}

class CrdtImpl(system: ActorSystem, services: Map[String, CrdtStatefulService], rootContext: Context) extends Crdt {

  /**
   * After invoking handle, the first message sent will always be a CrdtInit message, containing the entity ID, and,
   * if it exists or is available, the current state of the entity. After that, one or more commands may be sent,
   * as well as deltas as they arrive, and the entire state if either the entity is created, or the proxy wishes the
   * user function to replace its entire state.
   * The user function must respond with one reply per command in. They do not necessarily have to be sent in the same
   * order that the commands were sent, the command ID is used to correlate commands to replies.
   */
  def handle(in: Source[CrdtStreamIn, NotUsed]): Source[CrdtStreamOut, NotUsed] =
    in.prefixAndTail(1)
      .flatMapConcat {
        case (Seq(CrdtStreamIn(In.Init(init), _)), source) =>
          source.via(runEntity(init))
        case _ =>
          // todo better error
          throw new RuntimeException("Expected Init message")
      }
      .recover {
        case e =>
          // FIXME translate to failure message
          throw e
      }

  private def runEntity(init: CrdtInit): Flow[CrdtStreamIn, CrdtStreamOut, NotUsed] = {
    val service =
      services.getOrElse(init.serviceName, throw new RuntimeException(s"Service not found: ${init.serviceName}"))

    val runner = new EntityRunner(service, init.entityId, init.state.map { state =>
      CrdtStateTransformer.create(state, service.anySupport)
    })

    Flow[CrdtStreamIn]
      .mapConcat { in =>
        in.message match {
          case In.Command(command) =>
            runner.handleCommand(command)
          case In.Changed(delta) =>
            runner.handleDelta(delta).map { msg =>
              CrdtStreamOut(CrdtStreamOut.Message.StreamedMessage(msg))
            }
          case In.State(state) =>
            runner.handleState(state).map { msg =>
              CrdtStreamOut(CrdtStreamOut.Message.StreamedMessage(msg))
            }
          case In.Deleted(_) =>
            // ???
            Nil
          case In.StreamCancelled(cancelled) =>
            runner.handleStreamCancelled(cancelled)
          case In.Init(_) =>
            throw new IllegalStateException("Duplicate init event for the same entity")
          case In.Empty =>
            throw new RuntimeException("Empty or unknown in message")
        }
      }
      .recover {
        case err =>
          system.log.error(err, "Unexpected error, terminating CRDT.")
          CrdtStreamOut(CrdtStreamOut.Message.Failure(Failure(description = err.getMessage)))
      }
  }

  private class EntityRunner(service: CrdtStatefulService, entityId: String, private var crdt: Option[InternalCrdt]) {

    private var crdtIsNew = false
    private var subscribers = Map.empty[Long, function.Function[SubscriptionContext, Optional[JavaPbAny]]]
    private var cancelListeners = Map.empty[Long, function.Consumer[StreamCancelledContext]]
    private val entity = {
      val ctx = new CrdtCreationContext with CapturingCrdtFactory with ActivatableContext
      try {
        service.factory.create(ctx)
      } finally {
        ctx.deactivate()
      }
    }
    verifyNoDelta("creation")

    private def verifyNoDelta(scope: String): Unit =
      crdt match {
        case Some(changed) if changed.hasDelta && !crdtIsNew =>
          throw new RuntimeException(s"CRDT was changed during $scope, this is not allowed.")
        case _ =>
      }

    def handleState(state: CrdtState): List[CrdtStreamedMessage] = {
      crdt match {
        case Some(existing) => existing.applyState(state.state)
        case None => CrdtStateTransformer.create(state, service.anySupport)
      }
      notifySubscribers()
    }

    def handleDelta(delta: CrdtDelta): List[CrdtStreamedMessage] = {
      crdt match {
        case Some(existing) =>
          existing.applyDelta.applyOrElse(
            delta.delta, { noMatch: CrdtDelta.Delta =>
              throw new IllegalStateException(
                s"Received delta ${noMatch.value.getClass}, but it doesn't match the CRDT that this entity has: ${existing.name}"
              )
            }
          )
        case None => throw new IllegalStateException("Received delta for CRDT before it was created.")
      }
      notifySubscribers()
    }

    def handleCommand(command: Command): List[CrdtStreamOut] = {
      val grpcMethodIsStreamed = service.isStreamed(command.name)
      val ctx = if (grpcMethodIsStreamed) {
        new CrdtStreamedCommandContext(command)
      } else {
        new CrdtCommandContext(command)
      }

      val reply = try {
        val payload = ScalaPbAny.toJavaProto(command.payload.get)
        ctx match {
          case streamed: CrdtStreamedCommandContext =>
            entity.handleStreamedCommand(payload, streamed)
          case regular =>
            entity.handleCommand(payload, regular)
        }
      } catch {
        case FailInvoked =>
          Optional.empty[JavaPbAny]()
      } finally {
        ctx.deactivate()
      }

      val clientAction = ctx.createClientAction(reply, allowNoReply = true)

      if (ctx.hasError) {
        verifyNoDelta("failed command handling")
        CrdtStreamOut(
          CrdtStreamOut.Message.Reply(
            CrdtReply(
              commandId = command.id,
              clientAction = clientAction
            )
          )
        ) :: Nil
      } else {
        val crdtAction = ctx.createCrdtAction()

        // Notify subscribers of any changes before adding this streams subscribers to the list
        val streamedMessages = if (crdtAction.isDefined) {
          notifySubscribers()
        } else Nil

        val streamAccepted = ctx match {
          case stream: CrdtStreamedCommandContext => stream.addCallbacks()
          case _ => false
        }

        CrdtStreamOut(
          CrdtStreamOut.Message.Reply(
            CrdtReply(
              commandId = command.id,
              clientAction = clientAction,
              stateAction = crdtAction,
              sideEffects = ctx.sideEffects,
              streamed = streamAccepted
            )
          )
        ) :: streamedMessages.map(m => CrdtStreamOut(CrdtStreamOut.Message.StreamedMessage(m)))
      }
    }

    def handleStreamCancelled(cancelled: StreamCancelled): List[CrdtStreamOut] = {
      subscribers -= cancelled.id
      cancelListeners.get(cancelled.id) match {
        case Some(onCancel) =>
          cancelListeners -= cancelled.id
          val ctx = new CrdtStreamCancelledContext(cancelled)
          try {
            onCancel.accept(ctx)
          } finally {
            ctx.deactivate()
          }

          val crdtAction = ctx.createCrdtAction()
          if (crdtAction.isDefined) {
            CrdtStreamOut(
              CrdtStreamOut.Message.StreamCancelledResponse(
                CrdtStreamCancelledResponse(
                  commandId = cancelled.id,
                  stateAction = crdtAction,
                  sideEffects = ctx.sideEffects
                )
              )
            ) :: notifySubscribers().map(m => CrdtStreamOut(CrdtStreamOut.Message.StreamedMessage(m)))
          } else {
            CrdtStreamOut(
              CrdtStreamOut.Message.StreamCancelledResponse(
                CrdtStreamCancelledResponse(
                  commandId = cancelled.id,
                  sideEffects = ctx.sideEffects
                )
              )
            ) :: Nil
          }

        case None =>
          CrdtStreamOut(CrdtStreamOut.Message.StreamCancelledResponse(CrdtStreamCancelledResponse(cancelled.id))) :: Nil
      }

    }

    private def notifySubscribers(): List[CrdtStreamedMessage] =
      subscribers
        .collect(Function.unlift {
          case (id, callback) =>
            val context = new CrdtSubscriptionContext(id)
            val reply = try {
              callback(context)
            } catch {
              case FailInvoked =>
                Optional.empty[JavaPbAny]()
            } finally {
              context.deactivate()
            }

            val clientAction = context.createClientAction(reply, allowNoReply = true)

            if (context.hasError) {
              subscribers -= id
              cancelListeners -= id
              Some(
                CrdtStreamedMessage(
                  commandId = id,
                  clientAction = clientAction
                )
              )
            } else if (clientAction.isDefined || context.isEnded || context.sideEffects.nonEmpty) {
              if (context.isEnded) {
                subscribers -= id
                cancelListeners -= id
              }
              Some(
                CrdtStreamedMessage(
                  commandId = id,
                  clientAction = clientAction,
                  sideEffects = context.sideEffects,
                  endStream = context.isEnded
                )
              )
            } else {
              None
            }
        })
        .toList

    class CrdtStreamedCommandContext(command: Command)
        extends CrdtCommandContext(command)
        with StreamedCommandContext[JavaPbAny] {
      private final var changeCallback: Option[function.Function[SubscriptionContext, Optional[JavaPbAny]]] = None
      private final var cancelCallback: Option[Consumer[StreamCancelledContext]] = None

      override final def isStreamed: Boolean = command.streamed

      override final def onChange(subscriber: function.Function[SubscriptionContext, Optional[JavaPbAny]]): Unit = {
        checkActive()
        changeCallback = Some(subscriber)
      }

      override final def onCancel(effect: Consumer[StreamCancelledContext]): Unit = {
        checkActive()
        cancelCallback = Some(effect)
      }

      final def addCallbacks(): Boolean = {
        changeCallback.foreach { onChange =>
          subscribers = subscribers.updated(command.id, onChange)
        }
        cancelCallback.foreach { onCancel =>
          cancelListeners = cancelListeners.updated(command.id, onCancel)
        }
        changeCallback.isDefined || cancelCallback.isDefined
      }
    }

    class CrdtCommandContext(command: Command)
        extends CommandContext
        with AbstractCrdtContext
        with CapturingCrdtFactory
        with AbstractEffectContext
        with AbstractClientActionContext
        with DeletableContext
        with ActivatableContext {

      override final def commandId: Long = command.id

      override final def commandName(): String = command.name
    }

    class CrdtStreamCancelledContext(cancelled: StreamCancelled)
        extends StreamCancelledContext
        with CapturingCrdtFactory
        with AbstractEffectContext
        with ActivatableContext {
      override final def commandId(): Long = cancelled.id
    }

    class CrdtSubscriptionContext(override val commandId: Long)
        extends SubscriptionContext
        with AbstractCrdtContext
        with AbstractClientActionContext
        with AbstractEffectContext
        with ActivatableContext {
      private final var ended = false

      override final def endStream(): Unit = {
        checkActive()
        ended = true
      }

      final def isEnded: Boolean = ended
    }

    trait DeletableContext {
      self: ActivatableContext =>

    }

    trait AbstractCrdtContext extends CrdtContext {
      override final def state[T <: io.cloudstate.jvmsupport.crdt.Crdt](crdtType: Class[T]): Optional[T] =
        crdt match {
          case Some(crdt) if crdtType.isInstance(crdt) =>
            Optional.of(crdtType.cast(crdt))
          case None => Optional.empty()
          case Some(wrongType) =>
            throw new IllegalStateException(
              s"The current ${wrongType.name} CRDT state doesn't match requested type of ${crdtType.getSimpleName}"
            )
        }

      override final def entityId(): String = EntityRunner.this.entityId

      override def serviceCallFactory(): ServiceCallFactory = rootContext.serviceCallFactory()
    }

    trait CapturingCrdtFactory extends AbstractCrdtFactory with AbstractCrdtContext {
      self: ActivatableContext =>

      private var deleted = false

      override protected final def anySupport: AnySupport = service.anySupport

      override protected final def newCrdt[C <: InternalCrdt](newCrdt: C): C = {
        checkActive()
        if (crdt.isDefined) {
          throw new RuntimeException("This entity already has a CRDT created for it!")
        }
        crdt = Some(newCrdt)
        crdtIsNew = true
        newCrdt
      }

      final def delete(): Unit = {
        checkActive()
        if (!crdt.isDefined) {
          throw new IllegalStateException("The entity doesn't exist and so can't be deleted")
        }
        deleted = true
      }

      final def isDeleted: Boolean = deleted

      final def createCrdtAction(): Option[CrdtStateAction] = crdt match {
        case Some(c) =>
          if (crdtIsNew) {
            if (c.hasDelta) {
              crdtIsNew = false
              if (deleted) {
                crdt = None
                None
              } else {
                c.resetDelta()
                Some(CrdtStateAction(action = CrdtStateAction.Action.Create(CrdtState(c.state))))
              }
            } else if (deleted) {
              crdtIsNew = false
              crdt = None
              None
            } else {
              None
            }
          } else if (deleted) {
            Some(CrdtStateAction(action = CrdtStateAction.Action.Delete(CrdtDelete())))
          } else if (c.hasDelta) {
            val delta = c.delta.get
            c.resetDelta()
            Some(CrdtStateAction(action = CrdtStateAction.Action.Update(CrdtDelta(delta))))
          } else {
            None
          }
        case None => None
      }
    }
  }
}
