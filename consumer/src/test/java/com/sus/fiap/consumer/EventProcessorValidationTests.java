package com.sus.fiap.consumer;

import java.time.Instant;
import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sus.fiap.consumer.model.EventEnvelope;
import com.sus.fiap.consumer.model.EventType;
import com.sus.fiap.consumer.persistence.entity.CadastroSus;
import com.sus.fiap.consumer.persistence.entity.Colaborador;
import com.sus.fiap.consumer.persistence.entity.FuncoesColabUnidade;
import com.sus.fiap.consumer.persistence.entity.PontoColaborador;
import com.sus.fiap.consumer.persistence.entity.TipoEstadoSenha;
import com.sus.fiap.consumer.persistence.entity.TipoPriorizacao;
import com.sus.fiap.consumer.persistence.repository.AtendimentosUnidadeRepository;
import com.sus.fiap.consumer.persistence.repository.CadastroSusRepository;
import com.sus.fiap.consumer.persistence.repository.ColaboradorRepository;
import com.sus.fiap.consumer.persistence.repository.EstadoAtendimentoRepository;
import com.sus.fiap.consumer.persistence.repository.PontoColaboradorRepository;
import com.sus.fiap.consumer.persistence.repository.TipoEstadoSenhaRepository;
import com.sus.fiap.consumer.persistence.repository.TipoPriorizacaoRepository;
import com.sus.fiap.consumer.service.EventProcessor;
import com.sus.fiap.consumer.service.RedisIdempotencyService;
import com.sus.fiap.consumer.service.RedisQueueService;
import com.sus.fiap.consumer.service.TempoAtendimentoRedisService;
import com.sus.fiap.consumer.service.UnidadeSchemaResolver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

import java.util.List;

@ExtendWith(MockitoExtension.class)
class EventProcessorValidationTests {
	private final ObjectMapper objectMapper = new ObjectMapper();

	@Mock
	private RedisIdempotencyService idempotencyService;
	@Mock
	private CadastroSusRepository cadastroSusRepository;
	@Mock
	private TipoPriorizacaoRepository tipoPriorizacaoRepository;
	@Mock
	private TipoEstadoSenhaRepository tipoEstadoSenhaRepository;
	@Mock
	private AtendimentosUnidadeRepository atendimentosUnidadeRepository;
	@Mock
	private EstadoAtendimentoRepository estadoAtendimentoRepository;
	@Mock
	private ColaboradorRepository colaboradorRepository;
	@Mock
	private PontoColaboradorRepository pontoColaboradorRepository;
	@Mock
	private RedisQueueService redisQueueService;
	@Mock
	private TempoAtendimentoRedisService tempoAtendimentoRedisService;

	private EventProcessor processor;

	@BeforeEach
	void setUp() {
		processor = new EventProcessor(
				idempotencyService,
				cadastroSusRepository,
				tipoPriorizacaoRepository,
				tipoEstadoSenhaRepository,
				atendimentosUnidadeRepository,
				estadoAtendimentoRepository,
				colaboradorRepository,
				pontoColaboradorRepository,
				redisQueueService,
				new UnidadeSchemaResolver(),
				tempoAtendimentoRedisService
		);

		when(idempotencyService.isProcessed(any())).thenReturn(false);
	}

	@Test
	void medicoEntraNoPonto_shouldIgnoreWhenNotDoctor() throws Exception {
		Colaborador naoMedico = Colaborador.builder()
				.codIdColaborador(1L)
				.nomeColaborador("Fulano")
				.funcao(FuncoesColabUnidade.builder().codIdFuncao(99).nomeFuncao("RECEPCAO").build())
				.build();
		when(colaboradorRepository.findById(1L)).thenReturn(Optional.of(naoMedico));

		processor.process(envelope(EventType.MEDICO_ENTRA_NO_PONTO, "{\"codIdColaborador\":\"1\"}"));

		verifyNoInteractions(pontoColaboradorRepository);
		verify(tempoAtendimentoRedisService).updateTempoMedioPorTipo(eq("UPA1"));
		verify(idempotencyService).markProcessed(any());
	}

