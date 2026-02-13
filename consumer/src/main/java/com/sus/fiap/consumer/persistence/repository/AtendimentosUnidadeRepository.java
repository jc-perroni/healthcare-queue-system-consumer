package com.sus.fiap.consumer.persistence.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.sus.fiap.consumer.persistence.entity.AtendimentosUnidade;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AtendimentosUnidadeRepository extends JpaRepository<AtendimentosUnidade, Long> {
	Optional<AtendimentosUnidade> findByNrSenhaAtendimentoAndPacienteCodCadastroSusPaciente(Integer nrSenhaAtendimento, Long codCadastroSusPaciente);

	boolean existsByPacienteCodCadastroSusPacienteAndEstadoSenhaCodTipoEstadoNotIn(Long codCadastroSusPaciente, Collection<Integer> estadosFinais);

	List<AtendimentosUnidade> findByPacienteCodCadastroSusPacienteAndEstadoSenhaCodTipoEstadoNotInOrderByNrSeqAtendimentoAsc(
			Long codCadastroSusPaciente,
			Collection<Integer> estadosFinais);

	long countByEstadoSenhaCodTipoEstadoNotInAndTipoPriorizacaoCodTipoPriorizacao(
			Collection<Integer> estadosFinais,
			Integer codTipoPriorizacao);
}
