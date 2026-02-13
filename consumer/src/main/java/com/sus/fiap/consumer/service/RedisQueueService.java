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

	private final StringRedisTemplate redis;
	private final ObjectMapper objectMapper;

	public RedisQueueService(StringRedisTemplate redis, ObjectMapper objectMapper) {
		this.redis = redis;
		this.objectMapper = objectMapper;
	}

	public void enqueueNormal(String unidadeAtendimento, String nrSeqAtendimento, double score) {
		redis.opsForZSet().add(queueKey(unidadeAtendimento), nrSeqAtendimento, score);
	}

	public void prioritize(String unidadeAtendimento, String nrSeqAtendimento) {
		Long counter = redis.opsForValue().increment(priorityCounterKey(unidadeAtendimento));
		long safeCounter = (counter == null ? 1L : counter);
		double score = -1.0d * safeCounter;
		redis.opsForZSet().add(queueKey(unidadeAtendimento), nrSeqAtendimento, score);
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

	private static String queueKey(String unidadeAtendimento) {
		return "queue:zset:" + unidadeAtendimento;
	}

	private static String priorityCounterKey(String unidadeAtendimento) {
		return "queue:priorityCounter:" + unidadeAtendimento;
	}

	private static String atendimentoKey(String unidadeAtendimento, String nrSeqAtendimento) {
		return "atendimento:" + unidadeAtendimento + ":" + nrSeqAtendimento;
	}
}
