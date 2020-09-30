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

package io.cloudstate.proxy.crud.store

import akka.grpc.ProtobufSerializer
import akka.util.ByteString
import com.google.protobuf.any.{Any => ScalaPbAny}
import io.cloudstate.proxy.crud.store.JdbcStore.Key

import scala.concurrent.{ExecutionContext, Future}

trait JdbcRepository {

  val store: JdbcStore[Key, ByteString]

  /**
   * Retrieve the payload for the given key.
   *
   * @param key to retrieve payload for
   * @return Some(payload) if payload exists for the key and None otherwise
   */
  def get(key: Key): Future[Option[ScalaPbAny]]

  /**
   * Insert the entity payload with the given key if it not already exists.
   * Update the entity payload at the given key if it already exists.
   *
   * @param key  to insert or update the entity
   * @param payload that should be persisted
   */
  def update(key: Key, payload: ScalaPbAny): Future[Unit]

  /**
   * Delete the entity with the given key.
   *
   * @param key to delete data.
   */
  def delete(key: Key): Future[Unit]

}

object JdbcRepositoryImpl {

  private[store] final object ReplySerializer extends ProtobufSerializer[ScalaPbAny] {
    override final def serialize(state: ScalaPbAny): ByteString =
      if (state.value.isEmpty) ByteString.empty
      else ByteString.fromArrayUnsafe(state.value.toByteArray)

    override final def deserialize(bytes: ByteString): ScalaPbAny =
      ScalaPbAny.parseFrom(bytes.toByteBuffer.array())
  }

}

class JdbcRepositoryImpl(val store: JdbcStore[Key, ByteString], serializer: ProtobufSerializer[ScalaPbAny])(
    implicit ec: ExecutionContext
) extends JdbcRepository {

  def this(store: JdbcStore[Key, ByteString])(implicit ec: ExecutionContext) =
    this(store, JdbcRepositoryImpl.ReplySerializer)

  def get(key: Key): Future[Option[ScalaPbAny]] =
    store
      .get(key)
      .map {
        case Some(value) => Some(serializer.deserialize(value))
        case None => None
      }

  def update(key: Key, entity: ScalaPbAny): Future[Unit] =
    store.update(key, serializer.serialize(entity))

  def delete(key: Key): Future[Unit] = store.delete(key)
}
