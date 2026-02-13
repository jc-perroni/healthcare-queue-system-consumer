package com.sus.fiap.consumer.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "ESTADO_ATENDIMENTO")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EstadoAtendimento {
	@EmbeddedId
	private EstadoAtendimentoId id;

	@Column(name = "NR_SEQ_ESTADO_ATENDIMENTO", insertable = false, updatable = false)
	private Long nrSeqEstadoAtendimento;
}
