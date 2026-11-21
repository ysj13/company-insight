package com.companyinsight.analysis;

import com.companyinsight.common.external.ErrorKind;
import java.time.Instant;
import java.util.List;

/**
 * 한 단계를 실행한 결과. 실패는 여기에 없다. 예외로 던진다.
 */
public sealed interface StageResult {

	/** 이 단계에서 끝났다. 다음 단계가 없다. */
	static Done ended() {
		return new Done(RunOutcome.ENDED, null, null, List.of(), null, null);
	}

	/**
	 * 다음 단계로 넘긴다.
	 *
	 * @param passReason 넘긴 이유 (예: TONE_BY_AI). 40자까지
	 * @param next       줄 세울 단계. 하나 이상
	 */
	static Done passed(String passReason, Next... next) {
		return new Done(RunOutcome.PASSED, passReason, null, List.of(next), null, null);
	}

	/** 본문이 없는 공시다. */
	static Done noBody() {
		return new Done(RunOutcome.NO_BODY, null, null, List.of(), null, null);
	}

	/** 판정을 보류한다. */
	static Done held() {
		return new Done(RunOutcome.HELD, null, null, List.of(), null, null);
	}

	/**
	 * 이 작업 하나를 실패로 세지 않고 미룬다.
	 *
	 * @param until   이 시각이 지나야 다시 꺼낸다
	 * @param kind    미룬 이유의 분류
	 * @param code    외부 소스의 원래 코드. 없으면 null
	 * @param message 설명
	 */
	static Deferred deferred(Instant until, ErrorKind kind, String code, String message) {
		return new Deferred(until, false, kind, code, message, null);
	}

	/**
	 * 이 작업을 미루고, 같은 쪽({@link WorkerGroup})이 꺼내는 대기 작업도 모두 그 시각 뒤로 민다.
	 * 하루 한도처럼 다음 작업을 꺼내도 같은 답이 올 때 쓴다.
	 */
	static Deferred deferredAll(Instant until, ErrorKind kind, String code, String message) {
		return new Deferred(until, true, kind, code, message, null);
	}

	/**
	 * 단계를 끝냈다.
	 *
	 * @param outcome    끝난 모양. {@link RunOutcome#finishesJob()}인 값만
	 * @param passReason 넘긴 이유. 넘기지 않았으면 null
	 * @param resultJson 실행 기록에 남길 것(JSON 글). 없으면 null
	 * @param next       줄 세울 다음 단계. 넘겼을 때만 있고, 넘겼으면 하나 이상이다
	 * @param writes     끝을 기록하는 트랜잭션 안에서 함께 실행할 DB 쓰기 (본문 저장, 공시의 판정 고치기 등). 없으면 null
	 * @param aiUsage    AI를 불렀으면 그 기록. 아니면 null
	 */
	record Done(RunOutcome outcome, String passReason, String resultJson, List<Next> next, Runnable writes,
			AiUsage aiUsage) implements StageResult {

		public Done {
			next = List.copyOf(next);
			if (!outcome.finishesJob()) {
				throw new IllegalArgumentException("단계를 끝내는 결과가 아닙니다: " + outcome);
			}
			if ((outcome == RunOutcome.PASSED) == next.isEmpty()) {
				throw new IllegalArgumentException("다음 단계는 넘겼을 때만, 넘겼으면 하나 이상 있어야 합니다: " + outcome);
			}
		}

		/** 실행 기록에 남길 것을 더한다. */
		public Done withResult(String json) {
			return new Done(outcome, passReason, json, next, writes, aiUsage);
		}

		/** 끝을 기록하는 트랜잭션 안에서 함께 실행할 DB 쓰기를 더한다. */
		public Done withWrites(Runnable dbWrites) {
			return new Done(outcome, passReason, resultJson, next, dbWrites, aiUsage);
		}

		/** AI 호출 기록을 더한다. */
		public Done withAiUsage(AiUsage usage) {
			return new Done(outcome, passReason, resultJson, next, writes, usage);
		}
	}

	/**
	 * 실패로 세지 않고 미뤘다.
	 *
	 * @param until      이 시각이 지나야 다시 꺼낸다
	 * @param wholeGroup 같은 쪽의 대기 작업을 모두 미룰지
	 * @param kind       미룬 이유의 분류
	 * @param code       외부 소스의 원래 코드. 없으면 null
	 * @param message    설명
	 * @param aiUsage    AI를 불렀으면 그 기록(응답을 받지 못해 토큰은 없다). 아니면 null
	 */
	record Deferred(Instant until, boolean wholeGroup, ErrorKind kind, String code, String message, AiUsage aiUsage)
			implements StageResult {

		/** AI 호출 기록을 더한다. */
		public Deferred withAiUsage(AiUsage usage) {
			return new Deferred(until, wholeGroup, kind, code, message, usage);
		}
	}

	/**
	 * 줄 세울 다음 단계.
	 *
	 * @param stage  단계
	 * @param reason 대기가 된 이유. null이면 워커가 정한다 (다시 돌리기에서 이어지면 RERUN, 아니면 NEW)
	 */
	record Next(AnalysisStage stage, EnqueueReason reason) {

		/** 이유를 워커가 정하는 다음 단계. */
		public static Next of(AnalysisStage stage) {
			return new Next(stage, null);
		}
	}
}
