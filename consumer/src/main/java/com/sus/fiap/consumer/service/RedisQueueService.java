package com.sus.fiap.consumer.service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sus.fiap.consumer.persistence.entity.AtendimentosUnidade;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class RedisQueueService {
	private static final Logger log = LoggerFactory.getLogger(RedisQueueService.class);
	private static final Duration TICKET_TTL = Duration.ofDays(7);
	private static final long SCORE_BUCKET = 1_000_000L;

	private static final int PRIORIZACAO_NORMAL = 0;
	private static final int PRIORIZACAO_IDOSO = 1;
	private static final int PRIORIZACAO_GESTANTE = 2;
	private static final int PRIORIZACAO_EMERGENCIA = 3;

	private final StringRedisTemplate redis;
	private final ObjectMapper objectMapper;

	public RedisQueueService(StringRedisTemplate redis, ObjectMapper objectMapper) {
		this.redis = redis;
		this.objectMapper = objectMapper;
	}

	/**
	 * Fila ÚNICA por unidade.
	 * Ordem de atendimento (menor score primeiro):
	 * 1) Emergência 2) Gestante 3) Idoso 4) Normal.
	 */
	public void enqueue(String unidadeAtendimento, AtendimentosUnidade atendimento) {
		if (unidadeAtendimento == null || unidadeAtendimento.isBlank() || atendimento == null) {
			return;
		}
		Long nrSeq = atendimento.getNrSeqAtendimento();
		Integer nrSenha = atendimento.getNrSenhaAtendimento();
		Integer codPriorizacao = atendimento.getTipoPriorizacao() == null ? null : atendimento.getTipoPriorizacao().getCodTipoPriorizacao();
		if (nrSeq == null || nrSenha == null) {
			return;
		}
		double score = scoreFor(codPriorizacao, nrSenha);
		redis.opsForZSet().add(queueKey(unidadeAtendimento), String.valueOf(nrSeq), score);
	}

	public void remove(String unidadeAtendimento, String nrSeqAtendimento) {
		redis.opsForZSet().remove(queueKey(unidadeAtendimento), nrSeqAtendimento);
		redis.delete(atendimentoKey(unidadeAtendimento, nrSeqAtendimento));
	}

	public void saveAtendimentoSnapshot(String unidadeAtendimento, AtendimentosUnidade atendimento) {
		try {
			Map<String, Object> payload = new HashMap<>();
			payload.put("unidadeAtendimento", unidadeAtendimento);
			payload.put("nrSeqAtendimento", atendimento.getNrSeqAtendimento());
			payload.put("nrSenhaAtendimento", atendimento.getNrSenhaAtendimento());
			payload.put("codCadastroSusPaciente", atendimento.getPaciente() == null ? null : atendimento.getPaciente().getCodCadastroSusPaciente());
			payload.put("codTipoPriorizacao", atendimento.getTipoPriorizacao() == null ? null : atendimento.getTipoPriorizacao().getCodTipoPriorizacao());
			payload.put("codEstadoSenha", atendimento.getEstadoSenha() == null ? null : atendimento.getEstadoSenha().getCodTipoEstado());

			String json = objectMapper.writeValueAsString(payload);
			redis.opsForValue().set(atendimentoKey(unidadeAtendimento, String.valueOf(atendimento.getNrSeqAtendimento())), json, TICKET_TTL);
		} catch (Exception e) {
			log.warn("Falha ao salvar snapshot do atendimento no Redis: unidade={}, nrSeq={}",
					unidadeAtendimento, atendimento.getNrSeqAtendimento(), e);
		}
	}

	private static double scoreFor(Integer codTipoPriorizacao, int nrSenhaAtendimento) {
		int rank = switch (codTipoPriorizacao == null ? PRIORIZACAO_NORMAL : codTipoPriorizacao) {
			case PRIORIZACAO_EMERGENCIA -> 0;
			case PRIORIZACAO_GESTANTE -> 1;
			case PRIORIZACAO_IDOSO -> 2;
			case PRIORIZACAO_NORMAL -> 3;
			default -> 3;
		};
		int senha = normalizeSenha(nrSenhaAtendimento);
		long score = (rank * SCORE_BUCKET) + (long) senha;
		return (double) score;
	}

	private static int normalizeSenha(int nrSenhaAtendimento) {
		if (nrSenhaAtendimento <= 0) {
			return 1;
		}
		int mod = (nrSenhaAtendimento - 1) % 999;
		return mod + 1;
	}

	private static String queueKey(String unidadeAtendimento) {
		return "queue:zset:" + unidadeAtendimento;
	}

	private static String atendimentoKey(String unidadeAtendimento, String nrSeqAtendimento) {
		return "atendimento:" + unidadeAtendimento + ":" + nrSeqAtendimento;
	}
}
