package com.sus.fiap.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sus.fiap.consumer.model.EventType;
import com.sus.fiap.consumer.service.EventEnvelopeParser;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EventEnvelopeParserTests {
	@Test
	void parse_shouldReadStandardEnvelope() {
		ObjectMapper objectMapper = new ObjectMapper();
		EventEnvelopeParser parser = new EventEnvelopeParser(objectMapper);

		String json = """
				{
				  \"eventId\": \"2f6f5a3c-7ac4-4d0b-9c5a-1b4d832d8f12\",
				  \"type\": \"MEDICO_ENTRA_NO_PONTO\",
				  \"occurredAt\": \"2026-02-13T12:10:00Z\",
				  \"payload\": {
				    \"codIdColaborador\": \"123\",
				    \"unidadeAtendimento\": \"UPA1\",
				    \"timestamp\": \"2026-02-13T12:10:00Z\"
				  }
				}
				""";

		var env = parser.parse(json);
		assertThat(env.eventId().toString()).isEqualTo("2f6f5a3c-7ac4-4d0b-9c5a-1b4d832d8f12");
		assertThat(env.type()).isEqualTo(EventType.MEDICO_ENTRA_NO_PONTO);
		assertThat(env.payload().get("unidadeAtendimento").asText()).isEqualTo("UPA1");
	}

	@Test
	void parse_shouldAcceptEpochSecondsAndFlattenedPayload() {
		ObjectMapper objectMapper = new ObjectMapper();
		EventEnvelopeParser parser = new EventEnvelopeParser(objectMapper);

		String json = """
				{
				  \"eventId\": \"b97b7628-c979-4434-8623-c1b619a08556\",
				  \"type\": \"RETIRADA_DE_SENHA\",
				  \"occurredAt\": 1771010745.973904,
				  \"unidadeAtendimento\": \"UPA1\",
				  \"nrSenhaAtendimento\": 1,
				  \"codCadastroSusPaciente\": 10
				}
				""";

		var env = parser.parse(json);
		assertThat(env.type()).isEqualTo(EventType.RETIRADA_DE_SENHA);
		assertThat(env.occurredAt()).isNotNull();
		assertThat(env.payload().get("unidadeAtendimento").asText()).isEqualTo("UPA1");
		assertThat(env.payload().get("nrSenhaAtendimento").asInt()).isEqualTo(1);
		assertThat(env.payload().get("codCadastroSusPaciente").asLong()).isEqualTo(10L);
	}
}
