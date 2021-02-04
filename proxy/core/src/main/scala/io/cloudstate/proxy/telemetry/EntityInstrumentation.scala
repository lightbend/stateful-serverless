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

package io.cloudstate.proxy.telemetry

object EntityInstrumentation {
  final case class Context(startTime: Long)
  final case class StashContext(stash: Context, restore: Context)
}

/**
 * Instrumentation SPI for value-based entities.
 */
abstract class EntityInstrumentation {
  import EntityInstrumentation._

  /**
   * Record entity activated.
   *
   * @param entityName the entity name
   * @return the context that will be passed to [[entityPassivated]]
   */
  def entityActivated(entityName: String): Context

  /**
   * Record entity passivated (actor stopped).
   *
   * @param entityName the entity name
   * @param context the context passed from [[entityActivated]]
   */
  def entityPassivated(entityName: String, context: Context): Unit

  /**
   * Record entity failed (unexpected termination or crash).
   *
   * @param entityName the entity name
   */
  def entityFailed(entityName: String): Unit

  /**
   * Record entity recovery started (loading state).
   *
   * @param entityName the entity name
   * @return the context that will be passed to [[recoveryCompleted]]
   */
  def recoveryStarted(entityName: String): Context

  /**
   * Record entity recovery completed (loading state).
   *
   * @param entityName the entity name
   * @param context the context passed from [[recoveryStarted]]
   */
  def recoveryCompleted(entityName: String, context: Context): Unit

  /**
   * Record entity recovery failed (loading state).
   *
   * @param entityName the entity name
   */
  def recoveryFailed(entityName: String): Unit

  /**
   * Record command received.
   *
   * @param entityName the entity name
   * @return the context that will be passed to [[commandCompleted]]
   */
  def commandReceived(entityName: String): Context

  /**
   * Record command stashed (currently processing).
   *
   * @param entityName the entity name
   * @param context the context from [[commandReceived]]
   * @return the context that will be passed to [[commandUnstashed]]
   */
  def commandStashed(entityName: String, context: Context): StashContext

  /**
   * Record command unstashed (ready to process).
   *
   * @param entityName the entity name
   * @param context the context passed from [[commandStashed]]
   */
  def commandUnstashed(entityName: String, context: StashContext): Unit

  /**
   * Record command started processing (sending command to user function).
   *
   * @param entityName the entity name
   * @return the context that will be passed to [[commandProcessed]]
   */
  def commandStarted(entityName: String): Context

  /**
   * Record command completed processing (reply received from user function).
   *
   * @param entityName the entity name
   * @param context the context passed from [[commandStarted]]
   */
  def commandProcessed(entityName: String, context: Context): Unit

  /**
   * Record command failed.
   *
   * @param entityName the entity name
   */
  def commandFailed(entityName: String): Unit

  /**
   * Record command fully completed (including processing and persisting).
   *
   * @param entityName the entity name
   * @param context the context passed from [[commandReceived]]
   */
  def commandCompleted(entityName: String, context: Context): Unit

  /**
   * Record entity persist state started.
   *
   * @param entityName the entity name
   * @return the context that will be passed to [[persistCompleted]]
   */
  def persistStarted(entityName: String): Context

  /**
   * Record entity persist state completed.
   *
   * @param entityName the entity name
   * @param context the context passed from [[persistStarted]]
   */
  def persistCompleted(entityName: String, context: Context): Unit

  /**
   * Record entity persist state failed.
   *
   * @param entityName the entity name
   */
  def persistFailed(entityName: String): Unit

  /**
   * Record state persisted to storage.
   *
   * @param entityName the entity name
   * @param size the serialized size of the state
   */
  def statePersisted(entityName: String, size: Int): Unit

  /**
   * Record state loaded from storage.
   *
   * @param entityName the entity name
   * @param size the serialized size of the state
   */
  def stateLoaded(entityName: String, size: Int): Unit

  /**
   * Record entity delete state started.
   *
   * @param entityName the entity name
   * @return the context that will be passed to [[deleteCompleted]]
   */
  def deleteStarted(entityName: String): Context

  /**
   * Record entity delete state completed.
   *
   * @param entityName the entity name
   * @param context the context passed from [[deleteStarted]]
   */
  def deleteCompleted(entityName: String, context: Context): Unit

  /**
   * Record entity delete state failed.
   *
   * @param entityName the entity name
   */
  def deleteFailed(entityName: String): Unit
}

object NoEntityInstrumentation extends EntityInstrumentation {
  import EntityInstrumentation._

  override def entityActivated(entityName: String): Context = null
  override def entityPassivated(entityName: String, context: Context): Unit = ()
  override def entityFailed(entityName: String): Unit = ()
  override def recoveryStarted(entityName: String): Context = null
  override def recoveryCompleted(entityName: String, context: Context): Unit = ()
  override def recoveryFailed(entityName: String): Unit = ()
  override def commandReceived(entityName: String): Context = null
  override def commandStashed(entityName: String, context: Context): StashContext = null
  override def commandUnstashed(entityName: String, context: StashContext): Unit = ()
  override def commandStarted(entityName: String): Context = null
  override def commandProcessed(entityName: String, context: Context): Unit = ()
  override def commandFailed(entityName: String): Unit = ()
  override def commandCompleted(entityName: String, context: Context): Unit = ()
  override def persistStarted(entityName: String): Context = null
  override def persistCompleted(entityName: String, context: Context): Unit = ()
  override def persistFailed(entityName: String): Unit = ???
  override def statePersisted(entityName: String, size: Int): Unit = ()
  override def stateLoaded(entityName: String, size: Int): Unit = ()
  override def deleteStarted(entityName: String): Context = null
  override def deleteCompleted(entityName: String, context: Context): Unit = ()
  override def deleteFailed(entityName: String): Unit = ()
}

