package com.companyinsight.analysis.table;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 공시 한 건의 크기(영향도)를 정하는 값. 경계값과 고정값은 바뀔 수 있어 코드에 두지 않고 설정으로 둔다.
 * 빠진 값이나 순서가 뒤집힌 경계가 있으면 앱이 뜨지 않는다.
 *
 * <p>경계는 "이상 ~ 미만"이다. 비율이 경계와 같으면 큰 쪽에 든다.
 *
 * @param earnings           실적(잠정실적, 손익구조 변동)
 * @param shareIncrease      주식 수가 늘어나는 일(유상증자)
 * @param audit              감사보고서 제출
 * @param subsidiaryStepDown 자회사의 공시일 때 크기를 몇 단계 낮출지. 낮춰도 1보다 작아지지 않는다
 */
@ConfigurationProperties("app.judgment.impact")
public record ImpactProperties(Earnings earnings, ShareIncrease shareIncrease, Audit audit, Integer subsidiaryStepDown) {

	public ImpactProperties {
		require(earnings != null, "earnings");
		require(shareIncrease != null, "share-increase");
		require(audit != null, "audit");
		require(subsidiaryStepDown != null && subsidiaryStepDown >= 0, "subsidiary-step-down");
	}

	/** 자회사의 공시면 크기를 낮춘다. */
	int stepDownForSubsidiary(int impact) {
		return Math.max(1, impact - subsidiaryStepDown);
	}

	/**
	 * 실적의 크기. 영업이익이 견주는 기간보다 얼마나 늘거나 줄었는지(증감율의 절댓값, %)로 정한다.
	 *
	 * @param neutralBelow   이보다 작으면 중립
	 * @param mediumFrom     이 이상이면 크기 2
	 * @param largeFrom      이 이상이면 크기 3
	 * @param turnaround     흑자 전환·적자 전환의 크기
	 * @param deficitWidened 두 기간 모두 적자이고 적자 폭이 늘었을 때의 크기
	 */
	public record Earnings(BigDecimal neutralBelow, BigDecimal mediumFrom, BigDecimal largeFrom, Integer turnaround,
			Integer deficitWidened) {

		public Earnings {
			require(neutralBelow != null && neutralBelow.signum() >= 0, "earnings.neutral-below");
			require(mediumFrom != null && mediumFrom.compareTo(neutralBelow) >= 0, "earnings.medium-from");
			require(largeFrom != null && largeFrom.compareTo(mediumFrom) >= 0, "earnings.large-from");
			require(turnaround != null && turnaround >= 1, "earnings.turnaround");
			require(deficitWidened != null && deficitWidened >= 1, "earnings.deficit-widened");
		}

		/** 증감율의 절댓값으로 크기를 정한다. 중립이면 0. */
		int sizeOf(BigDecimal absoluteRate) {
			if (absoluteRate.compareTo(neutralBelow) < 0) {
				return 0;
			}
			if (absoluteRate.compareTo(mediumFrom) < 0) {
				return 1;
			}
			return absoluteRate.compareTo(largeFrom) < 0 ? 2 : 3;
		}
	}

	/**
	 * 주식 수가 늘어나는 일의 크기. 새로 생기는 주식이 지금 발행주식총수의 몇 %인지로 정한다.
	 *
	 * @param mediumFrom 이 이상이면 크기 2
	 * @param largeFrom  이 이상이면 크기 3
	 */
	public record ShareIncrease(BigDecimal mediumFrom, BigDecimal largeFrom) {

		public ShareIncrease {
			require(mediumFrom != null && mediumFrom.signum() >= 0, "share-increase.medium-from");
			require(largeFrom != null && largeFrom.compareTo(mediumFrom) >= 0, "share-increase.large-from");
		}

		/** 비율로 크기를 정한다. 비율을 읽지 못했으면(null) 1. */
		int sizeOf(BigDecimal ratio) {
			if (ratio == null || ratio.compareTo(mediumFrom) < 0) {
				return 1;
			}
			return ratio.compareTo(largeFrom) < 0 ? 2 : 3;
		}
	}

	/**
	 * 감사보고서 제출의 크기. 여럿에 해당하면 큰 값을 쓴다.
	 *
	 * @param opinionNotClean 감사의견이 적정이 아닐 때(한정, 부적정, 의견거절)
	 * @param goingConcern    계속기업 존속 불확실성에 해당할 때
	 * @param internalControl 내부회계관리제도 의견이 비적정일 때
	 */
	public record Audit(Integer opinionNotClean, Integer goingConcern, Integer internalControl) {

		public Audit {
			require(opinionNotClean != null && opinionNotClean >= 1, "audit.opinion-not-clean");
			require(goingConcern != null && goingConcern >= 1, "audit.going-concern");
			require(internalControl != null && internalControl >= 1, "audit.internal-control");
		}
	}

	private static void require(boolean ok, String name) {
		if (!ok) {
			throw new IllegalArgumentException("app.judgment.impact." + name + " 값이 없거나 올바르지 않습니다");
		}
	}
}
