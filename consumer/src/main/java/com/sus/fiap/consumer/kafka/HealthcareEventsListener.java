package com.sus.fiap.consumer.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.sus.fiap.consumer.model.EventEnvelope;
import com.sus.fiap.consumer.service.EventEnvelopeParser;
import com.sus.fiap.consumer.service.EventProcessor;
import com.sus.fiap.consumer.service.TenantContext;
import com.sus.fiap.consumer.service.UnidadeSchemaResolver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Component
public class HealthcareEventsListener {
	private static final Logger log = LoggerFactory.getLogger(HealthcareEventsListener.class);

	private final EventEnvelopeParser parser;
	private final EventProcessor processor;
	private final UnidadeSchemaResolver unidadeSchemaResolver;

	public HealthcareEventsListener(EventEnvelopeParser parser, EventProcessor processor, UnidadeSchemaResolver unidadeSchemaResolver) {
		this.parser = parser;
		this.processor = processor;
		this.unidadeSchemaResolver = unidadeSchemaResolver;
	}

	@KafkaListener(topics = "${app.kafka.topic.events}")
	public void onMessage(
			@Payload String value,
			@Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key
	) {
		try {
			EventEnvelope event = parser.parse(value);
			log.info("Evento recebido: type={}, eventId={}, key={}", event.type(), event.eventId(), key);
			String unidadeAtendimento = optionalText(event.payload(), "unidadeAtendimento");
			String tenant = unidadeSchemaResolver.resolveSchemaFromUnidade(unidadeAtendimento);
			TenantContext.setCurrentTenant(tenant);
			try {
				processor.process(event);
			} finally {
				TenantContext.clear();
			}
		} catch (IllegalArgumentException e) {
			String cause = (e.getCause() == null || e.getCause().getMessage() == null) ? null : e.getCause().getMessage();
			if (cause == null || cause.isBlank()) {
				log.warn("Ignorando mensagem Kafka inválida: key={}, erro={}", key, e.getMessage());
			} else {
				log.warn("Ignorando mensagem Kafka inválida: key={}, erro={}, causa={}", key, e.getMessage(), cause);
			}
		} catch (Exception e) {
			log.error("Falha ao processar mensagem Kafka: key={}", key, e);
			throw e;
		}
	}

	private static String optionalText(JsonNode payload, String field) {
		if (payload == null) {
			return null;
		}
		JsonNode node = payload.get(field);
		if (node == null || node.isNull()) {
			return null;
		}
		String text = node.asText();
		return (text == null || text.isBlank()) ? null : text;
	}
}