	@Test
	void medicoEntraNoPonto_shouldIgnoreSecondEntryWhenPointIsOpen() throws Exception {
		Colaborador medico = Colaborador.builder()
				.codIdColaborador(1L)
				.nomeColaborador("Dra. Maria")
				.funcao(FuncoesColabUnidade.builder().codIdFuncao(1).nomeFuncao("MEDICO").build())
				.build();
		when(colaboradorRepository.findById(1L)).thenReturn(Optional.of(medico));
		when(pontoColaboradorRepository
				.findFirstByColaboradorCodIdColaboradorAndHorarioSaidaIsNullOrderByHorarioEntradaDesc(1L))
				.thenReturn(Optional.of(PontoColaborador.builder()
						.nrSeqHorario(10)
						.colaborador(medico)
						.horarioEntrada(LocalTime.NOON)
						.horarioSaida(null)
						.build()));

		processor.process(envelope(EventType.MEDICO_ENTRA_NO_PONTO, "{\"codIdColaborador\":\"1\"}"));

		verify(pontoColaboradorRepository, never()).save(any());
		verify(idempotencyService, never()).nextSequence(anyString());
		verify(tempoAtendimentoRedisService).updateTempoMedioPorTipo(eq("UPA1"));
		verify(idempotencyService).markProcessed(any());
	}

	@Test
	void retiradaSenha_shouldCancelCurrentSenhaWhenPacienteAlreadyHasActiveAtendimento() throws Exception {
		CadastroSus paciente = CadastroSus.builder()
				.codCadastroSusPaciente(10L)
				.nomePaciente("Paciente")
				.idadePaciente(30)
				.indicadorGestante("N")
				.build();
		when(cadastroSusRepository.findById(10L)).thenReturn(Optional.of(paciente));
		when(tipoPriorizacaoRepository.getReferenceById(anyInt()))
				.thenReturn(TipoPriorizacao.builder().codTipoPriorizacao(0).nomePriorizacao("NORMAL").build());
		when(tipoEstadoSenhaRepository.getReferenceById(eq(91)))
				.thenReturn(TipoEstadoSenha.builder().codTipoEstado(91).nomeStatus("CANCELADA").build());
		when(estadoAtendimentoRepository.existsById(any())).thenReturn(false);
		when(atendimentosUnidadeRepository.save(any())).thenAnswer(inv -> {
			var entity = (com.sus.fiap.consumer.persistence.entity.AtendimentosUnidade) inv.getArgument(0);
			if (entity.getNrSeqAtendimento() == null) {
				entity.setNrSeqAtendimento(999L);
			}
			return entity;
		});
		when(atendimentosUnidadeRepository
					.findByPacienteCodCadastroSusPacienteAndEstadoSenhaCodTipoEstadoNotInOrderByNrSeqAtendimentoAsc(eq(10L), anyCollection()))
				.thenReturn(java.util.List.of(com.sus.fiap.consumer.persistence.entity.AtendimentosUnidade.builder()
						.nrSeqAtendimento(1L)
						.nrSenhaAtendimento(1)
						.paciente(paciente)
						.tipoPriorizacao(TipoPriorizacao.builder().codTipoPriorizacao(0).nomePriorizacao("NORMAL").build())
						.estadoSenha(TipoEstadoSenha.builder().codTipoEstado(1).nomeStatus("CRIADA").build())
						.build()));

		processor.process(envelope(EventType.RETIRADA_DE_SENHA, """
				{ 
				  \"unidadeAtendimento\": \"UPA1\", 
				  \"nrSenhaAtendimento\": 1,
				  \"codCadastroSusPaciente\": 10
				}
				"""));

		ArgumentCaptor<com.sus.fiap.consumer.persistence.entity.AtendimentosUnidade> captor = ArgumentCaptor.forClass(
				com.sus.fiap.consumer.persistence.entity.AtendimentosUnidade.class);
		verify(atendimentosUnidadeRepository).save(captor.capture());
		assertThat(captor.getValue().getEstadoSenha().getCodTipoEstado()).isEqualTo(91);
		verify(redisQueueService, never()).enqueue(anyString(), any());
		verify(redisQueueService, never()).saveAtendimentoSnapshot(anyString(), any());
		verify(tempoAtendimentoRedisService).updateTempoMedioPorTipo(eq("UPA1"));
		verify(idempotencyService).markProcessed(any());
	}

