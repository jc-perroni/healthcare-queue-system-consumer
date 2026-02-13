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
@Table(name = "COLABORADORES")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Colaborador {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "COD_ID_COLABORADOR", nullable = false)
	private Long codIdColaborador;

	@Column(name = "NOME_COLABORADOR", nullable = false, length = 150)
	private String nomeColaborador;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "COD_ID_FUNCAO", nullable = false)
	private FuncoesColabUnidade funcao;
}
