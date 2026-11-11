package com.mesutuluag.portugeseforkidsbackend.commons;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import com.mesutuluag.portugeseforkidsbackend.exception.DailyLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RateLimitService {

	private final int authenticatedDailyLimit;
	private final int anonymousDailyLimit;
	private final int userSyncDailyLimit;
	private final boolean enabled;
	private final AtomicReference<LocalDate> currentDate;
	private final Map<String, Integer> requestCounts = new ConcurrentHashMap<>();

	public RateLimitService() {
		this(250, 30, 500, true);
	}

	public RateLimitService(int dailyLimit, boolean enabled) {
		this(dailyLimit, dailyLimit, dailyLimit, enabled);
	}

	public RateLimitService(int authenticatedDailyLimit, int anonymousDailyLimit, boolean enabled) {
		this(authenticatedDailyLimit, anonymousDailyLimit, 500, enabled);
	}

	public RateLimitService(int authenticatedDailyLimit, int anonymousDailyLimit, int userSyncDailyLimit, boolean enabled) {
		this.authenticatedDailyLimit = authenticatedDailyLimit;
		this.anonymousDailyLimit = anonymousDailyLimit;
		this.userSyncDailyLimit = userSyncDailyLimit;
		this.enabled = enabled;
		this.currentDate = new AtomicReference<>(LocalDate.now(ZoneOffset.UTC));
	}

	@Autowired
	public RateLimitService(
			@Value("${rate-limit.authenticated-daily-limit:${rate-limit.daily-limit:250}}") int authenticatedDailyLimit,
			@Value("${rate-limit.anonymous-daily-limit:30}") int anonymousDailyLimit,
			@Value("${rate-limit.user-sync-daily-limit:500}") int userSyncDailyLimit,
			@Value("${rate-limit.enabled:true}") boolean enabled,
			@Value("${rate-limit.daily-limit:250}") int fallbackDailyLimit) {
		this.authenticatedDailyLimit = authenticatedDailyLimit;
		this.anonymousDailyLimit = anonymousDailyLimit;
		this.userSyncDailyLimit = userSyncDailyLimit;
		this.enabled = enabled;
		this.currentDate = new AtomicReference<>(LocalDate.now(ZoneOffset.UTC));
	}

	/**
	 * Resolves the client IP address from the request.
	 *
	 * <p>In production with {@code server.forward-headers-strategy=native}, Tomcat's
	 * {@code RemoteIpValve} walks {@code X-Forwarded-For} from right to left across trusted
	 * internal proxies and sets {@code request.getRemoteAddr()} to the real public client IP,
	 * leaving any untrusted client-spoofed prefix in {@code X-Forwarded-For}. Therefore, if
	 * {@code remoteAddr} is already a public (non-internal) IP, it is returned directly so a
	 * spoofed {@code X-Forwarded-For} header cannot bypass rate limiting.
	 *
	 * <p>When {@code remoteAddr} is an internal/loopback proxy address (e.g. in unit tests or
	 * when {@code RemoteIpValve} has not stripped the header), falls back to the first non-empty
	 * IP in {@code X-Forwarded-For}, then {@code remoteAddr}, or {@code "unknown"}.
	 */
	public static String resolveClientIp(HttpServletRequest request) {
		if (request == null) {
			return "unknown";
		}
		String remoteAddr = request.getRemoteAddr();
		String trimmedRemote = remoteAddr != null ? remoteAddr.trim() : "";

		// If Tomcat's RemoteIpValve already resolved remoteAddr to a public IP, trust it over
		// any leftover spoofed X-Forwarded-For header.
		if (!trimmedRemote.isEmpty() && !isInternalProxyAddress(trimmedRemote)) {
			return trimmedRemote;
		}

		String forwarded = request.getHeader("X-Forwarded-For");
		if (forwarded != null && !forwarded.isBlank()) {
			for (String part : forwarded.split(",")) {
				String candidate = part.trim();
				if (!candidate.isEmpty()) {
					return candidate;
				}
			}
		}

		return !trimmedRemote.isEmpty() ? trimmedRemote : "unknown";
	}

	/**
	 * Returns true if the address is a loopback, link-local, or RFC-1918 private proxy IP
	 * (matching Tomcat RemoteIpValve's default internalProxies ranges).
	 */
	static boolean isInternalProxyAddress(String ip) {
		if (ip.equals("127.0.0.1") || ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1")) {
			return true;
		}
		if (ip.startsWith("10.") || ip.startsWith("192.168.") || ip.startsWith("169.254.") || ip.startsWith("127.")) {
			return true;
		}
		if (ip.startsWith("172.")) {
			int secondDot = ip.indexOf('.', 4);
			if (secondDot > 4) {
				try {
					int secondOctet = Integer.parseInt(ip.substring(4, secondDot));
					return secondOctet >= 16 && secondOctet <= 31;
				} catch (NumberFormatException ignored) {
					return false;
				}
			}
		}
		return false;
	}

	public void checkAndIncrement(HttpServletRequest request) {
		checkAndIncrement(request, null);
	}

	public void checkAndIncrement(HttpServletRequest request, String userId) {
		if (userId != null && !userId.isBlank()) {
			checkAndIncrementKey("uid:" + userId.trim(), authenticatedDailyLimit);
		} else {
			String clientIp = resolveClientIp(request);
			String safeIp = (!clientIp.isBlank()) ? clientIp.trim() : "unknown";
			checkAndIncrementKey("ip:" + safeIp, anonymousDailyLimit);
		}
	}

	public void checkAndIncrementUserSync(HttpServletRequest request, String userId) {
		if (userId != null && !userId.isBlank()) {
			checkAndIncrementKey("user-sync:" + userId.trim(), userSyncDailyLimit);
		} else {
			String clientIp = resolveClientIp(request);
			String safeIp = (!clientIp.isBlank()) ? clientIp.trim() : "unknown";
			checkAndIncrementKey("user-sync-ip:" + safeIp, userSyncDailyLimit);
		}
	}

	public void checkAndIncrement(String ipAddress) {
		String safeIp = (ipAddress != null && !ipAddress.isBlank()) ? ipAddress.trim() : "unknown";
		checkAndIncrementKey(safeIp, authenticatedDailyLimit);
	}

	private void checkAndIncrementKey(String suffix, int limit) {
		if (!enabled) {
			return;
		}

		LocalDate today = LocalDate.now(ZoneOffset.UTC);
		LocalDate current = currentDate.get();
		if (!today.equals(current) && currentDate.compareAndSet(current, today)) {
			// Date rolled over — purge stale day counts
			requestCounts.keySet().removeIf(k -> !k.startsWith(today.toString()));
		}

		String key = today + ":" + suffix;

		// Bound count increment to avoid integer overflow
		int count = requestCounts.compute(key, (k, currentVal) -> {
			int val = (currentVal == null) ? 1 : currentVal + 1;
			return (val > limit + 1) ? limit + 1 : val;
		});

		if (count > limit) {
			throw new DailyLimitExceededException();
		}
	}

	public int getDailyLimit() {
		return authenticatedDailyLimit;
	}

	public int getAuthenticatedDailyLimit() {
		return authenticatedDailyLimit;
	}

	public int getAnonymousDailyLimit() {
		return anonymousDailyLimit;
	}

	public int getUserSyncDailyLimit() {
		return userSyncDailyLimit;
	}

	int getTrackedKeyCount() {
		return requestCounts.size();
	}
}
