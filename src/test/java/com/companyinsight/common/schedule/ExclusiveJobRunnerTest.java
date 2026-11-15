package com.companyinsight.common.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ExclusiveJobRunnerTest {

	private final ExclusiveJobRunner runner = new ExclusiveJobRunner();

	@Test
	void 같은_그룹이_실행_중이면_건너뛴다() throws Exception {
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger runs = new AtomicInteger();
		Thread first = Thread.ofVirtual().start(() -> runner.run("g", "first", () -> {
			runs.incrementAndGet();
			started.countDown();
			await(release);
		}));
		assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

		boolean sameGroup = runner.run("g", "second", runs::incrementAndGet);
		boolean otherGroup = runner.run("other", "third", runs::incrementAndGet);

		release.countDown();
		first.join(5000);
		assertThat(sameGroup).isFalse();
		assertThat(otherGroup).isTrue();
		assertThat(runs).hasValue(2);
	}

	@Test
	void 작업이_실패해도_예외를_던지지_않고_다음_실행을_막지_않는다() {
		boolean first = runner.run("g", "fails", () -> {
			throw new IllegalStateException("boom");
		});
		AtomicInteger runs = new AtomicInteger();

		boolean second = runner.run("g", "next", runs::incrementAndGet);

		assertThat(first).isTrue();
		assertThat(second).isTrue();
		assertThat(runs).hasValue(1);
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await(5, TimeUnit.SECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
