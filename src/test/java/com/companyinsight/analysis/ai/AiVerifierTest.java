package com.companyinsight.analysis.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.companyinsight.analysis.body.BodyDocument;
import com.companyinsight.analysis.body.DisclosureBodyReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * AI 답의 원문 대조. 기대값은 실제 공시 원문에 적힌 값이다.
 *
 * <p>SK하이닉스 2026-10-28 연결재무제표기준영업(잠정)실적(공정공시), 접수번호 20261028800036.
 * 단위 백만원. 영업이익 당해실적 83,333,818 / 전분기 60,542,608 / 전분기대비 37.6% / 전년동기 11,383,390 / 전년동기대비 632.1%.
 * 기업설명회 2026년 10월 28일. 실적기간 2026-07-01 ~ 2026-09-30.
 */
class AiVerifierTest {

	private static final String BODY = body("20261028800036");

	@Test
	void 표의_칸을_이어_옮긴_근거는_본문에_있다() {
		assertThat(AiVerifier.evidenceFound(BODY, "83,333,818 | 60,542,608 | 37.6")).isTrue();
		// 칸 구분 없이 공백으로 옮겨도 같다
		assertThat(AiVerifier.evidenceFound(BODY, "83,333,818 60,542,608 37.6")).isTrue();
	}

	@Test
	void 표의_항목과_값을_콜론으로_이어_옮긴_근거도_본문에_있다() {
		// SK하이닉스 2026-06-24 주요사항보고서(유상증자결정), 접수번호 20260624000420. 표에 "증자방식 | 제3자배정증자"
		String body = body("20260624000420");
		assertThat(AiVerifier.evidenceFound(body, "증자방식: 제3자배정증자")).isTrue();
		assertThat(AiVerifier.evidenceFound(body, "증자방식: 주주배정증자")).isFalse();
	}

	@Test
	void 고쳐_쓰거나_건너뛴_근거는_없는_것으로_본다() {
		assertThat(AiVerifier.evidenceFound(BODY, "영업이익이 크게 늘어 83조원을 넘었다")).isFalse();
		assertThat(AiVerifier.evidenceFound(BODY, "83,333,818 ... 632.1")).isFalse();
		assertThat(AiVerifier.evidenceFound(BODY, "37.6")).isFalse(); // 너무 짧다
		assertThat(AiVerifier.evidenceFound(BODY, null)).isFalse();
	}

	@Test
	void 백만원_단위의_금액을_조억으로_바꾸고_반올림한_것은_맞다() {
		// 83,333,818백만원 = 83조 3,338억 1,800만원
		assertThat(AiVerifier.unmatchedFigures(BODY, List.of("3분기 영업이익은 83조 3,338억 원이다."))).isEmpty();
		assertThat(AiVerifier.unmatchedFigures(BODY, List.of("영업이익 83.3조 원"))).isEmpty();
	}

	@Test
	void 조원_단위_표의_값을_조로_옮긴_것도_맞다() {
		// 삼성전자 2026-01-29 [기재정정]연결재무제표기준영업(잠정)실적, 접수번호 20260129800008. 단위 조원, 영업이익 20.07 / 전년동기 6.49
		String body = body("20260129800008");
		assertThat(AiVerifier.unmatchedFigures(body, List.of("영업이익은 20.07조 원이다.", "전년 동기 영업이익은 6.49조 원이다."))).isEmpty();
		// 조원 표의 값을 억 단위로 잘못 옮기면 틀린다
		assertThat(AiVerifier.unmatchedFigures(body, List.of("영업이익은 20억 원이다."))).containsExactly("20억");
	}

	@Test
	void 단위_뒤에_조사가_붙어도_금액을_본다() {
		assertThat(AiVerifier.unmatchedFigures(BODY, List.of("영업이익이 83조 3,338억에 달한다."))).isEmpty();
		assertThat(AiVerifier.unmatchedFigures(BODY, List.of("영업이익이 83조 4,000억을 넘었다."))).containsExactly("83조 4,000억");
	}

	@Test
	void 천억_천만_단위도_본다() {
		assertThat(AiVerifier.unmatchedFigures("계약금액 359,812,345,678원", List.of("9천억 원 계약", "5천만 원")))
			.containsExactly("9천억", "5천만");
		// 본문 190,000,000원 → "1억 9천만"은 맞다
		assertThat(AiVerifier.unmatchedFigures("계약금액 190,000,000원", List.of("1억 9천만 원"))).isEmpty();
		// 199,000,000원을 "1억 9천만"으로 버린 것은 유효숫자가 두 자리라 받는다
		assertThat(AiVerifier.unmatchedFigures("계약금액 199,000,000원", List.of("1억 9천만 원"))).isEmpty();
	}

	@Test
	void 단위_금액_끝에_이어_쓴_나머지도_금액에_넣는다() {
		// 진시스템 자기주식 처분 공시의 처분예정금액 875,921,100원을 AI가 "8억 7,592만 1,100원"으로 옮긴 실제 사례
		String body = "처분예정금액(원) | 875,921,100";
		assertThat(AiVerifier.unmatchedFigures(body, List.of("8억 7,592만 1,100원 규모"))).isEmpty();
		assertThat(AiVerifier.unmatchedFigures(body, List.of("8억 7,592만 1,200원 규모"))).containsExactly("8억 7,592만 1,200");
	}

