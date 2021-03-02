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

import io.cloudstate.jvmsupport.impl.AnySupport
import io.cloudstate.protocol.crdt.CrdtState

private[crdt] object CrdtStateTransformer {

  def create(state: CrdtState, anySupport: AnySupport): InternalCrdt = {
    val crdt = state.state match {
      case CrdtState.State.Gcounter(_) =>
        new GCounterImpl
      case CrdtState.State.Pncounter(_) =>
        new PNCounterImpl
      case CrdtState.State.Gset(_) =>
        new GSetImpl[Any](anySupport)
      case CrdtState.State.Orset(_) =>
        new ORSetImpl[Any](anySupport)
      case CrdtState.State.Flag(_) =>
        new FlagImpl
      case CrdtState.State.Lwwregister(_) =>
        new LWWRegisterImpl[Any](anySupport)
      case CrdtState.State.Ormap(_) =>
        new ORMapImpl[Any, InternalCrdt](anySupport)
      case CrdtState.State.Vote(_) =>
        new VoteImpl
    }
    crdt.applyState(state.state)
    crdt
  }

}
