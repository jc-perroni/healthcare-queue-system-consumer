package com.sus.fiap.consumer.persistence.entity;

import java.io.Serializable;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Embeddable
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class EstadoAtendimentoId implements Serializable {
	@Column(name = "NR_SEQ_ATENDIMENTO", nullable = false)
	private Long nrSeqAtendimento;

	@Column(name = "COD_TIPO_ESTADO", nullable = false)
	private Integer codTipoEstado;

	@Column(name = "TIMESTAMP_ESTADO", nullable = false)
	private Instant timestampEstado;

	public Long getNrSeqAtendimento() {
		return nrSeqAtendimento;
	}

	public Integer getCodTipoEstado() {
		return codTipoEstado;
	}

	public Instant getTimestampEstado() {
		return timestampEstado;
	}
}
