package com.sus.fiap.consumer.persistence.repository;

import java.util.Optional;

import com.sus.fiap.consumer.persistence.entity.PontoColaborador;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PontoColaboradorRepository extends JpaRepository<PontoColaborador, Integer> {
	Optional<PontoColaborador> findFirstByColaboradorCodIdColaboradorAndHorarioSaidaIsNullOrderByHorarioEntradaDesc(Long codIdColaborador);

	long countByHorarioSaidaIsNull();
}
