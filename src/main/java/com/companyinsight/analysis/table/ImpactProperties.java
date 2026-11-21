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
 * @param shareIncrease      주식 수가 늘어나는 일(유상증자, 주식 관련 사채 발행, 전환 청구 행사, 자기사채 매도)
 * @param shareDecrease      주식 수가 줄어드는 일(주식 소각)
 * @param amount             금액의 크기(공급계약, 시설 투자, 소송 청구). 매출액·자기자본 대비 비율로 정한다
 * @param audit              감사보고서 제출
 * @param ownership          지분 보고
 * @param guarantee          채무보증·담보 제공, 금전 대여
 * @param borrowing          차입
 * @param misconduct         횡령·배임·제재
 * @param fixed              비율 없이 정해 둔 크기(전환가액 하향 조정, 자산 재평가 이익)
 * @param subsidiaryStepDown 자회사의 공시일 때 크기를 몇 단계 낮출지. 낮춰도 1보다 작아지지 않는다
 */
@ConfigurationProperties("app.judgment.impact")
public record ImpactProperties(Earnings earnings, ShareIncrease shareIncrease, ShareDecrease shareDecrease, Amount amount,
		Audit audit, Ownership ownership, Guarantee guarantee, Borrowing borrowing, Misconduct misconduct, Fixed fixed,
		Integer subsidiaryStepDown) {

	public ImpactProperties {
		require(earnings != null, "earnings");
		require(shareIncrease != null, "share-increase");
		require(shareDecrease != null, "share-decrease");
		require(amount != null, "amount");
		require(audit != null, "audit");
		require(ownership != null, "ownership");
		require(guarantee != null, "guarantee");
		require(borrowing != null, "borrowing");
		require(misconduct != null, "misconduct");
		require(fixed != null, "fixed");
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
	 * 주식 수가 늘어나는 일의 크기. 새로 생기는(생길 수 있는) 주식이 발행주식총수의 몇 %인지로 정한다.
	 *
	 * @param mediumFrom           이 이상이면 크기 2
	 * @param largeFrom            이 이상이면 크기 3
	 * @param exerciseNeutralBelow 전환·신주인수권·교환 청구 행사에만 쓰는 하한. 이보다 작으면 중립
	 */
	public record ShareIncrease(BigDecimal mediumFrom, BigDecimal largeFrom, BigDecimal exerciseNeutralBelow) {

		public ShareIncrease {
			require(mediumFrom != null && mediumFrom.signum() >= 0, "share-increase.medium-from");
			require(largeFrom != null && largeFrom.compareTo(mediumFrom) >= 0, "share-increase.large-from");
			require(exerciseNeutralBelow != null && exerciseNeutralBelow.signum() >= 0
					&& exerciseNeutralBelow.compareTo(mediumFrom) <= 0, "share-increase.exercise-neutral-below");
		}

		/** 비율로 크기를 정한다. 비율을 읽지 못했으면(null) 1. */
		int sizeOf(BigDecimal ratio) {
			return steps(ratio, mediumFrom, largeFrom);
		}
	}

	/**
	 * 주식 수가 줄어드는 일의 크기. 없어지는 주식이 발행주식총수의 몇 %인지로 정한다. 늘어나는 쪽보다 비율이 훨씬 작아 경계를 따로 둔다.
	 *
	 * @param mediumFrom 이 이상이면 크기 2
	 * @param largeFrom  이 이상이면 크기 3
	 */
	public record ShareDecrease(BigDecimal mediumFrom, BigDecimal largeFrom) {

		public ShareDecrease {
			require(mediumFrom != null && mediumFrom.signum() >= 0, "share-decrease.medium-from");
			require(largeFrom != null && largeFrom.compareTo(mediumFrom) >= 0, "share-decrease.large-from");
		}

		/** 비율로 크기를 정한다. 비율을 읽지 못했으면(null) 1. */
		int sizeOf(BigDecimal ratio) {
			return steps(ratio, mediumFrom, largeFrom);
		}
	}

	/**
	 * 금액의 크기. 금액이 매출액·자기자본의 몇 %인지로 정한다(유형이 달라도 분포가 거의 같아 한 벌로 둔다).
	 *
	 * @param mediumFrom 이 이상이면 크기 2
	 * @param largeFrom  이 이상이면 크기 3
	 */
	public record Amount(BigDecimal mediumFrom, BigDecimal largeFrom) {

		public Amount {
			require(mediumFrom != null && mediumFrom.signum() >= 0, "amount.medium-from");
			require(largeFrom != null && largeFrom.compareTo(mediumFrom) >= 0, "amount.large-from");
		}

		/** 비율로 크기를 정한다. 비율을 읽지 못했으면(null) 1. */
		int sizeOf(BigDecimal ratio) {
			return steps(ratio, mediumFrom, largeFrom);
		}
	}

	/**
	 * 채무보증·담보 제공, 금전 대여의 크기. 자기자본 대비 비율이 작으면 일상적인 일로 보고 중립이다.
	 *
	 * @param negativeFrom 이 이상이면 악재 1
	 * @param mediumFrom   이 이상이면 악재 2
	 */
	public record Guarantee(BigDecimal negativeFrom, BigDecimal mediumFrom) {

		public Guarantee {
			require(negativeFrom != null && negativeFrom.signum() > 0, "guarantee.negative-from");
			require(mediumFrom != null && mediumFrom.compareTo(negativeFrom) >= 0, "guarantee.medium-from");
		}

		/** 자기자본 대비 비율로 크기를 정한다. 중립이면 0. */
		int sizeOf(BigDecimal ratio) {
			if (ratio.compareTo(negativeFrom) < 0) {
				return 0;
			}
			return ratio.compareTo(mediumFrom) < 0 ? 1 : 2;
		}
	}

	/**
	 * 차입의 크기. 자기 사업에 쓰는 돈이라 보증보다 높은 비율부터 악재로 본다.
	 *
	 * @param negativeFrom 자기자본 대비 비율이 이 이상이면 악재 1. 그 아래는 중립
	 */
	public record Borrowing(BigDecimal negativeFrom) {

		public Borrowing {
			require(negativeFrom != null && negativeFrom.signum() > 0, "borrowing.negative-from");
		}

		/** 자기자본 대비 비율로 크기를 정한다. 중립이면 0. */
		int sizeOf(BigDecimal ratio) {
			return ratio.compareTo(negativeFrom) < 0 ? 0 : 1;
		}
	}

	/**
	 * 횡령·배임·제재의 크기.
	 *
	 * @param embezzlementMinimum 횡령·배임의 가장 작은 크기. 금액 경계로 정한 크기가 이보다 작으면 이 값
	 * @param accountingViolation 회계처리기준 위반에 따른 조치(금액이 없는 서식)의 크기
	 */
	public record Misconduct(Integer embezzlementMinimum, Integer accountingViolation) {

		public Misconduct {
			require(embezzlementMinimum != null && embezzlementMinimum >= 1, "misconduct.embezzlement-minimum");
			require(accountingViolation != null && accountingViolation >= 1, "misconduct.accounting-violation");
		}
	}

	/**
	 * 견줄 비율이 없어 정해 둔 크기.
	 *
	 * @param conversionPriceDown 시가 하락에 따른 전환가액 등의 하향 조정(악재)
	 * @param revaluationGain     자산 재평가로 자산이 늘어남(호재)
	 */
	public record Fixed(Integer conversionPriceDown, Integer revaluationGain) {

		public Fixed {
			require(conversionPriceDown != null && conversionPriceDown >= 1, "fixed.conversion-price-down");
			require(revaluationGain != null && revaluationGain >= 1, "fixed.revaluation-gain");
		}
	}

	/** 두 경계로 1·2·3을 정한다. 비율을 읽지 못했으면(null) 1. */
	private static int steps(BigDecimal ratio, BigDecimal mediumFrom, BigDecimal largeFrom) {
		if (ratio == null || ratio.compareTo(mediumFrom) < 0) {
			return 1;
		}
		return ratio.compareTo(largeFrom) < 0 ? 2 : 3;
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

	/**
	 * 지분 보고의 크기. 회사가 한 일이 아니라 주주가 한 일이어서 가장 큰 크기는 2다.
	 *
	 * @param tradeNeutralBelow 매매 순증감이 발행주식총수의 몇 %인지. 이보다 작으면 중립
	 * @param tradeMediumFrom   이 이상이면 크기 2
	 * @param holdingMediumFrom 대량보유상황보고서의 보유비율 증감(%p)의 절댓값. 이 이상이면 크기 2
	 */
	public record Ownership(BigDecimal tradeNeutralBelow, BigDecimal tradeMediumFrom, BigDecimal holdingMediumFrom) {

		public Ownership {
			require(tradeNeutralBelow != null && tradeNeutralBelow.signum() >= 0, "ownership.trade-neutral-below");
			require(tradeMediumFrom != null && tradeMediumFrom.compareTo(tradeNeutralBelow) >= 0, "ownership.trade-medium-from");
			require(holdingMediumFrom != null && holdingMediumFrom.signum() > 0, "ownership.holding-medium-from");
		}

		/** 매매 순증감 비율의 절댓값(%)으로 크기를 정한다. 중립이면 0. */
		int sizeOfTrade(BigDecimal absoluteRatio) {
			if (absoluteRatio.compareTo(tradeNeutralBelow) < 0) {
				return 0;
			}
			return absoluteRatio.compareTo(tradeMediumFrom) < 0 ? 1 : 2;
		}

		/** 보유비율 증감의 절댓값(%p)으로 크기를 정한다. 증감이 없으면 0. */
		int sizeOfHolding(BigDecimal absoluteChange) {
			if (absoluteChange.signum() == 0) {
				return 0;
			}
			return absoluteChange.compareTo(holdingMediumFrom) < 0 ? 1 : 2;
		}
	}

	private static void require(boolean ok, String name) {
		if (!ok) {
			throw new IllegalArgumentException("app.judgment.impact." + name + " 값이 없거나 올바르지 않습니다");
		}
	}
}
