package com.sus.fiap.consumer.model;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

public record EventEnvelope(
		UUID eventId,
		EventType type,
		Instant occurredAt,
		JsonNode payload
) {
}
