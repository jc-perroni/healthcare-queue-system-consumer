package com.sus.fiap.consumer.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "CADASTRO_SUS")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CadastroSus {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "COD_CADASTRO_SUS_PACIENTE", nullable = false)
	private Long codCadastroSusPaciente;

	@Column(name = "NOME_PACIENTE", nullable = false, length = 150)
	private String nomePaciente;

	@Column(name = "IDADE_PACIENTE", nullable = false)
	private Integer idadePaciente;

	@Column(name = "INDICADOR_GESTANTE", length = 1)
	private String indicadorGestante;

	public boolean isGestante() {
		return "S".equalsIgnoreCase(indicadorGestante);
	}
}
