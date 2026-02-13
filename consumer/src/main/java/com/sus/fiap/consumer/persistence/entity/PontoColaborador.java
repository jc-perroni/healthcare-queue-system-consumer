package com.sus.fiap.consumer.persistence.entity;

import java.time.LocalTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "PONTO_MEDICOS")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PontoColaborador {
	@Id
	@Column(name = "NR_SEQ_HORARIO", nullable = false)
	private Integer nrSeqHorario;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "COD_ID_COLABORADOR", nullable = false)
	private Colaborador colaborador;

	@Column(name = "HORARIO_ENTRADA", nullable = false)
	private LocalTime horarioEntrada;

	@Column(name = "HORARIO_SAIDA")
	private LocalTime horarioSaida;

	public void setHorarioSaida(LocalTime horarioSaida) {
		this.horarioSaida = horarioSaida;
	}
}
