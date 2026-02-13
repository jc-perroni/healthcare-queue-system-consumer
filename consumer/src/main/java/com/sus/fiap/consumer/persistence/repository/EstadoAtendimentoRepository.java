package com.sus.fiap.consumer.persistence.repository;

import com.sus.fiap.consumer.persistence.entity.EstadoAtendimento;
import com.sus.fiap.consumer.persistence.entity.EstadoAtendimentoId;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EstadoAtendimentoRepository extends JpaRepository<EstadoAtendimento, EstadoAtendimentoId> {
}
