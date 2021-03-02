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

import io.cloudstate.jvmsupport.crdt.Flag
import io.cloudstate.protocol.crdt.{CrdtDelta, CrdtState, FlagDelta, FlagState}

private[crdt] final class FlagImpl extends InternalCrdt with Flag {
  override final val name = "Flag"
  private var value: Boolean = false
  private var deltaValue: Boolean = false

  override def isEnabled: Boolean = value

  override def enable(): Unit =
    if (!deltaValue && !value) {
      deltaValue = true
      value = true
    }

  override def hasDelta: Boolean = deltaValue

  override def delta: Option[CrdtDelta.Delta] =
    if (hasDelta) {
      Some(CrdtDelta.Delta.Flag(FlagDelta(deltaValue)))
    } else None

  override def resetDelta(): Unit = deltaValue = false

  override def state: CrdtState.State = CrdtState.State.Flag(FlagState(value))

  override val applyDelta = {
    case CrdtDelta.Delta.Flag(FlagDelta(value, _)) =>
      this.value |= value
  }

  override val applyState = {
    case CrdtState.State.Flag(FlagState(value, _)) =>
      this.value = value
  }

  override def toString = s"Flag($value)"
}