	@Test
	void retiradaSenha_shouldNormalizeSenhaAfter999() throws Exception {
		CadastroSus paciente = CadastroSus.builder()
				.codCadastroSusPaciente(10L)
				.nomePaciente("Paciente")
				.idadePaciente(30)
				.indicadorGestante("N")
				.build();
		when(cadastroSusRepository.findById(10L)).thenReturn(Optional.of(paciente));
		when(atendimentosUnidadeRepository
					.findByPacienteCodCadastroSusPacienteAndEstadoSenhaCodTipoEstadoNotInOrderByNrSeqAtendimentoAsc(eq(10L), anyCollection()))
				.thenReturn(java.util.List.of());
		when(atendimentosUnidadeRepository
				.findByNrSenhaAtendimentoAndPacienteCodCadastroSusPaciente(anyInt(), eq(10L)))
				.thenReturn(Optional.empty());
		when(tipoPriorizacaoRepository.getReferenceById(anyInt()))
				.thenReturn(TipoPriorizacao.builder().codTipoPriorizacao(0).nomePriorizacao("NORMAL").build());
		when(tipoEstadoSenhaRepository.getReferenceById(anyInt()))
				.thenReturn(TipoEstadoSenha.builder().codTipoEstado(1).nomeStatus("CRIADA").build());
		when(estadoAtendimentoRepository.existsById(any())).thenReturn(false);
		when(atendimentosUnidadeRepository.save(any())).thenAnswer(inv -> {
			var entity = (com.sus.fiap.consumer.persistence.entity.AtendimentosUnidade) inv.getArgument(0);
			if (entity.getNrSeqAtendimento() == null) {
				entity.setNrSeqAtendimento(123L);
			}
			return entity;
		});

		processor.process(envelope(EventType.RETIRADA_DE_SENHA, """
				{ 
				  \"unidadeAtendimento\": \"UPA1\", 
				  \"nrSenhaAtendimento\": 1000,
				  \"codCadastroSusPaciente\": 10
				}
				"""));

		ArgumentCaptor<com.sus.fiap.consumer.persistence.entity.AtendimentosUnidade> captor = ArgumentCaptor.forClass(
				com.sus.fiap.consumer.persistence.entity.AtendimentosUnidade.class);
		verify(atendimentosUnidadeRepository).save(captor.capture());
		assertThat(captor.getValue().getNrSenhaAtendimento()).isEqualTo(1);
		ArgumentCaptor<com.sus.fiap.consumer.persistence.entity.AtendimentosUnidade> filaCaptor = ArgumentCaptor.forClass(
				com.sus.fiap.consumer.persistence.entity.AtendimentosUnidade.class);
		verify(redisQueueService).enqueue(eq("UPA1"), filaCaptor.capture());
		assertThat(filaCaptor.getValue().getNrSeqAtendimento()).isEqualTo(123L);
		verify(tempoAtendimentoRedisService).updateTempoMedioPorTipo(eq("UPA1"));
		verify(idempotencyService).markProcessed(any());
	}

