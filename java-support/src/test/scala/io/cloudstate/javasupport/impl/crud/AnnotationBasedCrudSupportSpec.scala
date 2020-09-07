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

package io.cloudstate.javasupport.impl.crud

import com.example.crud.shoppingcart.Shoppingcart
import com.google.protobuf.any.{Any => ScalaPbAny}
import com.google.protobuf.{ByteString, Any => JavaPbAny}
import io.cloudstate.javasupport.crud.{
  CommandContext,
  CommandHandler,
  CrudContext,
  CrudEntity,
  CrudEntityCreationContext,
  DeleteStateHandler,
  StateContext,
  UpdateStateHandler
}
import io.cloudstate.javasupport.impl.{AnySupport, ResolvedServiceMethod, ResolvedType}
import io.cloudstate.javasupport.{Context, EntityId, ServiceCall, ServiceCallFactory, ServiceCallRef}
import org.scalatest.{Matchers, WordSpec}

class AnnotationBasedCrudSupportSpec extends WordSpec with Matchers {
  trait BaseContext extends Context {
    override def serviceCallFactory(): ServiceCallFactory = new ServiceCallFactory {
      override def lookup[T](serviceName: String, methodName: String, messageType: Class[T]): ServiceCallRef[T] =
        throw new NoSuchElementException
    }
  }

  object MockContext extends CrudContext with BaseContext {
    override def entityId(): String = "foo"
  }

  class MockCommandContext extends CommandContext[JavaPbAny] with BaseContext {
    var action: Option[AnyRef] = None
    override def commandName(): String = "AddItem"
    override def commandId(): Long = 20
    override def updateEntity(state: JavaPbAny): Unit = action = Some(state)
    override def deleteEntity(): Unit = action = None
    override def entityId(): String = "foo"
    override def fail(errorMessage: String): RuntimeException = ???
    override def forward(to: ServiceCall): Unit = ???
    override def effect(effect: ServiceCall, synchronous: Boolean): Unit = ???
  }

  object WrappedResolvedType extends ResolvedType[Wrapped] {
    override def typeClass: Class[Wrapped] = classOf[Wrapped]
    override def typeUrl: String = AnySupport.DefaultTypeUrlPrefix + "/wrapped"
    override def parseFrom(bytes: ByteString): Wrapped = Wrapped(bytes.toStringUtf8)
    override def toByteString(value: Wrapped): ByteString = ByteString.copyFromUtf8(value.value)
  }

  object StringResolvedType extends ResolvedType[String] {
    override def typeClass: Class[String] = classOf[String]
    override def typeUrl: String = AnySupport.DefaultTypeUrlPrefix + "/string"
    override def parseFrom(bytes: ByteString): String = bytes.toStringUtf8
    override def toByteString(value: String): ByteString = ByteString.copyFromUtf8(value)
  }

  case class Wrapped(value: String)

  val anySupport = new AnySupport(Array(Shoppingcart.getDescriptor), this.getClass.getClassLoader)
  val descriptor = Shoppingcart.getDescriptor
    .findServiceByName("ShoppingCart")
    .findMethodByName("AddItem")
  val method = ResolvedServiceMethod(descriptor, StringResolvedType, WrappedResolvedType)

  def create(behavior: AnyRef, methods: ResolvedServiceMethod[_, _]*) =
    new AnnotationBasedCrudSupport(behavior.getClass,
                                   anySupport,
                                   methods.map(m => m.descriptor.getName -> m).toMap,
                                   Some(_ => behavior)).create(MockContext)

  def create(clazz: Class[_]) =
    new AnnotationBasedCrudSupport(clazz, anySupport, Map.empty, None).create(MockContext)

  def command(str: String) =
    ScalaPbAny.toJavaProto(ScalaPbAny(StringResolvedType.typeUrl, StringResolvedType.toByteString(str)))

  def decodeWrapped(any: JavaPbAny): Wrapped = {
    any.getTypeUrl should ===(WrappedResolvedType.typeUrl)
    WrappedResolvedType.parseFrom(any.getValue)
  }

  def state(any: Any): JavaPbAny = anySupport.encodeJava(any)

