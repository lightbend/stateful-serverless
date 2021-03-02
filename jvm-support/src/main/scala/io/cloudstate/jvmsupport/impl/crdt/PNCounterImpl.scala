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

import io.cloudstate.jvmsupport.crdt.PNCounter
import io.cloudstate.protocol.crdt.{CrdtDelta, CrdtState, PNCounterDelta, PNCounterState}

private[crdt] final class PNCounterImpl extends InternalCrdt with PNCounter {
  override final val name = "PNCounter"
  private var value: Long = 0
  private var deltaValue: Long = 0

  override def getValue: Long = value

  override def increment(by: Long): Long = {
    deltaValue += by
    value += by
    value
  }

  override def decrement(by: Long): Long = increment(-by)

  override def hasDelta: Boolean = deltaValue != 0

  override def delta: Option[CrdtDelta.Delta] =
    if (hasDelta) {
      Some(CrdtDelta.Delta.Pncounter(PNCounterDelta(deltaValue)))
    } else None

  override def resetDelta(): Unit = deltaValue = 0

  override def state: CrdtState.State = CrdtState.State.Pncounter(PNCounterState(value))

  override val applyDelta = {
    case CrdtDelta.Delta.Pncounter(PNCounterDelta(increment, _)) =>
      value += increment
  }

  override val applyState = {
    case CrdtState.State.Pncounter(PNCounterState(value, _)) =>
      this.value = value
  }

  override def toString = s"PNCounter($value)"
}
