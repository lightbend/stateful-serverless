/**
 * Event Sourcing support.
 *
 * <p>Event sourced entities can be annotated with the {@link
 * io.cloudstate.jvmsupport.eventsourced.EventSourcedEntity @EventSourcedEntity} annotation, and
 * supply command handlers using the {@link
 * io.cloudstate.jvmsupport.eventsourced.CommandHandler @CommandHandler} annotation.
 *
 * <p>In addition, {@link io.cloudstate.jvmsupport.eventsourced.EventHandler @EventHandler}
 * annotated methods should be defined to handle events, and {@link
 * io.cloudstate.jvmsupport.eventsourced.Snapshot @Snapshot} and {@link
 * io.cloudstate.jvmsupport.eventsourced.SnapshotHandler @SnapshotHandler} annotated methods should
 * be defined to produce and handle snapshots respectively.
 */
package io.cloudstate.jvmsupport.eventsourced;
