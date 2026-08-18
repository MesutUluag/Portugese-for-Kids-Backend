package com.mesutuluag.portugeseforkidsbackend;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

@Service
class RateLimitService {

	private static final int DAILY_LIMIT = 50;

	private final Map<String, Integer> requestCounts = new ConcurrentHashMap<>();

	void checkAndIncrement(String ipAddress) {
		String key = LocalDate.now() + ":" + ipAddress;
		int count = requestCounts.merge(key, 1, Integer::sum);
		if (count > DAILY_LIMIT) {
			throw new DailyLimitExceededException();
		}
	}
}
