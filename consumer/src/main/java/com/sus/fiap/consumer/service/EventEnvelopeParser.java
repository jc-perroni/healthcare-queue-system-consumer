package com.sus.fiap.consumer.service;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sus.fiap.consumer.model.EventEnvelope;
import com.sus.fiap.consumer.model.EventType;

import org.springframework.stereotype.Component;

@Component
public class EventEnvelopeParser {
	private final ObjectMapper objectMapper;

	public EventEnvelopeParser(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public EventEnvelope parse(String rawJson) {
		try {
			JsonNode root = objectMapper.readTree(rawJson);
			UUID eventId = UUID.fromString(requiredText(root, "eventId"));
			EventType type = EventType.valueOf(requiredText(root, "type"));
			Instant occurredAt = parseInstantRequired(root, "occurredAt");
			JsonNode payload = normalizePayload(root);
			return new EventEnvelope(eventId, type, occurredAt, payload);
		} catch (Exception e) {
			String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
			throw new IllegalArgumentException("Evento Kafka inválido (envelope): " + reason, e);
		}
	}

	private static Instant parseInstantRequired(JsonNode node, String field) {
		JsonNode child = node.get(field);
		if (child == null || child.isNull()) {
			throw new IllegalArgumentException("Campo obrigatório ausente: " + field);
		}
		if (child.isNumber()) {
			return instantFromEpochNumber(child.asDouble());
		}
		String text = child.asText();
		if (text == null || text.isBlank()) {
			throw new IllegalArgumentException("Campo obrigatório ausente: " + field);
		}
		try {
			return Instant.parse(text);
		} catch (Exception ignored) {
			// aceita epoch em string (inclusive notação científica)
			double epoch = Double.parseDouble(text);
			return instantFromEpochNumber(epoch);
		}
	}

	/**
	 * Heurística:
	 * - epoch < 10^11 => segundos
	 * - epoch >= 10^11 => millis
	 */
	private static Instant instantFromEpochNumber(double epoch) {
		if (epoch < 100_000_000_000d) {
			long seconds = (long) epoch;
			long nanos = (long) ((epoch - seconds) * 1_000_000_000d);
			return Instant.ofEpochSecond(seconds, nanos);
		}
		long millis = (long) epoch;
		return Instant.ofEpochMilli(millis);
	}

	/**
	 * Suporta 2 formatos:
	 * 1) Envelope padrão com `payload` (objeto).
	 * 2) Envelope "flattened" (sem `payload`), onde campos adicionais no root são tratados como payload.
	 */
	private JsonNode normalizePayload(JsonNode root) {
		JsonNode payload = root.get("payload");
		if (payload != null && !payload.isNull()) {
			return payload;
		}

		ObjectNode objectPayload = objectMapper.createObjectNode();
		if (root != null && root.isObject()) {
			root.fields().forEachRemaining(entry -> {
				String key = entry.getKey();
				if ("eventId".equals(key) || "type".equals(key) || "occurredAt".equals(key) || "payload".equals(key)) {
					return;
				}
				// Campos auxiliares que alguns producers enviam no root (não fazem parte do payload do domínio)
				if ("topic".equals(key) || "key".equals(key)) {
					return;
				}
				objectPayload.set(key, entry.getValue());
			});
		}
		return objectPayload;
	}

	private static String requiredText(JsonNode node, String field) {
		JsonNode child = node.get(field);
		if (child == null || child.isNull() || child.asText().isBlank()) {
			throw new IllegalArgumentException("Campo obrigatório ausente: " + field);
		}
		return child.asText();
	}
}
