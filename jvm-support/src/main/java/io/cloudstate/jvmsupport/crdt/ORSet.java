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
 * An Observed-Removed Set.
 *
 * <p>An Observed-Removed Set allows both the addition and removal of elements in a set. A removal
 * can only be done if all of the additions that added the key have been seen by this node. This
 * means that, for example if node 1 adds element A, and node 2 also adds element A, then node 1's
 * addition is replicated to node 3, and node 3 deletes it before node 2's addition is replicated,
 * then the element will still be in the map because node 2's addition had not yet been observed by
 * node 3, and will cause the element to be re-added when node 3 receives it. However, if both
 * additions had been replicated to node 3, then the element will be removed.
 *
 * <p>Care needs to be taken to ensure that the serialized value of elements in the set is stable.
 * For example, if using protobufs, the serialized value of any maps contained in the protobuf is
 * not stable, and can yield a different set of bytes for the same logically equal element. Hence
 * maps should be avoided. Additionally, some changes in protobuf schemas which are backwards
 * compatible from a protobuf perspective, such as changing from sint32 to int32, do result in
 * different serialized bytes, and so must be avoided.
 *
 * @param <T> The type of elements.
 */
public interface ORSet<T> extends Crdt, Set<T> {}
