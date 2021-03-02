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

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Context for handling a streamed command.
 *
 * <p>This may be passed to any {@link CommandHandler} annotated element that corresponds to a
 * command whose output is streamed.
 */
public interface StreamedCommandContext<Output> extends CommandContext {
  /**
   * Whether the call is actually streamed.
   *
   * <p>When a command is handled via the HTTP adapter, the command will not be streamed since the
   * HTTP adapter does not support streaming, and this will return <code>false</code>. In that case,
   * calls to {@link StreamedCommandContext#onChange(Function)} and {@link
   * StreamedCommandContext#onCancel(Consumer)} will fail.
   *
   * @return True if the command is actually streamed.
   */
  boolean isStreamed();

  /**
   * Register an on change callback for this command.
   *
   * <p>The callback will be invoked any time the CRDT changes. The callback may inspect the CRDT,
   * but any attempt to modify the CRDT will be ignored and the CRDT will crash.
   *
   * <p>If the callback returns a value, that value will be sent down the stream. Alternatively, the
   * callback may forward messages to other entities via the passed in {@link SubscriptionContext}.
   * The callback may also emit side effects to other entities via that context.
   *
   * @param subscriber The subscriber callback.
   */
  void onChange(Function<SubscriptionContext, Optional<Output>> subscriber);

  /**
   * Register an on cancel callback for this command.
   *
   * <p>This will be invoked if the client initiates a stream cancel. It will not be invoked if the
   * entity cancels the stream itself via {@link SubscriptionContext#endStream()} from an {@link
   * StreamedCommandContext#onChange(Function)} callback.
   *
   * <p>An on cancel callback may update the CRDT, and may emit side effects via the passed in
   * {@link StreamCancelledContext}.
   *
   * @param effect The effect to perform when this stream is cancelled.
   */
  void onCancel(Consumer<StreamCancelledContext> effect);
}
