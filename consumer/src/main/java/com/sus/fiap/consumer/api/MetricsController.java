package com.sus.fiap.consumer.api;

import java.io.IOException;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sus.fiap.consumer.persistence.repository.AtendimentosUnidadeRepository;
import com.sus.fiap.consumer.persistence.repository.PontoColaboradorRepository;
import com.sus.fiap.consumer.service.TenantContext;
import com.sus.fiap.consumer.service.UnidadeSchemaResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/metrics")
public class MetricsController {
	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
	private static final long SCORE_BUCKET = 1_000_000L;
	private static final int TEMPO_MEDIO_ATENDIMENTO_MIN = 10;
	private static final int EST_ATENDIMENTO_FINALIZADO = 6;
	private static final int EST_SENHA_EXPIRADA = 90;
	private static final int EST_SENHA_CANCELADA = 91;

	private final StringRedisTemplate redis;
	private final UnidadeSchemaResolver unidadeSchemaResolver;
	private final PontoColaboradorRepository pontoColaboradorRepository;
	private final AtendimentosUnidadeRepository atendimentosUnidadeRepository;

	@Value("${METRICS_API_KEY:}")
	private String metricsApiKey;

	@Value("${METRICS_API_KEY_HEADER:X-API-KEY}")
	private String metricsApiKeyHeader;

	public MetricsController(
			StringRedisTemplate redis,
			UnidadeSchemaResolver unidadeSchemaResolver,
			PontoColaboradorRepository pontoColaboradorRepository,
			AtendimentosUnidadeRepository atendimentosUnidadeRepository
	) {
		this.redis = redis;
		this.unidadeSchemaResolver = unidadeSchemaResolver;
		this.pontoColaboradorRepository = pontoColaboradorRepository;
		this.atendimentosUnidadeRepository = atendimentosUnidadeRepository;
	}

	@GetMapping(value = "/tempo-espera/{unidade}", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<String> tempoEspera(
			@PathVariable("unidade") String unidade,
			@RequestHeader HttpHeaders headers
	) {
		ResponseEntity<String> auth = authorize(headers);
		if (auth != null) {
			return auth;
		}

		String unidadeNormalizada = normalizeUnidade(unidade);
		String json = redis.opsForValue().get(key(unidadeNormalizada));
		if (json == null || json.isBlank()) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
		}
		return ResponseEntity.ok(json);
	}

	@GetMapping(value = "/tempo-espera", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<String> tempoEsperaPorTipo(
			@RequestParam(name = "unidade", required = false) String unidade,
			@RequestParam(name = "tipo", required = false) Integer tipo,
			@RequestParam(name = "senha", required = false) Integer senha,
			@RequestParam(name = "codSus", required = false) Long codCadastroSusPaciente,
			@RequestHeader HttpHeaders headers
	) {
		ResponseEntity<String> auth = authorize(headers);
		if (auth != null) {
			return auth;
		}

		String unidadeNormalizada = normalizeUnidade(unidade);

		// Modo individual por paciente SUS: encontra o nrSeqAtendimento no banco e usa o score real no ZSET
		if (codCadastroSusPaciente != null) {
			Long nrSeq = resolveNrSeqAtendimentoAtivo(unidadeNormalizada, codCadastroSusPaciente);
			if (nrSeq == null) {
				return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
			}
			String member = String.valueOf(nrSeq);
			Double score = redis.opsForZSet().score(queueKey(unidadeNormalizada), member);
			if (score == null) {
				return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
			}
			long pessoasNaFrente = countAhead(unidadeNormalizada, score.doubleValue());
			long medicos = countMedicosEmAtendimento(unidadeNormalizada);
			if (medicos <= 0) {
				return ResponseEntity.ok(
						"{\"tempoEstimadoMin\":null,\"pessoasNaFrente\":" + pessoasNaFrente
								+ ",\"medicosEmAtendimento\":" + medicos
								+ ",\"nrSeqAtendimento\":" + nrSeq + "}"
				);
			}
			long tempoEstimadoMin = (long) Math.ceil((pessoasNaFrente * (double) TEMPO_MEDIO_ATENDIMENTO_MIN) / (double) medicos);
			return ResponseEntity.ok(
					"{\"tempoEstimadoMin\":" + tempoEstimadoMin
							+ ",\"pessoasNaFrente\":" + pessoasNaFrente
							+ ",\"medicosEmAtendimento\":" + medicos
							+ ",\"nrSeqAtendimento\":" + nrSeq + "}"
			);
		}

		int tipoInt = (tipo == null) ? -1 : tipo.intValue();
		String tipoKey = tipoKey(tipoInt);
		if (tipoKey == null) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST)
					.contentType(MediaType.APPLICATION_JSON)
					.body("{\"error\":\"tipo inválido (use 0=Normal,1=Idoso,2=Gestante,3=Emergência)\"}");
		}

		// Modo individual: calcula com base em quantas pessoas estão na frente na fila (ZSET)
		if (senha != null) {
			int senhaNorm = normalizeSenha(senha.intValue());
			long medicos = countMedicosEmAtendimento(unidadeNormalizada);
			if (medicos <= 0) {
				return ResponseEntity.ok(
						"{\"tempoEstimadoMin\":null,\"pessoasNaFrente\":0,\"medicosEmAtendimento\":" + medicos + "}"
				);
			}
			double score = scoreFor(tipoInt, senhaNorm);
			long pessoasNaFrente = countAhead(unidadeNormalizada, score);
			long tempoEstimadoMin = (long) Math.ceil((pessoasNaFrente * (double) TEMPO_MEDIO_ATENDIMENTO_MIN) / (double) medicos);
			return ResponseEntity.ok(
					"{\"tempoEstimadoMin\":" + tempoEstimadoMin
							+ ",\"pessoasNaFrente\":" + pessoasNaFrente
							+ ",\"medicosEmAtendimento\":" + medicos + "}"
			);
		}

