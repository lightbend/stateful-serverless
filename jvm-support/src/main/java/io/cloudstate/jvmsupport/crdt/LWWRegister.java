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

/**
 * A Last-Write-Wins Register.
 *
 * <p>This uses a clock value to determine which of two concurrent writes should win. When both
 * clock values are the same, an ordering defined over the node addresses is used to break the tie.
 *
 * <p>By default, the clock used is the clock of the node that set the value. This can be affected
 * by clock skew, which means two successive writes delegated to two separate nodes could result in
 * the first one winning. This can be avoided by using a custom clock with a domain specific clock
 * value, if such a causally ordered value is available.
 *
 * @param <T>
 */
public interface LWWRegister<T> extends Crdt {

  /**
   * Get the current value of the register.
   *
   * @return The current value of the register.
   */
  T get();

  /**
   * Set the current value of the register, using the default clock.
   *
   * @param value The value of the register to set.
   * @return The old value of the register.
   */
  default T set(T value) {
    return set(value, Clock.DEFAULT, 0);
  }

  /**
   * Set the current value of the register, using the given custom clock and clock value if
   * required.
   *
   * @param value The value of the register to set.
   * @param clock The clock to use.
   * @param customClockValue The custom clock value to use if the clock selected is a custom clock.
   *     This is ignored if the clock is not a custom clock.
   * @return The old value of the register.
   */
  T set(T value, Clock clock, long customClockValue);

  /** A clock. */
  enum Clock {

    /** The default clock, uses the current system time as the clock value. */
    DEFAULT,

    /**
     * A reverse clock, based on the system clock.
     *
     * <p>Using this effectively achieves First-Write-Wins semantics.
     *
     * <p>This is susceptible to the same clock skew problems as the default clock.
     */
    REVERSE,

    /**
     * A custom clock.
     *
     * <p>The custom clock value is passed by using the <code>customClockValue</code> parameter on
     * the {@link LWWRegister#set(Object, Clock, long)} method. The value should be a domain
     * specific monotonically increasing value. For example, if the source of the value for this
     * register is a single device, that device may attach a sequence number to each update, that
     * sequence number can be used to guarantee that the register will converge to the last update
     * emitted by that device.
     */
    CUSTOM,

    /**
     * A custom clock, that automatically increments the custom value if the local clock value is
     * greater than it.
     *
     * <p>This is like {@link Clock#CUSTOM}, however if when performing the update in the proxy,
     * it's found that the clock value of the register is greater than the specified clock value for
     * the update, the proxy will instead use the current clock value of the register plus one.
     *
     * <p>This can guarantee that updates done on the same node will be causally ordered (addressing
     * problems caused by the system clock being adjusted), but will not guarantee causal ordering
     * for updates on different nodes, since it's possible that an update on a different node has
     * not yet been replicated to this node.
     */
    CUSTOM_AUTO_INCREMENT
  }
}
