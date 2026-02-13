package com.sus.fiap.consumer.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "ATENDIMENTOS_UNIDADE")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AtendimentosUnidade {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "NR_SEQ_ATENDIMENTO", nullable = false)
	private Long nrSeqAtendimento;

	@Column(name = "NR_SENHA_ATENDIMENTO", nullable = false)
	private Integer nrSenhaAtendimento;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "COD_CADASTRO_SUS_PACIENTE", nullable = false)
	private CadastroSus paciente;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "COD_TIPO_PRIORIZACAO", nullable = false)
	private TipoPriorizacao tipoPriorizacao;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "COD_ESTADO_SENHA", nullable = false)
	private TipoEstadoSenha estadoSenha;
}
