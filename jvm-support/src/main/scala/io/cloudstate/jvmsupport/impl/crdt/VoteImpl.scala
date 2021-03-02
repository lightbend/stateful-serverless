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

import io.cloudstate.jvmsupport.crdt.Vote
import io.cloudstate.protocol.crdt.{CrdtDelta, CrdtState, VoteDelta, VoteState}

private[crdt] final class VoteImpl extends InternalCrdt with Vote {
  override final val name = "Vote"
  private var selfVote = false
  private var voters = 1
  private var votesFor = 0
  private var selfVoteChanged = false

  override def getSelfVote: Boolean = selfVote

  override def getVoters: Int = voters

  override def getVotesFor: Int = votesFor

  override def vote(vote: Boolean): Unit =
    if (selfVote != vote) {
      if (selfVoteChanged) {
        selfVoteChanged = false
      } else {
        selfVoteChanged = true
      }
      selfVote = vote
      if (selfVote) {
        votesFor += 1
      } else {
        votesFor -= 1
      }
    }

  override def hasDelta: Boolean = selfVoteChanged

  override def delta: Option[CrdtDelta.Delta] =
    if (selfVoteChanged) {
      Some(CrdtDelta.Delta.Vote(VoteDelta(selfVote)))
    } else None

  override def resetDelta(): Unit = selfVoteChanged = false

  override def state: CrdtState.State = CrdtState.State.Vote(VoteState(votesFor, voters, selfVote))

  override val applyDelta = {
    case CrdtDelta.Delta.Vote(VoteDelta(_, votesFor, totalVoters, _)) =>
      this.voters = totalVoters
      this.votesFor = votesFor
  }

  override val applyState = {
    case CrdtState.State.Vote(VoteState(votesFor, voters, selfVote, _)) =>
      this.voters = voters
      this.votesFor = votesFor
      this.selfVote = selfVote
  }

  override def toString = s"Vote($selfVote)"
}
