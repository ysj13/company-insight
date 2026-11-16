package com.companyinsight.common.external;

import com.companyinsight.common.config.ClockConfig;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 외부 소스 일시 정지. 하루 한도 소진이나 인증 오류가 나면 그 소스 호출을 한동안 막는다.
 * 계속 호출해 봐야 같은 오류만 쌓이고, 한도가 있는 경우 다음 날 몫까지 깎을 수 있기 때문이다.
 *
 * <p>메모리에만 둔다. 앱을 재시작하면 풀리는데, 키를 고친 뒤 재시작하는 흐름과 맞고 서버가 1대라 공유할 필요가 없다.
 */
@Component
public class SourcePauseRegistry {

	private static final Logger log = LoggerFactory.getLogger(SourcePauseRegistry.class);

	/** 인증 오류는 키를 고쳐 재시작할 때까지 의미가 없지만, 일시적인 인증 서버 문제일 수도 있어 1시간 뒤 다시 시도한다. */
	static final Duration AUTH_PAUSE = Duration.ofHours(1);

	private final Clock clock;
	private final Map<String, Pause> pauses = new ConcurrentHashMap<>();

	public SourcePauseRegistry(Clock clock) {
		this.clock = clock;
	}

	/** 오류 분류에 따라 정지한다. 정지 대상이 아니면 아무것도 하지 않는다. */
	public void pauseFor(ExternalApiException e) {
		if (!e.kind().pausesSource()) {
			return;
		}
		Instant until = switch (e.kind()) {
			case QUOTA_EXCEEDED -> nextMidnightKst();
			default -> clock.instant().plus(AUTH_PAUSE);
		};
		pauses.put(e.source(), new Pause(until, e.kind(), e.code()));
		log.warn("{} 호출 정지: {} [{}], {}까지", e.source(), e.kind(), e.code(), until);
	}

	/**
	 * 지금 이 소스가 정지 중인지.
	 *
	 * @return 정지 중이면 정지 정보, 아니면(정지 시각이 지났으면 포함) 비어 있음
	 */
	public Optional<Pause> pausedNow(String source) {
		Pause pause = pauses.get(source);
		if (pause == null) {
			return Optional.empty();
		}
		if (!clock.instant().isBefore(pause.until())) {
			pauses.remove(source, pause);
			return Optional.empty();
		}
		return Optional.of(pause);
	}

	private Instant nextMidnightKst() {
		return LocalDate.now(clock.withZone(ClockConfig.KST)).plusDays(1).atStartOfDay(ClockConfig.KST).toInstant();
	}

	/**
	 * 정지 정보.
	 *
	 * @param until 이 시각까지 호출하지 않는다
	 * @param kind  정지를 일으킨 오류 분류
	 * @param code  소스의 원래 오류 코드
	 */
	public record Pause(Instant until, ErrorKind kind, String code) {
	}
}
