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
@Table(name = "TIPO_PRIORIZACAO")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TipoPriorizacao {
	@Id
	@Column(name = "COD_TIPO_PRIORIZACAO", nullable = false)
	private Integer codTipoPriorizacao;

	@Column(name = "NOME_PRIORIZACAO", nullable = false, length = 50)
	private String nomePriorizacao;
}
