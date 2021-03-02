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

import java.util

import com.google.protobuf.any.{Any => ScalaPbAny}
import io.cloudstate.jvmsupport.crdt.ORSet
import io.cloudstate.jvmsupport.impl.AnySupport
import io.cloudstate.protocol.crdt.{CrdtDelta, CrdtState, ORSetDelta, ORSetState}

import scala.collection.JavaConverters._

private[crdt] class ORSetImpl[T](anySupport: AnySupport) extends util.AbstractSet[T] with InternalCrdt with ORSet[T] {
  override final val name = "ORSet"
  private val value = new util.HashSet[T]()
  private val added = new util.HashSet[ScalaPbAny]()
  private val removed = new util.HashSet[ScalaPbAny]()
  private var cleared = false

  override def size(): Int = value.size()

  override def isEmpty: Boolean = super.isEmpty

  override def contains(o: Any): Boolean = value.contains(o)

  override def add(e: T): Boolean =
    if (value.contains(e)) {
      false
    } else {
      val encoded = anySupport.encodeScala(e)
      if (removed.contains(encoded)) {
        removed.remove(encoded)
      } else {
        added.add(anySupport.encodeScala(e))
      }
      value.add(e)
    }

  override def remove(o: Any): Boolean =
    if (!value.contains(o)) {
      false
    } else {
      value.remove(o)
      if (value.isEmpty) {
        clear()
      } else {
        val encoded = anySupport.encodeScala(o)
        if (added.contains(encoded)) {
          added.remove(encoded)
        } else {
          removed.add(encoded)
        }
      }
      true
    }

  override def iterator(): util.Iterator[T] = new util.Iterator[T] {
    private val iter = value.iterator()
    private var lastNext: T = _

    override def hasNext: Boolean = iter.hasNext

    override def next(): T = {
      lastNext = iter.next()
      lastNext
    }

    override def remove(): Unit = {
      iter.remove()
      val encoded = anySupport.encodeScala(lastNext)
      if (added.contains(encoded)) {
        added.remove(encoded)
      } else {
        removed.add(encoded)
      }
    }
  }

  override def clear(): Unit = {
    value.clear()
    cleared = true
    removed.clear()
    added.clear()
  }

  override def hasDelta: Boolean = cleared || !added.isEmpty || !removed.isEmpty

  override def delta: Option[CrdtDelta.Delta] =
    if (hasDelta) {
      Some(
        CrdtDelta.Delta.Orset(ORSetDelta(cleared, removed = removed.asScala.toVector, added = added.asScala.toVector))
      )
    } else None

  override def resetDelta(): Unit = {
    cleared = false
    added.clear()
    removed.clear()
  }

  override def state: CrdtState.State =
    CrdtState.State.Orset(ORSetState(value.asScala.toSeq.map(anySupport.encodeScala)))

  override val applyDelta = {
    case CrdtDelta.Delta.Orset(ORSetDelta(cleared, removed, added, _)) =>
      if (cleared) {
        value.clear()
      }
      value.removeAll(removed.map(e => anySupport.decode(e).asInstanceOf[T]).asJava)
      value.addAll(added.map(e => anySupport.decode(e).asInstanceOf[T]).asJava)
  }

  override val applyState = {
    case CrdtState.State.Orset(ORSetState(value, _)) =>
      this.value.clear()
      this.value.addAll(value.map(e => anySupport.decode(e).asInstanceOf[T]).asJava)
  }

  override def toString = s"ORSet(${value.asScala.mkString(",")})"
}
