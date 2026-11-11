package com.mesutuluag.portugeseforkidsbackend.commons;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import com.mesutuluag.portugeseforkidsbackend.exception.DailyLimitExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitServiceTest {

    private RateLimitService service;

    @BeforeEach
    void setUp() {
        service = new RateLimitService(250, true);
    }

    // -------------------------------------------------------------------------
    // resolveClientIp tests
    // -------------------------------------------------------------------------

    @Test
    void resolveClientIp_withNullRequest_returnsUnknown() {
        assertThat(RateLimitService.resolveClientIp(null)).isEqualTo("unknown");
    }

    @Test
    void resolveClientIp_withRemoteAddrOnly_returnsRemoteAddr() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.50");

        assertThat(RateLimitService.resolveClientIp(request)).isEqualTo("192.168.1.50");
    }

    @Test
    void resolveClientIp_withXForwardedForSingleIp_returnsClientIp() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1"); // proxy address
        request.addHeader("X-Forwarded-For", "203.0.113.195");

        assertThat(RateLimitService.resolveClientIp(request)).isEqualTo("203.0.113.195");
    }

    @Test
    void resolveClientIp_withXForwardedForMultipleIps_returnsFirstClientIp() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1"); // proxy address
        request.addHeader("X-Forwarded-For", "203.0.113.195, 70.41.3.18, 150.172.238.178");

        assertThat(RateLimitService.resolveClientIp(request)).isEqualTo("203.0.113.195");
    }

    @Test
    void resolveClientIp_withBlankRemoteAddrAndBlankHeader_returnsUnknown() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("");
        request.addHeader("X-Forwarded-For", "   ");

        assertThat(RateLimitService.resolveClientIp(request)).isEqualTo("unknown");
    }

    @Test
    void resolveClientIp_whenRemoteAddrAlreadyResolvedToPublicIp_ignoresLeftoverSpoofedXForwardedFor() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        // Simulates Tomcat RemoteIpValve having resolved remoteAddr to the real client IP
        // while leaving the client's spoofed prefix in X-Forwarded-For.
        request.setRemoteAddr("203.0.113.195");
        request.addHeader("X-Forwarded-For", "1.2.3.4");

        assertThat(RateLimitService.resolveClientIp(request)).isEqualTo("203.0.113.195");
    }

    @Test
    void resolveClientIp_withLeadingCommaInXForwardedFor_skipsEmptySegment() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", " , 203.0.113.195");

        assertThat(RateLimitService.resolveClientIp(request)).isEqualTo("203.0.113.195");
    }

    // -------------------------------------------------------------------------
    // checkAndIncrement tests
    // -------------------------------------------------------------------------

    @Test
    void checkAndIncrement_withinLimit_doesNotThrow() {
        assertThatCode(() -> {
            for (int i = 0; i < 250; i++) {
                service.checkAndIncrement("198.51.100.1");
            }
        }).doesNotThrowAnyException();
    }

    @Test
    void checkAndIncrement_exceedsLimit_throwsDailyLimitExceededException() {
        for (int i = 0; i < 250; i++) {
            service.checkAndIncrement("198.51.100.2");
        }

        assertThatThrownBy(() -> service.checkAndIncrement("198.51.100.2"))
                .isInstanceOf(DailyLimitExceededException.class);
    }

    @Test
    void checkAndIncrement_differentIpsHaveIndependentLimits() {
        for (int i = 0; i < 250; i++) {
            service.checkAndIncrement("198.51.100.3");
        }

        // Another IP has not exceeded the limit
        assertThatCode(() -> service.checkAndIncrement("198.51.100.4"))
                .doesNotThrowAnyException();
    }

    @Test
    void checkAndIncrement_whenDisabled_allowsUnlimitedRequests() {
        ReflectionTestUtils.setField(service, "enabled", false);

        assertThatCode(() -> {
            for (int i = 0; i < 300; i++) {
                service.checkAndIncrement("198.51.100.5");
            }
        }).doesNotThrowAnyException();
    }

    @Test
    void checkAndIncrement_withHttpServletRequest_extractsIpAndIncrements() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "198.51.100.6");

        assertThatCode(() -> service.checkAndIncrement(request))
                .doesNotThrowAnyException();
    }

    @Test
    void checkAndIncrement_withNullOrBlankIp_safelyUsesUnknown() {
        assertThatCode(() -> service.checkAndIncrement((String) null))
                .doesNotThrowAnyException();
        assertThatCode(() -> service.checkAndIncrement("   "))
                .doesNotThrowAnyException();
    }

    @Test
    @SuppressWarnings("unchecked")
    void checkAndIncrement_dateRollover_purgesPreviousDayKeys() {
        RateLimitService customService = new RateLimitService(2, true);
        customService.checkAndIncrement("198.51.100.10");
        customService.checkAndIncrement("198.51.100.10");
        assertThatThrownBy(() -> customService.checkAndIncrement("198.51.100.10"))
                .isInstanceOf(DailyLimitExceededException.class);

        // Simulate currentDate having been yesterday and a stale yesterday key in map
        AtomicReference<LocalDate> dateRef = (AtomicReference<LocalDate>) ReflectionTestUtils.getField(customService, "currentDate");
        java.util.Map<String, Integer> counts = (java.util.Map<String, Integer>) ReflectionTestUtils.getField(customService, "requestCounts");
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        dateRef.set(yesterday);
        counts.clear();
        counts.put(yesterday + ":198.51.100.10", 2);

        // Next call rolls over to today, purging yesterday's entry and allowing today's request
        assertThatCode(() -> customService.checkAndIncrement("198.51.100.10"))
                .doesNotThrowAnyException();
        assertThat(customService.getTrackedKeyCount()).isEqualTo(1);
    }

    @Test
    void checkAndIncrement_authenticatedUser_usesAuthenticatedLimitAndUidKey() {
        RateLimitService tieredService = new RateLimitService(5, 2, true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.10");

        // 5 requests with userId succeed
        for (int i = 0; i < 5; i++) {
            tieredService.checkAndIncrement(request, "user-abc");
        }

        // 6th fails
        assertThatThrownBy(() -> tieredService.checkAndIncrement(request, "user-abc"))
                .isInstanceOf(DailyLimitExceededException.class);
    }

    @Test
    void checkAndIncrement_anonymousUser_usesAnonymousLimitAndIpKey() {
        RateLimitService tieredService = new RateLimitService(5, 2, true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.20");

        // Anonymous limit is 2
        tieredService.checkAndIncrement(request, null);
        tieredService.checkAndIncrement(request, null);

        // 3rd fails
        assertThatThrownBy(() -> tieredService.checkAndIncrement(request, null))
                .isInstanceOf(DailyLimitExceededException.class);
    }
}
