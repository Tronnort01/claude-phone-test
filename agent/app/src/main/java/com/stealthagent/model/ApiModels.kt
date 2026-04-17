package com.stealthagent.model

import kotlinx.serialization.Serializable

@Serializable
data class EventBatch(val deviceId: String, val events: List<EventPayload>)

@Serializable
data class EventPayload(val id: String, val kind: String, val payload: String, val capturedAt: Long)

@Serializable
data class PairRequest(val otp: String, val deviceName: String)

@Serializable
data class PairResponse(val deviceId: String, val token: String)
