package com.sus.fiap.consumer.persistence.repository;

import com.sus.fiap.consumer.persistence.entity.Colaborador;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ColaboradorRepository extends JpaRepository<Colaborador, Long> {
}
