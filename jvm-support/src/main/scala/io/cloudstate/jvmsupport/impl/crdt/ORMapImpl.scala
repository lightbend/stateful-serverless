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
import java.util.{function, Map}

import com.google.protobuf.any.{Any => ScalaPbAny}
import io.cloudstate.jvmsupport.crdt.{Crdt, CrdtFactory, ORMap}
import io.cloudstate.jvmsupport.impl.AnySupport
import io.cloudstate.protocol.crdt.{CrdtDelta, CrdtState, ORMapDelta, ORMapEntry, ORMapEntryDelta, ORMapState}
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._

/**
 * A few notes on implementation:
 *
 * - put, and any similar operations (such as Map.Entry.setValue) are not supported, because the only way to create
 *   a CRDT is using a CrdtFactory, and we only make CrdtFactory's available in very specific contexts, such as in the
 *   getOrCreate method. The getOrCreate method is the only way to insert something new into the map.
 * - All mechanisms for removal are supported - eg, calling remove directly, calling remove on any of the derived sets
 *   (entrySet, keySet, values), and calling remove on the entrySet iterator.
 * - ju.AbstractMap is very useful, though bases most of its implementation on entrySet, so we need to take care to
 *   efficiently implement operations that it implements in O(n) time that we can do in O(1) time, such as
 *   get/remove/containsKey.
 */
