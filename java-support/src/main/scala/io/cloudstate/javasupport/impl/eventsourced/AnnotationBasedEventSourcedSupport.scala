package io.cloudstate.javasupport.impl.eventsourced

import java.lang.reflect.{Constructor, InvocationTargetException}
import java.util.Optional

import com.google.protobuf.{Descriptors, Any => JavaPbAny}
import io.cloudstate.javasupport.ServiceCallFactory
import io.cloudstate.javasupport.eventsourced._
import io.cloudstate.javasupport.impl.ReflectionHelper.{InvocationContext, MainArgumentParameterHandler}
import io.cloudstate.javasupport.impl.{AnySupport, ReflectionHelper, ResolvedEntityFactory, ResolvedServiceMethod}

/**
 * Annotation based implementation of the [[EventSourcedEntityFactory]].
 */
private[impl] class AnnotationBasedEventSourcedSupport(
    entityClass: Class[_],
    anySupport: AnySupport,
    override val resolvedMethods: Map[String, ResolvedServiceMethod[_, _]],
    factory: Option[EventSourcedEntityCreationContext => AnyRef] = None
) extends EventSourcedEntityFactory
    with ResolvedEntityFactory {

  def this(entityClass: Class[_], anySupport: AnySupport, serviceDescriptor: Descriptors.ServiceDescriptor) =
    this(entityClass, anySupport, anySupport.resolveServiceDescriptor(serviceDescriptor))

  private val behavior = EventBehaviorReflection(entityClass, resolvedMethods)

  override def create(context: EventSourcedContext): EventSourcedEntityHandler =
    new EntityHandler(context)

  private val constructor: EventSourcedEntityCreationContext => AnyRef = factory.getOrElse {
    entityClass.getConstructors match {
      case Array(single) =>
        new EntityConstructorInvoker(ReflectionHelper.ensureAccessible(single))
      case _ =>
        throw new RuntimeException(s"Only a single constructor is allowed on event sourced entities: $entityClass")
    }
  }

  private class EntityHandler(context: EventSourcedContext) extends EventSourcedEntityHandler {
    private val entity = {
      constructor(new DelegatingEventSourcedContext(context) with EventSourcedEntityCreationContext {
        override def entityId(): String = context.entityId()
      })
    }

    override def handleEvent(anyEvent: JavaPbAny, context: EventContext): Unit = unwrap {
      val event = anySupport.decode(anyEvent).asInstanceOf[AnyRef]

      behavior.getCachedEventHandlerForClass(event.getClass) match {
        case Some(handler) =>
          val ctx = new DelegatingEventSourcedContext(context) with EventContext {
            override def sequenceNumber(): Long = context.sequenceNumber()
          }
          handler.invoke(entity, event, ctx)
        case None =>
          throw new RuntimeException(
            s"No event handler found for event ${event.getClass} on $behaviorsString"
          )
      }
    }

    override def handleCommand(command: JavaPbAny, context: CommandContext): Optional[JavaPbAny] = unwrap {
      behavior.commandHandlers.get(context.commandName()).map { handler =>
        handler.invoke(entity, command, context)
      } getOrElse {
        throw new RuntimeException(
          s"No command handler found for command [${context.commandName()}] on $behaviorsString"
        )
      }
    }

    override def handleSnapshot(anySnapshot: JavaPbAny, context: SnapshotContext): Unit = unwrap {
      val snapshot = anySupport.decode(anySnapshot).asInstanceOf[AnyRef]

      behavior.getCachedSnapshotHandlerForClass(snapshot.getClass) match {
        case Some(handler) =>
          val ctx = new DelegatingEventSourcedContext(context) with SnapshotContext {
            override def sequenceNumber(): Long = context.sequenceNumber()
          }
          handler.invoke(entity, snapshot, ctx)
        case None =>
          throw new RuntimeException(
            s"No snapshot handler found for snapshot ${snapshot.getClass} on $behaviorsString"
          )
      }
    }

    override def snapshot(context: SnapshotContext): Optional[JavaPbAny] = unwrap {
      behavior.snapshotInvoker.map { invoker =>
        invoker.invoke(entity, context)
      } match {
        case Some(invoker) => Optional.ofNullable(anySupport.encodeJava(invoker))
        case None => Optional.empty()
      }
    }

    private def unwrap[T](block: => T): T =
      try {
        block
      } catch {
        case ite: InvocationTargetException if ite.getCause != null =>
          throw ite.getCause
      }

    private def behaviorsString = entity.getClass.toString
  }

  private abstract class DelegatingEventSourcedContext(delegate: EventSourcedContext) extends EventSourcedContext {
    override def entityId(): String = delegate.entityId()
    override def serviceCallFactory(): ServiceCallFactory = delegate.serviceCallFactory()
  }
}

private class EntityConstructorInvoker(constructor: Constructor[_])
    extends (EventSourcedEntityCreationContext => AnyRef) {
  private val parameters = ReflectionHelper.getParameterHandlers[EventSourcedEntityCreationContext](constructor)()
  parameters.foreach {
    case MainArgumentParameterHandler(clazz) =>
      throw new RuntimeException(s"Don't know how to handle argument of type $clazz in constructor")
    case _ =>
  }

  def apply(context: EventSourcedEntityCreationContext): AnyRef = {
    val ctx = InvocationContext("", context)
    constructor.newInstance(parameters.map(_.apply(ctx)): _*).asInstanceOf[AnyRef]
  }
}
