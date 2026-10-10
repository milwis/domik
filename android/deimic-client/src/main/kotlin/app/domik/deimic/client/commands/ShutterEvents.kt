package app.domik.deimic.client.commands

import app.domik.deimic.client.Connection
import app.domik.deimic.protocol.ShutterState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance

/**
 * Shutter events (0x8B) the controller sends: after every shutter command, whoever gave it (a wall panel,
 * another client, a schedule), and when a shutter arrives at its end position by itself.
 *
 * There is no way to *ask* for a shutter's state on firmware 2017 (the collective state's `shutterStates` is not
 * updated, 0x94 is not supported), so a client that wants to show shutters must keep the last event per shutter
 * and treat a shutter without an event as "unknown".
 */
val Connection.shutterStates: Flow<ShutterState>
    get() = messages.filterIsInstance<ShutterState>()
