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

	private final int dailyLimit;
	private final boolean enabled;
	private final AtomicReference<LocalDate> currentDate;
	private final Map<String, Integer> requestCounts = new ConcurrentHashMap<>();

	public RateLimitService() {
		this(250, true);
	}

	@Autowired
	public RateLimitService(
			@Value("${rate-limit.daily-limit:250}") int dailyLimit,
			@Value("${rate-limit.enabled:true}") boolean enabled) {
		this.dailyLimit = dailyLimit;
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
		checkAndIncrement(resolveClientIp(request));
	}

	public void checkAndIncrement(String ipAddress) {
		if (!enabled) {
			return;
		}

		LocalDate today = LocalDate.now(ZoneOffset.UTC);
		LocalDate current = currentDate.get();
		if (!today.equals(current) && currentDate.compareAndSet(current, today)) {
			// Date rolled over — purge stale day counts
			requestCounts.keySet().removeIf(k -> !k.startsWith(today.toString()));
		}

		String safeIp = (ipAddress != null && !ipAddress.isBlank()) ? ipAddress.trim() : "unknown";
		String key = today + ":" + safeIp;

		// Bound count increment to avoid integer overflow
		int count = requestCounts.compute(key, (k, currentVal) -> {
			int val = (currentVal == null) ? 1 : currentVal + 1;
			return (val > dailyLimit + 1) ? dailyLimit + 1 : val;
		});

		if (count > dailyLimit) {
			throw new DailyLimitExceededException();
		}
	}

	int getDailyLimit() {
		return dailyLimit;
	}

	int getTrackedKeyCount() {
		return requestCounts.size();
	}
}