private[crdt] final class ORMapImpl[K, V <: InternalCrdt](anySupport: AnySupport)
    extends util.AbstractMap[K, V]
    with InternalCrdt
    with ORMap[K, V] {
  override final val name = "ORMap"
  private val value = new util.HashMap[K, V]()
  private val added = new util.HashMap[K, (ScalaPbAny, V)]()
  private val removed = new util.HashSet[ScalaPbAny]()
  private var cleared = false

  override def getOrCreate(key: K, create: function.Function[CrdtFactory, V]): V =
    if (value.containsKey(key)) {
      value.get(key)
    } else {
      val encodedKey = anySupport.encodeScala(key)
      var internalCrdt: InternalCrdt = null
      val crdt = create(new AbstractCrdtFactory {
        override protected def anySupport: AnySupport = ORMapImpl.this.anySupport
        override protected def newCrdt[C <: InternalCrdt](crdt: C): C = {
          if (internalCrdt != null) {
            throw new IllegalStateException(
              "getOrCreate creation callback must only be used to create one CRDT at a time"
            )
          }
          internalCrdt = crdt
          crdt
        }
      })
      if (crdt == null) {
        throw new IllegalArgumentException("getOrCreate creation callback must return a CRDT")
      } else if (crdt != internalCrdt) {
        throw new IllegalArgumentException(
          "CRDT returned by getOrCreate creation callback must have been created by the CrdtFactory passed to it"
        )
      }

      value.put(key, crdt)
      added.put(key, (encodedKey, crdt))
      crdt
    }

  override def containsKey(key: Any): Boolean = value.containsKey(key)

  override def get(key: Any): V = value.get(key)

  override def put(key: K, value: V): V =
    throw new UnsupportedOperationException("Cannot put on an ORMap, use getOrCreate instead.")

  override def remove(key: Any): V = {
    if (value.containsKey(key)) {
      val encodedKey = anySupport.encodeScala(key)
      if (added.containsKey(key)) {
        added.remove(key)
      } else {
        removed.add(encodedKey)
      }
    }
    value.remove(key)
  }

  // Most methods in AbstractMap build on this. Most important thing is to get the mutability aspects right.
  override def entrySet(): util.Set[util.Map.Entry[K, V]] = new EntrySet

  private class EntrySet extends util.AbstractSet[util.Map.Entry[K, V]] {
    override def size(): Int = value.size()
    override def iterator(): util.Iterator[util.Map.Entry[K, V]] = new util.Iterator[util.Map.Entry[K, V]] {
      private val iter = value.entrySet().iterator()
      private var lastNext: util.Map.Entry[K, V] = _
      override def hasNext: Boolean = iter.hasNext
      override def next(): Map.Entry[K, V] = {
        lastNext = iter.next()
        new util.Map.Entry[K, V] {
          private val entry = lastNext
          override def getKey: K = entry.getKey
          override def getValue: V = entry.getValue
          override def setValue(value: V): V = throw new UnsupportedOperationException()
        }
      }
      override def remove(): Unit = {
        if (lastNext != null) {
          val encodedKey = anySupport.encodeScala(lastNext.getKey)
          if (added.containsKey(lastNext.getKey)) {
            added.remove(lastNext.getKey)
          } else {
            removed.add(encodedKey)
          }
        }
        iter.remove()
      }
    }
    override def clear(): Unit = ORMapImpl.this.clear()
  }

  override def size(): Int = value.size()

  override def isEmpty: Boolean = super.isEmpty

  override def clear(): Unit = {
    value.clear()
    cleared = true
    removed.clear()
    added.clear()
  }

  override def hasDelta: Boolean =
    if (cleared || !added.isEmpty || !removed.isEmpty) {
      true
    } else {
      value.values().asScala.exists(_.hasDelta)
    }

  override def delta: Option[CrdtDelta.Delta] =
    if (hasDelta) {
      val updated = (value.asScala -- this.added.keySet().asScala).collect {
        case (key, changed) if changed.hasDelta =>
          ORMapEntryDelta(Some(anySupport.encodeScala(key)), changed.delta.map(CrdtDelta(_)))
      }
      val added = this.added.asScala.values.map {
        case (key, crdt) => ORMapEntry(Some(key), Some(CrdtState(crdt.state)))
      }

      Some(
        CrdtDelta.Delta.Ormap(
          ORMapDelta(
            cleared = cleared,
            removed = removed.asScala.toVector,
            updated = updated.toVector,
            added = added.toVector
          )
        )
      )
    } else None

  override def resetDelta(): Unit = {
    cleared = false
    added.clear()
    removed.clear()
    value.values().asScala.foreach(_.resetDelta())
  }

  override def state: CrdtState.State =
    CrdtState.State.Ormap(
      ORMapState(
        value.asScala.map {
          case (key, crdt) => ORMapEntry(Some(anySupport.encodeScala(key)), Some(CrdtState(crdt.state)))
        }.toVector
      )
    )

  override val applyDelta = {
    case CrdtDelta.Delta.Ormap(ORMapDelta(cleared, removed, updated, added, _)) =>
      if (cleared) {
        value.clear()
      }
      removed.foreach(key => value.remove(anySupport.decode(key)))
      updated.foreach {
        case ORMapEntryDelta(Some(key), Some(delta), _) =>
          val crdt = value.get(anySupport.decode(key))
          if (crdt == null) {
            ORMapImpl.log.warn(s"ORMap entry to update with key $key not found in map")
          } else {
            crdt.applyDelta(delta.delta)
          }
      }
      added.foreach {
        case ORMapEntry(Some(key), Some(state), _) =>
          value.put(anySupport.decode(key).asInstanceOf[K],
                    CrdtStateTransformer.create(state, anySupport).asInstanceOf[V])
      }
  }

  override val applyState = {
    case CrdtState.State.Ormap(ORMapState(values, _)) =>
      value.clear()
      values.foreach {
        case ORMapEntry(Some(key), Some(state), _) =>
          value.put(anySupport.decode(key).asInstanceOf[K],
                    CrdtStateTransformer.create(state, anySupport).asInstanceOf[V])
      }
  }

  override def toString = s"ORMap(${value.asScala.map { case (k, v) => s"$k->$v" }.mkString(",")})"
}

private object ORMapImpl {
  private final val log = LoggerFactory.getLogger(classOf[ORMapImpl[_, _]])
}
