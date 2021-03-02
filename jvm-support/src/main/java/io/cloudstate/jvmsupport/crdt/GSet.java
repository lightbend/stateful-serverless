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

package io.cloudstate.jvmsupport.crdt;

import java.util.Set;

/**
 * A Grow-only Set.
 *
 * <p>A Grow-only Set can have elements added to it, but cannot have elements removed from it.
 *
 * <p>Care needs to be taken to ensure that the serialized value of elements in the set is stable.
 * For example, if using protobufs, the serialized value of any maps contain in the protobuf is not
 * stable, and can yield a different set of bytes for the same logically equal element. Hence maps,
 * should be avoided. Additionally, some changes in protobuf schemas which are backwards compatible
 * from a protobuf perspective, such as changing from sint32 to int32, do result in different
 * serialized bytes, and so must be avoided.
 *
 * @param <T> The value of the set elements
 */
public interface GSet<T> extends Crdt, Set<T> {

  /** Remove is not support on a Grow-only set. */
  @Override
  default boolean remove(Object o) {
    throw new UnsupportedOperationException("Remove is not supported on a Grow-only Set.");
  }
}
