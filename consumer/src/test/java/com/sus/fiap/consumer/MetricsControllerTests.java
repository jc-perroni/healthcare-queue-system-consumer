package com.sus.fiap.consumer;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.sus.fiap.consumer.api.MetricsController;
import com.sus.fiap.consumer.persistence.entity.AtendimentosUnidade;
import com.sus.fiap.consumer.persistence.repository.AtendimentosUnidadeRepository;
import com.sus.fiap.consumer.persistence.repository.PontoColaboradorRepository;
import com.sus.fiap.consumer.service.UnidadeSchemaResolver;

@WebMvcTest(controllers = MetricsController.class)
class MetricsControllerTests {
	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private StringRedisTemplate redis;

	@MockitoBean
	private UnidadeSchemaResolver unidadeSchemaResolver;

	@MockitoBean
	private PontoColaboradorRepository pontoColaboradorRepository;

	@MockitoBean
	private AtendimentosUnidadeRepository atendimentosUnidadeRepository;

	@Test
	void returns404WhenMetricMissing() throws Exception {
		@SuppressWarnings("unchecked")
		ValueOperations<String, String> valueOps = (ValueOperations<String, String>) org.mockito.Mockito.mock(ValueOperations.class);
		when(redis.opsForValue()).thenReturn(valueOps);
		when(valueOps.get("metrics:tempoAtendimentoMedio:UPA1")).thenReturn(null);

		mvc.perform(get("/api/metrics/tempo-espera/UPA1"))
				.andExpect(status().isNotFound());
	}

	@Test
	void returnsJsonWhenMetricExists() throws Exception {
		@SuppressWarnings("unchecked")
		ValueOperations<String, String> valueOps = (ValueOperations<String, String>) org.mockito.Mockito.mock(ValueOperations.class);
		when(redis.opsForValue()).thenReturn(valueOps);
		when(valueOps.get("metrics:tempoAtendimentoMedio:UPA2")).thenReturn("{\"unidadeAtendimento\":\"UPA2\"}");

		mvc.perform(get("/api/metrics/tempo-espera/upa2"))
				.andExpect(status().isOk())
				.andExpect(content().json("{\"unidadeAtendimento\":\"UPA2\"}"));
	}

	@Test
	void returnsOnlyWaitTimeForTipo() throws Exception {
		@SuppressWarnings("unchecked")
		ValueOperations<String, String> valueOps = (ValueOperations<String, String>) org.mockito.Mockito.mock(ValueOperations.class);
		when(redis.opsForValue()).thenReturn(valueOps);
		when(valueOps.get("metrics:tempoAtendimentoMedio:UPA1")).thenReturn(
				"{\"unidadeAtendimento\":\"UPA1\",\"normal\":{\"tempoEstimadoMin\":65},\"idoso\":{\"tempoEstimadoMin\":25},\"gestante\":{\"tempoEstimadoMin\":15},\"emergencia\":{\"tempoEstimadoMin\":10}}"
		);

		mvc.perform(get("/api/metrics/tempo-espera").queryParam("unidade", "UPA1").queryParam("tipo", "1"))
				.andExpect(status().isOk())
				.andExpect(content().json("{\"tempoEstimadoMin\":25}"));
	}

	@Test
	void returns400WhenTipoInvalid() throws Exception {
		mvc.perform(get("/api/metrics/tempo-espera").queryParam("unidade", "UPA1").queryParam("tipo", "9"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void returnsIndividualWaitTimeBySenha() throws Exception {
		@SuppressWarnings("unchecked")
		ZSetOperations<String, String> zsetOps = (ZSetOperations<String, String>) org.mockito.Mockito.mock(ZSetOperations.class);
		when(redis.opsForZSet()).thenReturn(zsetOps);
		when(zsetOps.count(org.mockito.Mockito.eq("queue:zset:UPA1"), org.mockito.Mockito.anyDouble(), org.mockito.Mockito.anyDouble()))
				.thenReturn(13L);

		when(unidadeSchemaResolver.resolveSchemaFromUnidade("UPA1")).thenReturn("und_atd1");
		when(pontoColaboradorRepository.countByHorarioSaidaIsNull()).thenReturn(2L);

		mvc.perform(get("/api/metrics/tempo-espera")
					.queryParam("unidade", "UPA1")
					.queryParam("tipo", "0")
					.queryParam("senha", "50"))
				.andExpect(status().isOk())
				.andExpect(content().json("{\"tempoEstimadoMin\":65,\"pessoasNaFrente\":13,\"medicosEmAtendimento\":2}"));
	}

	@Test
	void returnsIndividualWaitTimeByCodSus() throws Exception {
		@SuppressWarnings("unchecked")
		ZSetOperations<String, String> zsetOps = (ZSetOperations<String, String>) org.mockito.Mockito.mock(ZSetOperations.class);
		when(redis.opsForZSet()).thenReturn(zsetOps);
		when(zsetOps.score("queue:zset:UPA1", "123")).thenReturn(3_000_050d);
		when(zsetOps.count(org.mockito.Mockito.eq("queue:zset:UPA1"), org.mockito.Mockito.anyDouble(), org.mockito.Mockito.anyDouble()))
				.thenReturn(13L);

		when(unidadeSchemaResolver.resolveSchemaFromUnidade("UPA1")).thenReturn("und_atd1");
		when(pontoColaboradorRepository.countByHorarioSaidaIsNull()).thenReturn(2L);
		when(atendimentosUnidadeRepository
				.findByPacienteCodCadastroSusPacienteAndEstadoSenhaCodTipoEstadoNotInOrderByNrSeqAtendimentoAsc(
						10L,
						java.util.List.of(6, 90, 91)
				)).thenReturn(java.util.List.of(AtendimentosUnidade.builder().nrSeqAtendimento(123L).build()));

		mvc.perform(get("/api/metrics/tempo-espera")
					.queryParam("unidade", "UPA1")
					.queryParam("codSus", "10"))
				.andExpect(status().isOk())
				.andExpect(content().json("{\"tempoEstimadoMin\":65,\"pessoasNaFrente\":13,\"medicosEmAtendimento\":2,\"nrSeqAtendimento\":123}"));
	}
}
