// SPDX-License-Identifier: MIT

package eu.kanade.tachiyomi.ui.stats

import tachiyomi.domain.immersion.model.CapabilityState

internal fun ankiPresentationCapabilityState(
    capabilityState: CapabilityState,
    isStale: Boolean,
): CapabilityState =
    if (isStale) CapabilityState.STALE else capabilityState
