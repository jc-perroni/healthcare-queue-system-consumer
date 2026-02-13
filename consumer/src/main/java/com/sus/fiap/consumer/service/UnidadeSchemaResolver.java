package com.sus.fiap.consumer.service;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

@Service
public class UnidadeSchemaResolver {
	private static final Pattern UPA_PATTERN = Pattern.compile("^UPA\\s*(\\d+)$", Pattern.CASE_INSENSITIVE);

	public String resolveSchemaFromUnidade(String unidadeAtendimento) {
		if (unidadeAtendimento == null || unidadeAtendimento.isBlank()) {
			return "und_atd1";
		}
		String normalized = unidadeAtendimento.trim().toUpperCase(Locale.ROOT);
		Matcher matcher = UPA_PATTERN.matcher(normalized);
		if (matcher.matches()) {
			String n = matcher.group(1);
			return "und_atd" + n;
		}
		// fallback: se já vier como schema
		if (normalized.startsWith("UND_ATD")) {
			return normalized.toLowerCase(Locale.ROOT);
		}
		return "und_atd1";
	}
}