	@Test
	void 표의_단위_배수는_연도처럼_작은_숫자에_곱하지_않는다() {
		String body = "(단위: 억원) 2026년 계약금액 150";
		assertThat(AiVerifier.unmatchedFigures(body, List.of("계약금액 150억 원"))).isEmpty();
		assertThat(AiVerifier.unmatchedFigures(body, List.of("계약금액 2,026억 원"))).containsExactly("2,026억");
	}

	@Test
	void 버림은_유효숫자가_두_자리_이상일_때만_받는다() {
		String body = "계약금액 199,000,000원";
		assertThat(AiVerifier.unmatchedFigures(body, List.of("1억 원"))).containsExactly("1억");
		assertThat(AiVerifier.unmatchedFigures(body, List.of("2억 원", "1.99억 원"))).isEmpty();
		// 83,333,818백만원 → 83조 3,338억 (반올림과 버림이 같다)
		assertThat(AiVerifier.unmatchedFigures(BODY, List.of("83조 3,338억 원"))).isEmpty();
	}

	@Test
	void 비율은_날짜의_일과_견주지_않는다() {
		String body = "이사회 결의일 2026년 10월 15일. 할인율 10.5";
		assertThat(AiVerifier.unmatchedFigures(body, List.of("할인율은 10.5%다."))).isEmpty();
		assertThat(AiVerifier.unmatchedFigures(body, List.of("지분 15%를 취득한다."))).containsExactly("15%");
	}

	@Test
	void 숫자_뒤에_쉼표가_와도_본다() {
		assertThat(AiVerifier.unmatchedFigures("매장 300개", List.of("매장 300, 400개"))).containsExactly("400");
		assertThat(AiVerifier.unmatchedFigures("매장 400개", List.of("매장 300, 400개"))).containsExactly("300");
	}

	@Test
	void 어림하거나_틀린_금액은_본문에_없다() {
		assertThat(AiVerifier.unmatchedFigures(BODY, List.of("영업이익은 83조 4,000억 원이다."))).containsExactly("83조 4,000억");
		assertThat(AiVerifier.unmatchedFigures(BODY, List.of("영업이익 84조 원"))).containsExactly("84조");
	}

	@Test
	void 비율은_본문의_값이나_그_반올림만_맞다() {
		assertThat(AiVerifier.unmatchedFigures(BODY, List.of("전년 동기보다 632.1% 늘었다.", "전분기보다 38% 늘었다."))).isEmpty();
		// AI가 계산했거나 어림한 비율
		assertThat(AiVerifier.unmatchedFigures(BODY, List.of("전년 동기보다 600% 늘었다."))).containsExactly("600%");
	}

	@Test
	void 날짜는_본문의_날짜와_같아야_한다() {
		assertThat(AiVerifier.unmatchedFigures(BODY, List.of("2026년 10월 28일 기업설명회를 연다.", "실적기간은 2026.09.30까지다.",
				"10월 28일 발표"))).isEmpty();
		assertThat(AiVerifier.unmatchedFigures(BODY, List.of("2026년 11월 5일 기업설명회를 연다."))).containsExactly("2026년 11월 5일");
		assertThat(AiVerifier.unmatchedFigures(BODY, List.of("11월 5일 발표"))).containsExactly("11월 5일");
	}

	@Test
	void 본문이_두_자리로_줄여_쓴_연도를_풀어_써도_맞다() {
		// 롯데손해보험 파생상품거래손실발생 공시의 표기: "'17년 12월 ~ '18년 1월 체결"
		String body = "금리파생상품('17년 12월 ~ '18년 1월 체결)에서 손실 발생";
		assertThat(AiVerifier.unmatchedFigures(body, List.of("2017년 12월부터 2018년 1월 사이에 체결"))).isEmpty();
		assertThat(AiVerifier.unmatchedFigures(body, List.of("2019년 12월에 체결"))).containsExactly("2019년 12월");
	}

	@Test
	void 작은_정수와_본문에_있는_숫자는_본다() {
		assertThat(AiVerifier.unmatchedFigures(BODY, List.of("3분기 실적이다.", "전년동기 영업이익은 11,383,390백만원이다."))).isEmpty();
		assertThat(AiVerifier.unmatchedFigures(BODY, List.of("영업이익 12,345,678백만원"))).containsExactly("12,345,678");
	}

	@Test
	void 요약의_모양을_본다() {
		assertThat(AiVerifier.summaryProblems(BODY, "3분기 영업이익 83조 3,338억 원", List.of("전년 동기보다 632.1% 늘었다."))).isEmpty();
		assertThat(AiVerifier.summaryProblems(BODY, "3분기 영업이익 83조 3,338억 원", List.of())).containsExactly("요약이 비어 있음");
		assertThat(AiVerifier.summaryProblems(BODY, "", List.of("전년 동기보다 632.1% 늘었다."))).containsExactly("제목이 비어 있음");
		assertThat(AiVerifier.summaryProblems(BODY, "영업이익 급증", List.of("영업이익 급증"))).containsExactly("요약 문장이 제목과 같음");
		assertThat(AiVerifier.summaryProblems(BODY, "영업이익 급증", List.of("실적 호재다."))).containsExactly("판단을 적은 말(호재·악재)이 있음");
		assertThat(AiVerifier.summaryProblems(BODY, "영업이익 급증", List.of("가", "나", "다", "라", "마", "바")))
			.containsExactly("요약이 6문장 (상한 5)");
	}

	private static String body(String receiptNo) {
		try (InputStream in = AiVerifierTest.class.getResourceAsStream("/dart/body/" + receiptNo + ".zip")) {
			List<BodyDocument> documents = DisclosureBodyReader.documents(in.readAllBytes());
			return AiInputs.body(documents);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