  "Crud annotation support" should {
    "support entity construction" when {

      "there is a noarg constructor" in {
        create(classOf[NoArgConstructorTest])
      }

      "there is a constructor with an EntityId annotated parameter" in {
        create(classOf[EntityIdArgConstructorTest])
      }

      "there is a constructor with a EventSourcedEntityCreationContext parameter" in {
        create(classOf[CreationContextArgConstructorTest])
      }

      "there is a constructor with multiple parameters" in {
        create(classOf[MultiArgConstructorTest])
      }

      "fail if the constructor contains an unsupported parameter" in {
        a[RuntimeException] should be thrownBy create(classOf[UnsupportedConstructorParameter])
      }

    }

    "support command handlers" when {

      "no arg command handler" in {
        val handler = create(new {
          @CommandHandler
          def addItem() = Wrapped("blah")
        }, method)
        decodeWrapped(handler.handleCommand(command("nothing"), new MockCommandContext).get) should ===(Wrapped("blah"))
      }

      "single arg command handler" in {
        val handler = create(new {
          @CommandHandler
          def addItem(msg: String) = Wrapped(msg)
        }, method)
        decodeWrapped(handler.handleCommand(command("blah"), new MockCommandContext).get) should ===(Wrapped("blah"))
      }

      "multi arg command handler" in {
        val handler = create(
          new {
            @CommandHandler
            def addItem(msg: String, @EntityId eid: String, ctx: CommandContext[JavaPbAny]): Wrapped = {
              eid should ===("foo")
              ctx.commandName() should ===("AddItem")
              Wrapped(msg)
            }
          },
          method
        )
        decodeWrapped(handler.handleCommand(command("blah"), new MockCommandContext).get) should ===(Wrapped("blah"))
      }

      "allow updating the state" in {
        val handler = create(
          new {
            @CommandHandler
            def addItem(msg: String, ctx: CommandContext[JavaPbAny]): Wrapped = {
              ctx.updateEntity(state(msg + " state"))
              ctx.commandName() should ===("AddItem")
              Wrapped(msg)
            }
          },
          method
        )
        val ctx = new MockCommandContext
        decodeWrapped(handler.handleCommand(command("blah"), ctx).get) should ===(Wrapped("blah"))
        ctx.action.get should ===(state("blah state"))
      }

      "fail if there's a bad context type" in {
        a[RuntimeException] should be thrownBy create(new {
          @CommandHandler
          def addItem(msg: String, ctx: StateContext) =
            Wrapped(msg)
        }, method)
      }

      "fail if there's two command handlers for the same command" in {
        a[RuntimeException] should be thrownBy create(new {
          @CommandHandler
          def addItem(msg: String, ctx: CommandContext[JavaPbAny]) =
            Wrapped(msg)
          @CommandHandler
          def addItem(msg: String) =
            Wrapped(msg)
        }, method)
      }

      "fail if there's no command with that name" in {
        a[RuntimeException] should be thrownBy create(new {
          @CommandHandler
          def wrongName(msg: String) =
            Wrapped(msg)
        }, method)
      }

      "fail if there's a CRDT command handler" in {
        val ex = the[RuntimeException] thrownBy create(new {
            @io.cloudstate.javasupport.crdt.CommandHandler
            def addItem(msg: String) =
              Wrapped(msg)
          }, method)
        ex.getMessage should include("Did you mean")
        ex.getMessage should include(classOf[CommandHandler].getName)
      }

      "unwrap exceptions" in {
        val handler = create(new {
          @CommandHandler
          def addItem(): Wrapped = throw new RuntimeException("foo")
        }, method)
        val ex = the[RuntimeException] thrownBy handler.handleCommand(command("nothing"), new MockCommandContext)
        ex.getMessage should ===("foo")
      }

    }

    "support update state handlers" when {
      val ctx = new StateContext with BaseContext {
        override def entityId(): String = "foo"
      }

      "single parameter" in {
        var invoked = false
        val handler = create(new {
          @UpdateStateHandler
          def updateState(state: String): Unit = {
            state should ===("state!")
            invoked = true
          }
        })
        handler.handleUpdate(state("state!"), ctx)
        invoked shouldBe true
      }

      "context parameter" in {
        var invoked = false
        val handler = create(new {
          @UpdateStateHandler
          def updateState(state: String, context: StateContext): Unit = {
            state should ===("state!")
            invoked = true
          }
        })
        handler.handleUpdate(state("state!"), ctx)
        invoked shouldBe true
      }

      "fail if there's a bad context" in {
        a[RuntimeException] should be thrownBy create(new {
          @UpdateStateHandler
          def updateState(state: String, context: CommandContext[JavaPbAny]): Unit = ()
        })
      }

      "fail if there's no state parameter" in {
        a[RuntimeException] should be thrownBy create(new {
          @UpdateStateHandler
          def updateState(context: StateContext): Unit = ()
        })
      }

      "fail if there's no update handler for the given type" in {
        val handler = create(new {
          @UpdateStateHandler
          def updateState(state: Int): Unit = ()
        })
        a[RuntimeException] should be thrownBy handler.handleUpdate(state(10), ctx)
      }

      "fail if there are two update handler methods" in {
        a[RuntimeException] should be thrownBy create(new {
          @UpdateStateHandler
          def updateState1(context: StateContext): Unit = ()
          @UpdateStateHandler
          def updateState2(context: StateContext): Unit = ()
        })
      }
    }

    "support delete state handlers" when {
      val ctx = new StateContext with BaseContext {
        override def entityId(): String = "foo"
      }

      "no arg parameter" in {
        var invoked = false
        val handler = create(new {
          @DeleteStateHandler
          def deleteState(): Unit =
            invoked = true
        })
        handler.handleDelete(ctx)
        invoked shouldBe true
      }

      "context parameter" in {
        var invoked = false
        val handler = create(new {
          @DeleteStateHandler
          def deleteState(context: StateContext): Unit =
            invoked = true
        })
        handler.handleDelete(ctx)
        invoked shouldBe true
      }

      "fail if there's a single argument is not the context" in {
        a[RuntimeException] should be thrownBy create(new {
          @DeleteStateHandler
          def deleteState(state: String): Unit = ()
        })
      }

      "fail if there's two delete methods" in {
        a[RuntimeException] should be thrownBy create(new {
          @DeleteStateHandler
          def deleteState1: Unit = ()

          @DeleteStateHandler
          def deleteState2: Unit = ()
        })
      }

      "fail if there's a bad context" in {
        a[RuntimeException] should be thrownBy create(new {
          @DeleteStateHandler
          def deleteState(context: CommandContext[JavaPbAny]): Unit = ()
        })
      }
    }
  }
}

import Matchers._

@CrudEntity
private class NoArgConstructorTest() {}

@CrudEntity
private class EntityIdArgConstructorTest(@EntityId entityId: String) {
  entityId should ===("foo")
}

@CrudEntity
private class CreationContextArgConstructorTest(ctx: CrudEntityCreationContext) {
  ctx.entityId should ===("foo")
}

@CrudEntity
private class MultiArgConstructorTest(ctx: CrudContext, @EntityId entityId: String) {
  ctx.entityId should ===("foo")
  entityId should ===("foo")
}

@CrudEntity
private class UnsupportedConstructorParameter(foo: String)