/**
 * Instrumentation wrapper for an entity instance.
 */
abstract class ValueEntityInstrumentation {
  import EntityInstrumentation._

  def entityActivated(): Unit
  def entityPassivated(): Unit
  def entityFailed(): Unit
  def recoveryStarted(): Unit
  def recoveryCompleted(): Unit
  def recoveryFailed(): Unit
  def commandReceived(): Unit
  def commandStashed(): StashContext
  def commandUnstashed(context: StashContext): Unit
  def commandStarted(): Unit
  def commandProcessed(): Unit
  def commandFailed(): Unit
  def commandCompleted(): Unit
  def persistStarted(): Unit
  def persistCompleted(): Unit
  def persistFailed(): Unit
  def statePersisted(size: Int): Unit
  def stateLoaded(size: Int): Unit
  def deleteStarted(): Unit
  def deleteCompleted(): Unit
  def deleteFailed(): Unit
}

/**
 * Active instrumentation wrapper for an entity instance.
 * Stores instrumentation contexts local to an entity.
 */
class ActiveValueEntityInstrumentation(entityName: String, valueBased: EntityInstrumentation)
    extends ValueEntityInstrumentation {
  import EntityInstrumentation._

  // only one of these contexts is active at a time (stashed commands are stored in the stash queue)
  private[this] var entityContext: Context = _
  private[this] var recoveryContext: Context = _
  private[this] var receiveContext: Context = _
  private[this] var startContext: Context = _
  private[this] var persistContext: Context = _
  private[this] var deleteContext: Context = _

  override def entityActivated(): Unit =
    entityContext = valueBased.entityActivated(entityName)

  override def entityPassivated(): Unit = {
    valueBased.entityPassivated(entityName, entityContext)
    entityContext = null
  }

  override def entityFailed(): Unit =
    valueBased.entityFailed(entityName)

  override def recoveryStarted(): Unit =
    recoveryContext = valueBased.recoveryStarted(entityName)

  override def recoveryCompleted(): Unit = {
    valueBased.recoveryCompleted(entityName, recoveryContext)
    recoveryContext = null
  }

  override def recoveryFailed(): Unit =
    valueBased.recoveryFailed(entityName)

  override def commandReceived(): Unit =
    receiveContext = valueBased.commandReceived(entityName)

  override def commandStashed(): StashContext =
    valueBased.commandStashed(entityName, valueBased.commandReceived(entityName))

  override def commandUnstashed(context: StashContext): Unit = {
    valueBased.commandUnstashed(entityName, context)
    receiveContext = context.restore
  }

  override def commandStarted(): Unit =
    startContext = valueBased.commandStarted(entityName)

  override def commandProcessed(): Unit = {
    valueBased.commandProcessed(entityName, startContext)
    startContext = null
  }

  override def commandFailed(): Unit =
    valueBased.commandFailed(entityName)

  override def commandCompleted(): Unit = {
    valueBased.commandCompleted(entityName, receiveContext)
    receiveContext = null
  }

  override def persistStarted(): Unit =
    persistContext = valueBased.persistStarted(entityName)

  override def persistCompleted(): Unit = {
    valueBased.persistCompleted(entityName, persistContext)
    persistContext = null
  }

  override def persistFailed(): Unit =
    valueBased.persistFailed(entityName)

  override def statePersisted(size: Int): Unit =
    valueBased.statePersisted(entityName, size)

  override def stateLoaded(size: Int): Unit =
    valueBased.stateLoaded(entityName, size)

  override def deleteStarted(): Unit =
    deleteContext = valueBased.deleteStarted(entityName)

  override def deleteCompleted(): Unit = {
    valueBased.deleteCompleted(entityName, deleteContext)
    deleteContext = null
  }

  override def deleteFailed(): Unit = valueBased.deleteFailed(entityName)
}

object NoValueEntityInstrumentation extends ValueEntityInstrumentation {
  import EntityInstrumentation._

  override def entityActivated(): Unit = ()
  override def entityPassivated(): Unit = ()
  override def entityFailed(): Unit = ()
  override def recoveryStarted(): Unit = ()
  override def recoveryCompleted(): Unit = ()
  override def recoveryFailed(): Unit = ()
  override def commandReceived(): Unit = ()
  override def commandStashed(): StashContext = null
  override def commandUnstashed(context: StashContext): Unit = ()
  override def commandStarted(): Unit = ()
  override def commandProcessed(): Unit = ()
  override def commandFailed(): Unit = ()
  override def commandCompleted(): Unit = ()
  override def persistStarted(): Unit = ()
  override def persistCompleted(): Unit = ()
  override def persistFailed(): Unit = ()
  override def statePersisted(size: Int): Unit = ()
  override def stateLoaded(size: Int): Unit = ()
  override def deleteStarted(): Unit = ()
  override def deleteCompleted(): Unit = ()
  override def deleteFailed(): Unit = ()
}
