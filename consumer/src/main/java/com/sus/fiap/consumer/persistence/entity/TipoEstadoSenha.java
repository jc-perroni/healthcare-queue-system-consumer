package com.sus.fiap.consumer.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "TIPO_ESTADO_SENHA")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TipoEstadoSenha {
	@Id
	@Column(name = "COD_TIPO_ESTADO", nullable = false)
	private Integer codTipoEstado;

	@Column(name = "NOME_STATUS", nullable = false, length = 100)
	private String nomeStatus;

	@Column(name = "DESCRICAO_STATUS")
	private String descricaoStatus;
}
