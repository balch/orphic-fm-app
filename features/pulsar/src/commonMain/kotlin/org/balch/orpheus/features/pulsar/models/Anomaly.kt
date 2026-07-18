package org.balch.orpheus.features.pulsar.models

import kotlinx.serialization.Serializable

/**
 * A rare, dramatic event dispatched by the Anomaly Engine. A [Vibe] declares zero or more
 * anomalies in [Vibe.anomalies]; each concrete type has its own probability and fires on its
 * own terms:
 * - **Auto-fire:** the engine rolls each anomaly's probability per section entry, so anomalies
 *   surface on their own, rarely, as a surprise.
 * - **Manual fire:** the manual anomaly trigger force-fires ALL of a vibe's
 *   declared anomalies at once. A vibe with an empty [Vibe.anomalies] list ignores the trigger.
 *
 * The C++ dispatcher is the runtime registry: `orpheus_unit_pulsar.cpp` edge-detects the
 * `anomaly_request` control and routes to each anomaly's handler. Kotlin only marshals the
 * config; the concrete types here mirror the C++ slots (a single void config bank, a single
 * lick-anomaly slot in the lick bank), which is why [Vibe.init] caps each type at one.
 *
 * Future dramatic events (Scratch / Tape / Sweep) extend this sealed interface with their own
 * `@SerialName` and config fields.
 */
@Serializable
sealed interface Anomaly
