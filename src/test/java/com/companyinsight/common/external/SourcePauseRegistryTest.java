package com.companyinsight.common.external;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class SourcePauseRegistryTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	@Test
	void 하루_한도_소진이면_한국시간_자정까지_정지() {
		Instant now = ZonedDateTime.of(2026, 11, 16, 15, 30, 0, 0, KST).toInstant();
		SourcePauseRegistry registry = new SourcePauseRegistry(Clock.fixed(now, ZoneOffset.UTC));

		registry.pauseFor(ExternalApiException.of("DART", ErrorKind.QUOTA_EXCEEDED, "020", "한도 초과"));

		assertThat(registry.pausedNow("DART")).hasValueSatisfying(p ->
			assertThat(p.until()).isEqualTo(ZonedDateTime.of(2026, 11, 17, 0, 0, 0, 0, KST).toInstant()));
		assertThat(registry.pausedNow("NAVER")).isEmpty();
	}

	@Test
	void 인증_오류면_1시간_정지() {
		Instant now = Instant.parse("2026-11-16T06:00:00Z");
		SourcePauseRegistry registry = new SourcePauseRegistry(Clock.fixed(now, ZoneOffset.UTC));

		registry.pauseFor(ExternalApiException.of("DART", ErrorKind.AUTH, "010", "미등록 키"));

		assertThat(registry.pausedNow("DART")).hasValueSatisfying(p ->
			assertThat(p.until()).isEqualTo(now.plus(SourcePauseRegistry.AUTH_PAUSE)));
	}

	@Test
	void 일시_오류는_정지하지_않는다() {
		SourcePauseRegistry registry = new SourcePauseRegistry(Clock.systemUTC());

		registry.pauseFor(ExternalApiException.of("DART", ErrorKind.TRANSIENT, "800", "점검"));

		assertThat(registry.pausedNow("DART")).isEmpty();
	}

	@Test
	void 정지_시각이_지나면_풀린다() {
		MutableClock clock = new MutableClock(Instant.parse("2026-11-16T06:00:00Z"));
		SourcePauseRegistry registry = new SourcePauseRegistry(clock);
		registry.pauseFor(ExternalApiException.of("DART", ErrorKind.AUTH, "010", "미등록 키"));

		clock.now = clock.now.plus(SourcePauseRegistry.AUTH_PAUSE);

		assertThat(registry.pausedNow("DART")).isEmpty();
	}

	private static final class MutableClock extends Clock {
		Instant now;

		MutableClock(Instant now) {
			this.now = now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return Clock.fixed(now, zone);
		}

		@Override
		public Instant instant() {
			return now;
		}
	}
}
