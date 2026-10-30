package com.mesutuluag.portugeseforkidsbackend.commons;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.mesutuluag.portugeseforkidsbackend.exception.DailyLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RateLimitService {

	private static final int DAILY_LIMIT = 250;

	@Value("${rate-limit.enabled:true}")
	private boolean enabled;

	private final Map<String, Integer> requestCounts = new ConcurrentHashMap<>();

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

		String safeIp = (ipAddress != null && !ipAddress.isBlank()) ? ipAddress.trim() : "unknown";
		String key = LocalDate.now() + ":" + safeIp;
		int count = requestCounts.merge(key, 1, Integer::sum);
		if (count > DAILY_LIMIT) {
			throw new DailyLimitExceededException();
		}
	}
}