		// Modo antigo: devolve apenas o tempo estimado do JSON agregado salvo no Redis
		String json = redis.opsForValue().get(key(unidadeNormalizada));
		if (json == null || json.isBlank()) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
		}

		try {
			JsonNode root = OBJECT_MAPPER.readTree(json);
			JsonNode tipoNode = root.path(tipoKey);
			if (tipoNode.isMissingNode() || tipoNode.isNull()) {
				return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
			}
			JsonNode tempoNode = tipoNode.get("tempoEstimadoMin");
			if (tempoNode == null || tempoNode.isNull()) {
				return ResponseEntity.ok("{\"tempoEstimadoMin\":null}");
			}
			if (!tempoNode.isNumber()) {
				return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
			}
			int tempoEstimado = tempoNode.intValue();
			return ResponseEntity.ok("{\"tempoEstimadoMin\":" + tempoEstimado + "}");
		} catch (IOException e) {
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
		}
	}

	private static String key(String unidadeAtendimento) {
		return "metrics:tempoAtendimentoMedio:" + unidadeAtendimento;
	}

	private static String queueKey(String unidadeAtendimento) {
		return "queue:zset:" + unidadeAtendimento;
	}

	private long countMedicosEmAtendimento(String unidadeNormalizada) {
		String tenant = unidadeSchemaResolver.resolveSchemaFromUnidade(unidadeNormalizada);
		TenantContext.setCurrentTenant(tenant);
		try {
			return pontoColaboradorRepository.countByHorarioSaidaIsNull();
		} finally {
			TenantContext.clear();
		}
	}

	private Long resolveNrSeqAtendimentoAtivo(String unidadeNormalizada, Long codCadastroSusPaciente) {
		if (codCadastroSusPaciente == null) {
			return null;
		}
		java.util.List<Integer> estadosFinais = java.util.List.of(
				EST_ATENDIMENTO_FINALIZADO,
				EST_SENHA_EXPIRADA,
				EST_SENHA_CANCELADA
		);
		String tenant = unidadeSchemaResolver.resolveSchemaFromUnidade(unidadeNormalizada);
		TenantContext.setCurrentTenant(tenant);
		try {
			var lista = atendimentosUnidadeRepository
					.findByPacienteCodCadastroSusPacienteAndEstadoSenhaCodTipoEstadoNotInOrderByNrSeqAtendimentoAsc(
							codCadastroSusPaciente,
							estadosFinais
					);
			if (lista == null || lista.isEmpty()) {
				return null;
			}
			var primeiro = lista.get(0);
			return primeiro == null ? null : primeiro.getNrSeqAtendimento();
		} finally {
			TenantContext.clear();
		}
	}

	private long countAhead(String unidadeNormalizada, double score) {
		ZSetOperations<String, String> zset = redis.opsForZSet();
		// ZCOUNT é inclusivo; como o score é inteiro, usar (score - 0.5) funciona como "< score".
		double maxExclusive = score - 0.5d;
		Long count = zset.count(queueKey(unidadeNormalizada), -Double.MAX_VALUE, maxExclusive);
		return count == null ? 0L : count.longValue();
	}

	private static double scoreFor(int tipoInt, int senhaNorm) {
		int rank = switch (tipoInt) {
			case 3 -> 0; // emergência
			case 2 -> 1; // gestante
			case 1 -> 2; // idoso
			case 0 -> 3; // normal
			default -> 3;
		};
		long score = (rank * SCORE_BUCKET) + (long) senhaNorm;
		return (double) score;
	}

	private static int normalizeSenha(int nrSenhaAtendimento) {
		if (nrSenhaAtendimento <= 0) {
			return 1;
		}
		int mod = (nrSenhaAtendimento - 1) % 999;
		return mod + 1;
	}

	private ResponseEntity<String> authorize(HttpHeaders headers) {
		if (metricsApiKey != null && !metricsApiKey.isBlank()) {
			String provided = headers.getFirst(metricsApiKeyHeader);
			if (provided == null || !constantTimeEquals(provided, metricsApiKey)) {
				return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
			}
		}
		return null;
	}

	private static String tipoKey(int tipo) {
		return switch (tipo) {
			case 0 -> "normal";
			case 1 -> "idoso";
			case 2 -> "gestante";
			case 3 -> "emergencia";
			default -> null;
		};
	}

	static String normalizeUnidade(String raw) {
		if (raw == null || raw.isBlank()) {
			return "UPA1";
		}
		String v = raw.trim();
		String upper = v.toUpperCase(Locale.ROOT);

		if (upper.startsWith("UPA")) {
			String suffix = upper.substring(3);
			if (!suffix.isBlank() && suffix.chars().allMatch(Character::isDigit)) {
				return "UPA" + suffix;
			}
			return upper;
		}

		if (upper.startsWith("UND_ATD")) {
			String suffix = upper.substring("UND_ATD".length());
			if (!suffix.isBlank() && suffix.chars().allMatch(Character::isDigit)) {
				return "UPA" + suffix;
			}
		}

		return upper;
	}

	private static boolean constantTimeEquals(String a, String b) {
		if (a == null || b == null) {
			return false;
		}
		byte[] x = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		byte[] y = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		int diff = x.length ^ y.length;
		for (int i = 0; i < Math.min(x.length, y.length); i++) {
			diff |= x[i] ^ y[i];
		}
		return diff == 0;
	}
}
