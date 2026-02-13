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
@Table(name = "FUNCOES_COLAB_UNIDADE")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FuncoesColabUnidade {
	@Id
	@Column(name = "COD_ID_FUNCAO", nullable = false)
	private Integer codIdFuncao;

	@Column(name = "NOME_FUNCAO", nullable = false, length = 100)
	private String nomeFuncao;
}
