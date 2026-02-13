package com.sus.fiap.consumer.service;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;

import com.fasterxml.jackson.databind.JsonNode;
import com.sus.fiap.consumer.model.EventEnvelope;

import com.sus.fiap.consumer.persistence.entity.AtendimentosUnidade;
import com.sus.fiap.consumer.persistence.entity.CadastroSus;
import com.sus.fiap.consumer.persistence.entity.EstadoAtendimento;
import com.sus.fiap.consumer.persistence.entity.EstadoAtendimentoId;
import com.sus.fiap.consumer.persistence.entity.PontoColaborador;
import com.sus.fiap.consumer.persistence.repository.AtendimentosUnidadeRepository;
import com.sus.fiap.consumer.persistence.repository.CadastroSusRepository;
import com.sus.fiap.consumer.persistence.repository.ColaboradorRepository;
import com.sus.fiap.consumer.persistence.repository.EstadoAtendimentoRepository;
import com.sus.fiap.consumer.persistence.repository.PontoColaboradorRepository;
import com.sus.fiap.consumer.persistence.repository.TipoEstadoSenhaRepository;
import com.sus.fiap.consumer.persistence.repository.TipoPriorizacaoRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class EventProcessor {
	private static final Logger log = LoggerFactory.getLogger(EventProcessor.class);

	// Códigos oficiais conforme carga do banco
	private static final int EST_SENHA_NORMAL_CRIADA = 1;
	private static final int EST_SENHA_PRIORIZADA_GESTANTE = 2;
	private static final int EST_SENHA_PRIORIZADA_IDOSO = 3;
	private static final int EST_SENHA_PRIORIZADA_EMERGENCIA = 4;
	private static final int EST_ATENDIMENTO_FINALIZADO = 6;
	private static final int EST_SENHA_EXPIRADA = 90;
	private static final int EST_SENHA_CANCELADA = 91;

	private static final int PRIORIZACAO_NORMAL = 0;
	private static final int PRIORIZACAO_IDOSO = 1;
	private static final int PRIORIZACAO_GESTANTE = 2;
	private static final int PRIORIZACAO_EMERGENCIA = 3;

	private static final int MAX_SENHA = 999;

	private final RedisIdempotencyService idempotencyService;
	private final CadastroSusRepository cadastroSusRepository;
	private final TipoPriorizacaoRepository tipoPriorizacaoRepository;
	private final TipoEstadoSenhaRepository tipoEstadoSenhaRepository;
	private final AtendimentosUnidadeRepository atendimentosUnidadeRepository;
	private final EstadoAtendimentoRepository estadoAtendimentoRepository;
	private final ColaboradorRepository colaboradorRepository;
	private final PontoColaboradorRepository pontoColaboradorRepository;
	private final RedisQueueService redisQueueService;
	private final UnidadeSchemaResolver unidadeSchemaResolver;
	private final TempoAtendimentoRedisService tempoAtendimentoRedisService;

	public EventProcessor(
			RedisIdempotencyService idempotencyService,
			CadastroSusRepository cadastroSusRepository,
			TipoPriorizacaoRepository tipoPriorizacaoRepository,
			TipoEstadoSenhaRepository tipoEstadoSenhaRepository,
			AtendimentosUnidadeRepository atendimentosUnidadeRepository,
			EstadoAtendimentoRepository estadoAtendimentoRepository,
			ColaboradorRepository colaboradorRepository,
			PontoColaboradorRepository pontoColaboradorRepository,
			RedisQueueService redisQueueService,
			UnidadeSchemaResolver unidadeSchemaResolver,
			TempoAtendimentoRedisService tempoAtendimentoRedisService
	) {
		this.idempotencyService = idempotencyService;
		this.cadastroSusRepository = cadastroSusRepository;
		this.tipoPriorizacaoRepository = tipoPriorizacaoRepository;
		this.tipoEstadoSenhaRepository = tipoEstadoSenhaRepository;
		this.atendimentosUnidadeRepository = atendimentosUnidadeRepository;
		this.estadoAtendimentoRepository = estadoAtendimentoRepository;
		this.colaboradorRepository = colaboradorRepository;
		this.pontoColaboradorRepository = pontoColaboradorRepository;
		this.redisQueueService = redisQueueService;
		this.unidadeSchemaResolver = unidadeSchemaResolver;
		this.tempoAtendimentoRedisService = tempoAtendimentoRedisService;
	}

	@Transactional
	public void process(EventEnvelope event) {
		if (TenantContext.getCurrentTenant() == null) {
			String unidadeAtendimento = optionalText(event.payload(), "unidadeAtendimento");
			String tenant = unidadeSchemaResolver.resolveSchemaFromUnidade(unidadeAtendimento);
			TenantContext.setCurrentTenant(tenant);
		}

		boolean clearInFinally = true;
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			clearInFinally = false;
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCompletion(int status) {
					TenantContext.clear();
				}
			});
		}

		try {
		if (idempotencyService.isProcessed(event.eventId())) {
			log.debug("Evento já processado (idempotência redis): {}", event.eventId());
			return;
		}

		String unidadeForMetrics = resolveUnidadeForMetrics(event.payload());
		boolean forceMetricsUpdate = event.type() == com.sus.fiap.consumer.model.EventType.MEDICO_ENTRA_NO_PONTO
				|| event.type() == com.sus.fiap.consumer.model.EventType.MEDICO_SAI_DO_PONTO
				|| event.type() == com.sus.fiap.consumer.model.EventType.ATENDIMENTO_FINALIZADO;

		boolean dirty = switch (event.type()) {
			case MEDICO_ENTRA_NO_PONTO -> handleMedicoEntraNoPonto(event.payload(), event.occurredAt());
			case MEDICO_SAI_DO_PONTO -> handleMedicoSaiDoPonto(event.payload(), event.occurredAt());
			case RETIRADA_DE_SENHA -> handleRetiradaSenha(event.payload(), event.occurredAt());
			case SENHA_PRIORIZADA -> handleSenhaPriorizada(event.payload(), event.occurredAt());
			case ATENDIMENTO_FINALIZADO -> handleAtendimentoTerminal(event.payload(), EST_ATENDIMENTO_FINALIZADO, event.occurredAt());
			case SENHA_EXPIRADA -> handleAtendimentoTerminal(event.payload(), EST_SENHA_EXPIRADA, event.occurredAt());
			default -> {
				log.warn("Tipo de evento não tratado: {}", event.type());
				yield false;
			}
		};

		runAfterCommit(() -> idempotencyService.markProcessed(event.eventId()));
		if (dirty || forceMetricsUpdate) {
			runAfterCommit(() -> tempoAtendimentoRedisService.updateTempoMedioPorTipo(unidadeForMetrics));
		}
		} finally {
			if (clearInFinally) {
				TenantContext.clear();
			}
		}
	}

	private String resolveUnidadeForMetrics(JsonNode payload) {
		String unidade = optionalText(payload, "unidadeAtendimento");
		if (unidade != null) {
			return unidade;
		}
		String tenant = TenantContext.getCurrentTenant();
		if (tenant == null) {
			return "UPA1";
		}
		String normalized = tenant.trim().toLowerCase(java.util.Locale.ROOT);
		if (normalized.startsWith("und_atd")) {
			String suffix = normalized.substring("und_atd".length());
			if (!suffix.isBlank() && suffix.chars().allMatch(Character::isDigit)) {
				return "UPA" + suffix;
			}
		}
		return "UPA1";
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

	private static void runAfterCommit(Runnable action) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			action.run();
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				action.run();
			}
		});
	}

	private boolean handleMedicoEntraNoPonto(JsonNode payload, Instant occurredAt) {
		long codIdColaborador = Long.parseLong(requiredText(payload, "codIdColaborador"));
		Instant eventTime = payloadTimestampOr(payload, occurredAt);
		LocalTime horarioEntrada = eventTime.atOffset(ZoneOffset.UTC).toLocalTime();

		var colaboradorOpt = colaboradorRepository.findById(codIdColaborador);
		if (colaboradorOpt.isEmpty()) {
			log.warn("Colaborador não encontrado para registrar ponto: codIdColaborador={}", codIdColaborador);
			return false;
		}
		var colaborador = colaboradorOpt.get();
		if (!isMedico(colaborador)) {
			String funcao = colaborador.getFuncao() == null ? null : colaborador.getFuncao().getNomeFuncao();
			Integer codFuncao = colaborador.getFuncao() == null ? null : colaborador.getFuncao().getCodIdFuncao();
			log.warn("Colaborador não é médico; ponto não será registrado: codIdColaborador={}, codFuncao={}, funcao={}",
					codIdColaborador, codFuncao, funcao);
			return false;
		}

		// Regra: não registra dupla entrada; só permite nova entrada após registrar saída
		var pontoAberto = pontoColaboradorRepository
				.findFirstByColaboradorCodIdColaboradorAndHorarioSaidaIsNullOrderByHorarioEntradaDesc(codIdColaborador);
		if (pontoAberto.isPresent()) {
			log.info("Entrada no ponto ignorada (já existe ponto aberto): codIdColaborador={}, nrSeqHorario={}",
					codIdColaborador, pontoAberto.get().getNrSeqHorario());
			return false;
		}

		Integer nrSeqHorario = nextSeqHorario();
		PontoColaborador ponto = PontoColaborador.builder()
				.nrSeqHorario(nrSeqHorario)
				.colaborador(colaborador)
				.horarioEntrada(horarioEntrada)
				.horarioSaida(null)
				.build();
		pontoColaboradorRepository.save(ponto);
		return true;
	}

	private boolean handleMedicoSaiDoPonto(JsonNode payload, Instant occurredAt) {
		long codIdColaborador = Long.parseLong(requiredText(payload, "codIdColaborador"));
		Instant eventTime = payloadTimestampOr(payload, occurredAt);
		LocalTime horarioSaida = eventTime.atOffset(ZoneOffset.UTC).toLocalTime();

		var colaboradorOpt = colaboradorRepository.findById(codIdColaborador);
		if (colaboradorOpt.isEmpty()) {
			log.warn("Colaborador não encontrado para registrar saída do ponto: codIdColaborador={}", codIdColaborador);
			return false;
		}
		if (!isMedico(colaboradorOpt.get())) {
			String funcao = colaboradorOpt.get().getFuncao() == null ? null : colaboradorOpt.get().getFuncao().getNomeFuncao();
			Integer codFuncao = colaboradorOpt.get().getFuncao() == null ? null : colaboradorOpt.get().getFuncao().getCodIdFuncao();
			log.warn("Colaborador não é médico; saída do ponto não será registrada: codIdColaborador={}, codFuncao={}, funcao={}",
					codIdColaborador, codFuncao, funcao);
			return false;
		}

		var pontoOpt = pontoColaboradorRepository
				.findFirstByColaboradorCodIdColaboradorAndHorarioSaidaIsNullOrderByHorarioEntradaDesc(codIdColaborador);
		if (pontoOpt.isEmpty()) {
			log.warn("Não há ponto em aberto para colaborador: codIdColaborador={}", codIdColaborador);
			return false;
		}
		PontoColaborador ponto = pontoOpt.get();
		ponto.setHorarioSaida(horarioSaida);
		pontoColaboradorRepository.save(ponto);
		return true;
	}

	private boolean handleRetiradaSenha(JsonNode payload, Instant occurredAt) {
		String unidade = requiredText(payload, "unidadeAtendimento");
		int nrSenhaAtendimentoRaw = (int) requiredLong(payload, "nrSenhaAtendimento");
		int nrSenhaAtendimento = normalizeSenha(nrSenhaAtendimentoRaw);
		long codCadastroSusPaciente = requiredLong(payload, "codCadastroSusPaciente");
		Instant eventTime = payloadTimestampOr(payload, occurredAt);

		CadastroSus paciente = cadastroSusRepository.findById(codCadastroSusPaciente)
				.orElse(null);
		if (paciente == null) {
			log.warn("Paciente não encontrado no CADASTRO_SUS: codCadastroSusPaciente={}", codCadastroSusPaciente);
			return false;
		}

		// Regra: se o paciente pegar mais de uma senha no mesmo período, manter apenas a primeira ativa e cancelar todas as posteriores.
		// Inclui a retirada atual: persistimos a senha atual já como CANCELADA para manter rastreabilidade.
		var estadosFinais = java.util.List.of(EST_ATENDIMENTO_FINALIZADO, EST_SENHA_EXPIRADA, EST_SENHA_CANCELADA);
		var ativos = atendimentosUnidadeRepository
				.findByPacienteCodCadastroSusPacienteAndEstadoSenhaCodTipoEstadoNotInOrderByNrSeqAtendimentoAsc(
						codCadastroSusPaciente, estadosFinais);
		if (!ativos.isEmpty()) {
			for (int i = 1; i < ativos.size(); i++) {
				cancelarAtendimento(ativos.get(i), unidade, eventTime);
			}

			PriorizacaoEstado pe = inferirPriorizacaoEstado(paciente);
			var tipoPriorizacao = tipoPriorizacaoRepository.getReferenceById(pe.codTipoPriorizacao);
			var estadoCancelada = tipoEstadoSenhaRepository.getReferenceById(EST_SENHA_CANCELADA);

			AtendimentosUnidade novaSenhaCancelada = AtendimentosUnidade.builder()
					.nrSenhaAtendimento(nrSenhaAtendimento)
					.paciente(paciente)
					.tipoPriorizacao(tipoPriorizacao)
					.estadoSenha(estadoCancelada)
					.build();
			novaSenhaCancelada = atendimentosUnidadeRepository.save(novaSenhaCancelada);
			salvarEstadoAtendimento(novaSenhaCancelada.getNrSeqAtendimento(), EST_SENHA_CANCELADA, eventTime);

			log.info("Senha posterior cancelada automaticamente (paciente ja possui senha ativa): codCadastroSusPaciente={}, nrSenhaAtendimento={}",
					codCadastroSusPaciente, nrSenhaAtendimento);
			return true;
		}

		// Regra: não permitir duplicar a mesma senha para o mesmo paciente enquanto ativa
		var sameSenha = atendimentosUnidadeRepository
				.findByNrSenhaAtendimentoAndPacienteCodCadastroSusPaciente(nrSenhaAtendimento, codCadastroSusPaciente)
				.orElse(null);
		if (sameSenha != null) {
			Integer estado = sameSenha.getEstadoSenha() == null ? null : sameSenha.getEstadoSenha().getCodTipoEstado();
			if (estado == null || (estado != EST_ATENDIMENTO_FINALIZADO && estado != EST_SENHA_EXPIRADA && estado != EST_SENHA_CANCELADA)) {
				log.info("Retirada de senha ignorada (senha já existe e não está finalizada): codCadastroSusPaciente={}, nrSenhaAtendimento={}, nrSeqAtendimentoExistente={}",
						codCadastroSusPaciente, nrSenhaAtendimento, sameSenha.getNrSeqAtendimento());
				return false;
			}
		}

		PriorizacaoEstado pe = inferirPriorizacaoEstado(paciente);
		var tipoPriorizacao = tipoPriorizacaoRepository.getReferenceById(pe.codTipoPriorizacao);
		var tipoEstado = tipoEstadoSenhaRepository.getReferenceById(pe.codTipoEstado);

		AtendimentosUnidade atendimento = AtendimentosUnidade.builder()
				.nrSenhaAtendimento(nrSenhaAtendimento)
				.paciente(paciente)
				.tipoPriorizacao(tipoPriorizacao)
				.estadoSenha(tipoEstado)
				.build();
		atendimento = atendimentosUnidadeRepository.save(atendimento);

		salvarEstadoAtendimento(atendimento.getNrSeqAtendimento(), pe.codTipoEstado, eventTime);

		double score = (double) nrSenhaAtendimento;
		redisQueueService.enqueueNormal(unidade, String.valueOf(atendimento.getNrSeqAtendimento()), score);
		redisQueueService.saveAtendimentoSnapshot(unidade, atendimento);
		return true;
	}

	private static int normalizeSenha(int nrSenhaAtendimento) {
		if (nrSenhaAtendimento <= 0) {
			return 1;
		}
		int mod = (nrSenhaAtendimento - 1) % MAX_SENHA;
		return mod + 1;
	}

	private static boolean isMedico(com.sus.fiap.consumer.persistence.entity.Colaborador colaborador) {
		if (colaborador == null || colaborador.getFuncao() == null) {
			return false;
		}
		String nomeFuncao = colaborador.getFuncao().getNomeFuncao();
		if (nomeFuncao == null) {
			return false;
		}
		return nomeFuncao.trim().equalsIgnoreCase("MEDICO") || nomeFuncao.trim().equalsIgnoreCase("MÉDICO");
	}

	private boolean handleAtendimentoTerminal(JsonNode payload, int codTipoEstado, Instant occurredAt) {
		String unidade = requiredText(payload, "unidadeAtendimento");
		long nrSeqAtendimento = Long.parseLong(requiredText(payload, "nrSeqAtendimento"));
		Instant eventTime = payloadTimestampOr(payload, occurredAt);

		AtendimentosUnidade atendimento = atendimentosUnidadeRepository.findById(nrSeqAtendimento)
				.orElse(null);
		if (atendimento == null) {
			log.warn("Atendimento não encontrado: nrSeqAtendimento={}", nrSeqAtendimento);
			return false;
		}

		atendimento.setEstadoSenha(tipoEstadoSenhaRepository.getReferenceById(codTipoEstado));
		atendimentosUnidadeRepository.save(atendimento);

		salvarEstadoAtendimento(nrSeqAtendimento, codTipoEstado, eventTime);
		redisQueueService.remove(unidade, String.valueOf(nrSeqAtendimento));
		return true;
	}

	private boolean handleSenhaPriorizada(JsonNode payload, Instant occurredAt) {
		String unidade = requiredText(payload, "unidadeAtendimento");
		long nrSeqAtendimento = Long.parseLong(requiredText(payload, "nrSeqAtendimento"));
		Instant eventTime = payloadTimestampOr(payload, occurredAt);

		AtendimentosUnidade atendimento = atendimentosUnidadeRepository.findById(nrSeqAtendimento)
				.orElse(null);
		if (atendimento == null) {
			log.warn("Atendimento não encontrado para priorização: nrSeqAtendimento={}", nrSeqAtendimento);
			return false;
		}

		atendimento.setTipoPriorizacao(tipoPriorizacaoRepository.getReferenceById(PRIORIZACAO_EMERGENCIA));
		atendimento.setEstadoSenha(tipoEstadoSenhaRepository.getReferenceById(EST_SENHA_PRIORIZADA_EMERGENCIA));
		atendimento = atendimentosUnidadeRepository.save(atendimento);

		salvarEstadoAtendimento(nrSeqAtendimento, EST_SENHA_PRIORIZADA_EMERGENCIA, eventTime);

		redisQueueService.prioritize(unidade, String.valueOf(nrSeqAtendimento));
		redisQueueService.saveAtendimentoSnapshot(unidade, atendimento);
		return true;
	}

	private void cancelarAtendimento(AtendimentosUnidade atendimento, String unidade, Instant eventTime) {
		atendimento.setEstadoSenha(tipoEstadoSenhaRepository.getReferenceById(EST_SENHA_CANCELADA));
		atendimentosUnidadeRepository.save(atendimento);
		salvarEstadoAtendimento(atendimento.getNrSeqAtendimento(), EST_SENHA_CANCELADA, eventTime);
		redisQueueService.remove(unidade, String.valueOf(atendimento.getNrSeqAtendimento()));
	}

	private void salvarEstadoAtendimento(Long nrSeqAtendimento, Integer codTipoEstado, Instant timestamp) {
		EstadoAtendimentoId id = new EstadoAtendimentoId(nrSeqAtendimento, codTipoEstado, timestamp);
		if (estadoAtendimentoRepository.existsById(id)) {
			return;
		}
		estadoAtendimentoRepository.save(EstadoAtendimento.builder().id(id).build());
	}

	private PriorizacaoEstado inferirPriorizacaoEstado(CadastroSus paciente) {
		Integer idade = paciente.getIdadePaciente() == null ? 0 : paciente.getIdadePaciente();
		if (paciente.isGestante()) {
			return new PriorizacaoEstado(PRIORIZACAO_GESTANTE, EST_SENHA_PRIORIZADA_GESTANTE);
		}
		if (idade >= 60) {
			return new PriorizacaoEstado(PRIORIZACAO_IDOSO, EST_SENHA_PRIORIZADA_IDOSO);
		}
		return new PriorizacaoEstado(PRIORIZACAO_NORMAL, EST_SENHA_NORMAL_CRIADA);
	}

	private record PriorizacaoEstado(Integer codTipoPriorizacao, Integer codTipoEstado) {
	}

	private Integer nextSeqHorario() {
		// Usamos Redis (INCR) como gerador simples de sequência para NR_SEQ_HORARIO.
		// A coluna não é identity no schema.
		try {
			Long seq = idempotencyService.nextSequence("seq:ponto_medicos");
			if (seq == null) {
				return (int) (System.currentTimeMillis() % Integer.MAX_VALUE);
			}
			if (seq > Integer.MAX_VALUE) {
				log.warn("Sequência NR_SEQ_HORARIO excedeu Integer.MAX_VALUE; usando fallback.");
				return (int) (System.currentTimeMillis() % Integer.MAX_VALUE);
			}
			return seq.intValue();
		} catch (Exception e) {
			log.warn("Falha ao gerar NR_SEQ_HORARIO via Redis; usando fallback timestamp.", e);
			return (int) (System.currentTimeMillis() % Integer.MAX_VALUE);
		}
	}

	private static Instant payloadTimestampOr(JsonNode payload, Instant fallback) {
		JsonNode ts = payload.get("timestamp");
		if (ts == null || ts.isNull()) {
			return fallback;
		}
		if (ts.isNumber()) {
			return instantFromEpochNumber(ts.asDouble());
		}
		String text = ts.asText();
		if (text == null || text.isBlank()) {
			return fallback;
		}
		try {
			return Instant.parse(text);
		} catch (Exception ignored) {
			double epoch = Double.parseDouble(text);
			return instantFromEpochNumber(epoch);
		}
	}

	private static Instant instantFromEpochNumber(double epoch) {
		if (epoch < 100_000_000_000d) {
			long seconds = (long) epoch;
			long nanos = (long) ((epoch - seconds) * 1_000_000_000d);
			return Instant.ofEpochSecond(seconds, nanos);
		}
		long millis = (long) epoch;
		return Instant.ofEpochMilli(millis);
	}

	private static String requiredText(JsonNode payload, String field) {
		JsonNode node = payload.get(field);
		if (node == null || node.isNull() || node.asText().isBlank()) {
			throw new IllegalArgumentException("Campo obrigatório ausente no payload: " + field);
		}
		return node.asText();
	}

	private static long requiredLong(JsonNode payload, String field) {
		JsonNode node = payload.get(field);
		if (node == null || node.isNull()) {
			throw new IllegalArgumentException("Campo obrigatório ausente no payload: " + field);
		}
		return node.asLong();
	}
}