	@Test
	void retiradaSenha_shouldCancelLaterActiveSenhasKeepingFirst() throws Exception {
		CadastroSus paciente = CadastroSus.builder()
				.codCadastroSusPaciente(10L)
				.nomePaciente("Paciente")
				.idadePaciente(30)
				.indicadorGestante("N")
				.build();
		when(cadastroSusRepository.findById(10L)).thenReturn(Optional.of(paciente));
		when(tipoPriorizacaoRepository.getReferenceById(anyInt()))
				.thenReturn(TipoPriorizacao.builder().codTipoPriorizacao(0).nomePriorizacao("NORMAL").build());
		when(tipoEstadoSenhaRepository.getReferenceById(91))
				.thenReturn(TipoEstadoSenha.builder().codTipoEstado(91).nomeStatus("CANCELADA").build());
		when(estadoAtendimentoRepository.existsById(any())).thenReturn(false);
		java.util.concurrent.atomic.AtomicLong seq = new java.util.concurrent.atomic.AtomicLong(200L);
		when(atendimentosUnidadeRepository.save(any())).thenAnswer(inv -> {
			var entity = (com.sus.fiap.consumer.persistence.entity.AtendimentosUnidade) inv.getArgument(0);
			if (entity.getNrSeqAtendimento() == null) {
				entity.setNrSeqAtendimento(seq.incrementAndGet());
			}
			return entity;
		});

		var priNormal = TipoPriorizacao.builder().codTipoPriorizacao(0).nomePriorizacao("NORMAL").build();
		var estAtiva = TipoEstadoSenha.builder().codTipoEstado(1).nomeStatus("CRIADA").build();
		var a1 = com.sus.fiap.consumer.persistence.entity.AtendimentosUnidade.builder()
				.nrSeqAtendimento(100L)
				.nrSenhaAtendimento(1)
				.paciente(paciente)
				.tipoPriorizacao(priNormal)
				.estadoSenha(estAtiva)
				.build();
		var a2 = com.sus.fiap.consumer.persistence.entity.AtendimentosUnidade.builder()
				.nrSeqAtendimento(101L)
				.nrSenhaAtendimento(2)
				.paciente(paciente)
				.tipoPriorizacao(priNormal)
				.estadoSenha(estAtiva)
				.build();

		when(atendimentosUnidadeRepository
					.findByPacienteCodCadastroSusPacienteAndEstadoSenhaCodTipoEstadoNotInOrderByNrSeqAtendimentoAsc(eq(10L), anyCollection()))
				.thenReturn(List.of(a1, a2));

		processor.process(envelope(EventType.RETIRADA_DE_SENHA, """
				{ 
				  \"unidadeAtendimento\": \"UPA1\", 
				  \"nrSenhaAtendimento\": 3,
				  \"codCadastroSusPaciente\": 10
				}
				"""));

		ArgumentCaptor<com.sus.fiap.consumer.persistence.entity.AtendimentosUnidade> captor = ArgumentCaptor.forClass(
				com.sus.fiap.consumer.persistence.entity.AtendimentosUnidade.class);
		verify(atendimentosUnidadeRepository, times(2)).save(captor.capture());
		assertThat(captor.getAllValues().get(0).getNrSeqAtendimento()).isEqualTo(101L);
		assertThat(captor.getAllValues().get(0).getEstadoSenha().getCodTipoEstado()).isEqualTo(91);
		assertThat(captor.getAllValues().get(1).getNrSenhaAtendimento()).isEqualTo(3);
		assertThat(captor.getAllValues().get(1).getEstadoSenha().getCodTipoEstado()).isEqualTo(91);
		verify(redisQueueService).remove(eq("UPA1"), eq("101"));
		verify(redisQueueService, never()).enqueue(anyString(), any());
		verify(redisQueueService, never()).saveAtendimentoSnapshot(anyString(), any());
		verify(tempoAtendimentoRedisService).updateTempoMedioPorTipo(eq("UPA1"));
		verify(idempotencyService).markProcessed(any());
	}

	private EventEnvelope envelope(EventType type, String payloadJson) throws Exception {
		JsonNode payload = objectMapper.readTree(payloadJson);
		return new EventEnvelope(UUID.randomUUID(), type, Instant.parse("2026-02-13T12:10:00Z"), payload);
	}
}
