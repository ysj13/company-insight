package com.companyinsight.common.schedule;

import com.companyinsight.common.external.ExternalApiException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 같은 그룹의 작업이 겹쳐 실행되지 않게 한다.
 *
 * <p>가상 스레드를 켜면 스케줄 작업이 매번 새 스레드에서 실행되어, 이전 실행이 끝나지 않아도 다음 실행이 시작된다.
 * 외부 API가 느릴 때 같은 수집이 겹치면 호출이 배로 늘고 같은 행을 동시에 갱신하게 된다.
 * 이미 실행 중이면 이번 실행은 건너뛴다. 다음 주기에 다시 실행되므로 놓치는 것은 없다.
 *
 * <p>작업이 던진 예외는 로그만 남기고 삼킨다. 수집 실패로 스케줄러나 앱이 멈추면 안 되기 때문이다.
 */
@Component
public class ExclusiveJobRunner {

	private static final Logger log = LoggerFactory.getLogger(ExclusiveJobRunner.class);

	private final Set<String> running = ConcurrentHashMap.newKeySet();

	/**
	 * @param group 겹치면 안 되는 작업들의 묶음 이름
	 * @param job   작업 이름 (로그용)
	 * @return 실행했으면 true, 같은 그룹이 실행 중이라 건너뛰었으면 false
	 */
	public boolean run(String group, String job, Runnable task) {
		if (!running.add(group)) {
			log.info("{} 건너뜀: {} 그룹의 다른 작업이 실행 중", job, group);
			return false;
		}
		try {
			task.run();
		}
		catch (ExternalApiException e) {
			// 메시지에 소스·분류·코드가 들어 있다. 스택은 필요 없다.
			log.error("{} 실패: {}", job, e.getMessage());
		}
		catch (RuntimeException e) {
			log.error("{} 실패", job, e);
		}
		finally {
			running.remove(group);
		}
		return true;
	}
}
