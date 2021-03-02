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

import com.example.shoppingcart.Shoppingcart
import com.google.protobuf.{ByteString, Empty}
import io.cloudstate.jvmsupport.Jsonable
import io.cloudstate.protocol.entity.UserFunctionError
import io.cloudstate.protocol.event_sourced.EventSourcedProto
import org.scalatest.{Matchers, OptionValues, WordSpec}

import scala.beans.BeanProperty

class AnySupportSpec extends WordSpec with Matchers with OptionValues {

  private val anySupport = new AnySupport(Array(Shoppingcart.getDescriptor, EventSourcedProto.javaDescriptor),
                                          getClass.getClassLoader,
                                          "com.example")
  private val addLineItem = Shoppingcart.AddLineItem
    .newBuilder()
    .setName("item")
    .setProductId("id")
    .setQuantity(10)
    .build()

  "Any support" should {

    "support se/deserializing java protobufs" in {
      val any = anySupport.encodeScala(addLineItem)
      any.typeUrl should ===("com.example/" + Shoppingcart.AddLineItem.getDescriptor.getFullName)
      anySupport.decode(any) should ===(addLineItem)
    }

    "support se/deserializing scala protobufs" in {
      val error = UserFunctionError("error")
      val any = anySupport.encodeScala(UserFunctionError("error"))
      any.typeUrl should ===("com.example/cloudstate.UserFunctionError")
      anySupport.decode(any) should ===(error)
    }

    "support resolving a service descriptor" in {
      val methods = anySupport.resolveServiceDescriptor(Shoppingcart.getDescriptor.findServiceByName("ShoppingCart"))
      methods should have size 3
      val method = methods("AddItem")

      // Input type
      method.inputType.typeUrl should ===("com.example/" + Shoppingcart.AddLineItem.getDescriptor.getFullName)
      method.inputType.typeClass should ===(classOf[Shoppingcart.AddLineItem])
      val iBytes = method.inputType.asInstanceOf[ResolvedType[Any]].toByteString(addLineItem)
      method.inputType.parseFrom(iBytes) should ===(addLineItem)

      // Output type - this also checks that when java_multiple_files is true, it works
      method.outputType.typeUrl should ===("com.example/" + Empty.getDescriptor.getFullName)
      method.outputType.typeClass should ===(classOf[Empty])
      val oBytes = method.outputType.asInstanceOf[ResolvedType[Any]].toByteString(Empty.getDefaultInstance)
      method.outputType.parseFrom(oBytes) should ===(Empty.getDefaultInstance)
    }

    def testPrimitive[T](name: String, value: T, defaultValue: T) = {
      val any = anySupport.encodeScala(value)
      any.typeUrl should ===(AnySupport.CloudStatePrimitive + name)
      anySupport.decode(any) should ===(value)

      val defaultAny = anySupport.encodeScala(defaultValue)
      defaultAny.typeUrl should ===(AnySupport.CloudStatePrimitive + name)
      defaultAny.value.size() shouldBe 0
      anySupport.decode(defaultAny) should ===(defaultValue)
    }

    "support se/deserializing strings" in testPrimitive("string", "foo", "")
    "support se/deserializing ints" in testPrimitive("int32", 10, 0)
    "support se/deserializing longs" in testPrimitive("int64", 10L, 0L)
    "support se/deserializing floats" in testPrimitive("float", 0.5f, 0f)
    "support se/deserializing doubles" in testPrimitive("double", 0.5d, 0d)
    "support se/deserializing bytes" in testPrimitive("bytes", ByteString.copyFromUtf8("foo"), ByteString.EMPTY)
    "support se/deserializing booleans" in testPrimitive("bool", true, false)

    "support se/deserializing json" in {
      val myJsonable = new MyJsonable
      myJsonable.field = "foo"
      val any = anySupport.encodeScala(myJsonable)
      any.typeUrl should ===(AnySupport.CloudStateJson + classOf[MyJsonable].getName)
      anySupport.decode(any).asInstanceOf[MyJsonable].field should ===("foo")
    }

  }

}

@Jsonable
class MyJsonable {
  @BeanProperty var field: String = _
}
