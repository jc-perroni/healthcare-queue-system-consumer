package com.sus.fiap.consumer.service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sus.fiap.consumer.persistence.repository.AtendimentosUnidadeRepository;
import com.sus.fiap.consumer.persistence.repository.PontoColaboradorRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class TempoAtendimentoRedisService {
	private static final Logger log = LoggerFactory.getLogger(TempoAtendimentoRedisService.class);
	private static final Duration TTL = Duration.ofMinutes(2);
	private static final int TEMPO_MEDIO_ATENDIMENTO_MIN = 10;

	private static final int EST_ATENDIMENTO_FINALIZADO = 6;
	private static final int EST_SENHA_EXPIRADA = 90;
	private static final int EST_SENHA_CANCELADA = 91;

	private static final int PRIORIZACAO_NORMAL = 0;
	private static final int PRIORIZACAO_IDOSO = 1;
	private static final int PRIORIZACAO_GESTANTE = 2;

	private final StringRedisTemplate redis;
	private final ObjectMapper objectMapper;
	private final AtendimentosUnidadeRepository atendimentosUnidadeRepository;
	private final PontoColaboradorRepository pontoColaboradorRepository;

	public TempoAtendimentoRedisService(
			StringRedisTemplate redis,
			ObjectMapper objectMapper,
			AtendimentosUnidadeRepository atendimentosUnidadeRepository,
			PontoColaboradorRepository pontoColaboradorRepository
	) {
		this.redis = redis;
		this.objectMapper = objectMapper;
		this.atendimentosUnidadeRepository = atendimentosUnidadeRepository;
		this.pontoColaboradorRepository = pontoColaboradorRepository;
	}

	public void updateTempoMedioPorTipo(String unidadeAtendimento) {
		if (unidadeAtendimento == null || unidadeAtendimento.isBlank()) {
			return;
		}

		List<Integer> estadosFinais = List.of(EST_ATENDIMENTO_FINALIZADO, EST_SENHA_EXPIRADA, EST_SENHA_CANCELADA);
		long medicos = pontoColaboradorRepository.countByHorarioSaidaIsNull();

		long ativosNormal = atendimentosUnidadeRepository
				.countByEstadoSenhaCodTipoEstadoNotInAndTipoPriorizacaoCodTipoPriorizacao(estadosFinais, PRIORIZACAO_NORMAL);
		long ativosIdoso = atendimentosUnidadeRepository
				.countByEstadoSenhaCodTipoEstadoNotInAndTipoPriorizacaoCodTipoPriorizacao(estadosFinais, PRIORIZACAO_IDOSO);
		long ativosGestante = atendimentosUnidadeRepository
				.countByEstadoSenhaCodTipoEstadoNotInAndTipoPriorizacaoCodTipoPriorizacao(estadosFinais, PRIORIZACAO_GESTANTE);

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("unidadeAtendimento", unidadeAtendimento);
		payload.put("calculadoEm", Instant.now().toString());
		payload.put("medicosEmAtendimento", medicos);
		payload.put("tempoMedioAtendimentoMin", TEMPO_MEDIO_ATENDIMENTO_MIN);

		payload.put("normal", tipoPayload(ativosNormal, medicos));
		payload.put("idoso", tipoPayload(ativosIdoso, medicos));
		payload.put("gestante", tipoPayload(ativosGestante, medicos));

		try {
			String json = objectMapper.writeValueAsString(payload);
			redis.opsForValue().set(key(unidadeAtendimento), json, TTL);
		} catch (Exception e) {
			log.warn("Falha ao salvar tempo medio no Redis: unidade={}", unidadeAtendimento, e);
		}
	}

	private static Map<String, Object> tipoPayload(long senhasAtivas, long medicos) {
		Map<String, Object> p = new LinkedHashMap<>();
		p.put("senhasAtivas", senhasAtivas);
		if (medicos <= 0) {
			p.put("tempoEstimadoMin", null);
			return p;
		}
		double minutos = (senhasAtivas * (double) TEMPO_MEDIO_ATENDIMENTO_MIN) / (double) medicos;
		long arredondadoParaCima = (long) Math.ceil(minutos);
		p.put("tempoEstimadoMin", Math.max(0L, arredondadoParaCima));
		return p;
	}

	private static String key(String unidadeAtendimento) {
		return "metrics:tempoAtendimentoMedio:" + unidadeAtendimento;
	}
}