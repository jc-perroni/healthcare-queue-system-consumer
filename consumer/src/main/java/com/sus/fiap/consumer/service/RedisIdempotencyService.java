package com.sus.fiap.consumer.service;

import java.time.Duration;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class RedisIdempotencyService {
	private static final Duration DEFAULT_TTL = Duration.ofDays(7);

	private final StringRedisTemplate redis;

	public RedisIdempotencyService(StringRedisTemplate redis) {
		this.redis = redis;
	}

	public boolean isProcessed(UUID eventId) {
		Boolean exists = redis.hasKey(key(eventId));
		return exists != null && exists;
	}

	public void markProcessed(UUID eventId) {
		redis.opsForValue().set(key(eventId), "1", DEFAULT_TTL);
	}

	/**
	 * Gera um número sequencial via Redis (INCR) para chaves que não são identity no banco.
	 */
	public Long nextSequence(String key) {
		return redis.opsForValue().increment(key);
	}

	private static String key(UUID eventId) {
		return "event:processed:" + eventId;
	}
}
