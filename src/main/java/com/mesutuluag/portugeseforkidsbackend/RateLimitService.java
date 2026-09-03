package com.mesutuluag.portugeseforkidsbackend;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
class RateLimitService {

	private static final int DAILY_LIMIT = 100;

	@Value("${rate-limit.enabled:true}")
	private boolean enabled;

	private final Map<String, Integer> requestCounts = new ConcurrentHashMap<>();

	void checkAndIncrement(String ipAddress) {
		if (!enabled) {
			return;
		}

		String key = LocalDate.now() + ":" + ipAddress;
		int count = requestCounts.merge(key, 1, Integer::sum);
		if (count > DAILY_LIMIT) {
			throw new DailyLimitExceededException();
		}
	}
}
