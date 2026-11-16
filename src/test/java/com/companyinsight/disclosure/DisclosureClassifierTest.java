package com.companyinsight.disclosure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 보고서명은 2026-10-19 ~ 11-16에 실제로 접수된 공시 제목을 그대로 옮긴 것이다.
 * 기대값은 코드가 아니라 합의한 분류표에서 가져왔다.
 */
class DisclosureClassifierTest {

	private final DisclosureClassifier classifier = new DisclosureClassifier();

	@ParameterizedTest(name = "{0} → {1} {2}")
	@CsvSource(delimiter = '|', textBlock = """
		주식소각결정                                                        | POSITIVE | 3
		회생절차종결결정                                                    | POSITIVE | 3
		주요사항보고서(자기주식취득결정)                                    | POSITIVE | 2
		주요사항보고서(자기주식취득신탁계약체결결정)                        | POSITIVE | 2
		현금ㆍ현물배당결정                                                  | POSITIVE | 2
		현금ㆍ현물배당결정 (2026년 3분기)                                   | POSITIVE | 2
		주요사항보고서(무상증자결정)                                        | POSITIVE | 2
		파산신청기각                                                        | POSITIVE | 2
		기업가치제고계획(자율공시) (PBR 개선 계획 제출 기업)                | POSITIVE | 1
		생산재개(자율공시)                                                  | POSITIVE | 1
		횡령ㆍ배임혐의발생                                                  | NEGATIVE | 3
		파산신청                                                            | NEGATIVE | 3
		주요사항보고서(영업정지)                                            | NEGATIVE | 3
		자본잠식50%이상또는매출액50억원미만사실발생                         | NEGATIVE | 3
		반기검토의견부적정,의견거절또는완전자본잠식사실발생                 | NEGATIVE | 3
		회계처리기준위반행위로인한증권선물위원회의검찰고발등                | NEGATIVE | 3
		기타시장안내 (주가 미달에 따른 상장폐지 우려 관련 안내)             | NEGATIVE | 3
		기타시장안내 (주권 상장폐지 우려 예고)                              | NEGATIVE | 3
		투자유의안내 (주가요건 미달로 인한 상장폐지 우려 예고)              | NEGATIVE | 3
		불성실공시법인지정 (공시번복 3건)                                   | NEGATIVE | 2
		기타시장안내(관리종목지정우려종목) (주가 1,000원 미달)              | NEGATIVE | 2
		기타시장안내 (관리종목 지정사유 추가우려)(시가총액 200억원 미달)    | NEGATIVE | 2
		투자유의안내 (관리종목지정 우려 예고)                               | NEGATIVE | 2
		단일판매ㆍ공급계약해지                                              | NEGATIVE | 2
		거래처와의거래중단                                                  | NEGATIVE | 2
		생산중단                                                            | NEGATIVE | 2
		파생상품거래손실발생                                                | NEGATIVE | 2
		중대재해발생                                                        | NEGATIVE | 2
		불성실공시법인지정예고 (공시불이행 3건)                             | NEGATIVE | 1
		벌금등의부과                                                        | NEGATIVE | 1
		""")
	void 제목으로_호재와_악재를_판정한다(String reportName, Tone tone, int impact) {
		Classification c = classifier.classify(reportName, null);

		assertThat(c.tone()).isEqualTo(tone);
		assertThat(c.impact()).isEqualTo(impact);
		assertThat(c.classifiedBy()).isEqualTo(ClassifiedBy.RULE);
		assertThat(c.correction()).isFalse();
	}

	@ParameterizedTest(name = "{0} → 중립 {1}")
	@CsvSource(delimiter = '|', textBlock = """
		분기보고서 (2026.09)                              | PERIODIC
		임원ㆍ주요주주특정증권등소유상황보고서            | OWNERSHIP
		주식등의대량보유상황보고서(약식)                  | OWNERSHIP
		임원ㆍ주요주주특정증권등거래계획보고서            | OWNERSHIP
		기업설명회(IR)개최                                | IR
		기업설명회(IR)개최결과                            | IR
		결산실적공시예고(안내공시)                        | IR
		기업가치제고계획예고(안내공시)                    | IR
		증권발행실적보고서                                | ISSUANCE
		증권신고서(지분증권)                              | ISSUANCE
		전환가액의조정                                    | ISSUANCE
		주주총회소집결의 (임시주주총회)                   | GOVERNANCE
		현금ㆍ현물배당을위한주주명부폐쇄(기준일)결정      | GOVERNANCE
		중간(분기)배당을위한주주명부폐쇄(기준일)결정      | GOVERNANCE
		자기주식취득결과보고서                            | MAJOR
		특수관계인과의내부거래                            | ETC
		""")
	void 절차성_공시는_중립으로_판정한다(String reportName, DisclosureKind kind) {
		Classification c = classifier.classify(reportName, null);

		assertThat(c.kind()).isEqualTo(kind);
		assertThat(c.tone()).isEqualTo(Tone.NEUTRAL);
		assertThat(c.impact()).isNull();
		assertThat(c.classifiedBy()).isEqualTo(ClassifiedBy.RULE);
	}

	@ParameterizedTest(name = "{0} → 판정 보류 {1}")
	@CsvSource(delimiter = '|', textBlock = """
		연결재무제표기준영업(잠정)실적(공정공시)                              | EARNINGS
		영업(잠정)실적(공정공시)                                              | EARNINGS
		단일판매ㆍ공급계약체결                                                | MAJOR
		주요사항보고서(유상증자결정)                                          | MAJOR
		주요사항보고서(전환사채권발행결정)                                    | MAJOR
		주요사항보고서(감자결정)                                              | MAJOR
		주요사항보고서(자기주식취득신탁계약해지결정)                          | MAJOR
		주요사항보고서(자본으로인정되는채무증권발행결정)                      | MAJOR
		횡령ㆍ배임혐의진행사항                                                | ETC
		감사보고서제출                                                        | ETC
		기타주요경영사항 (유상증자결정(제3자배정) 철회)                       | ETC
		투자판단관련주요경영사항 (회계처리기준 위반행위로 인한 검찰 항소 판결 관련 진행사항) | MAJOR
		불성실공시법인미지정 (지정유예)                                       | MARKET
		기타시장안내 (상장적격성 실질심사 대상 결정)                          | MARKET
		기타시장안내(최대주주의의무보유관련) (최대주주의 의무보유 이행 관련)  | MARKET
		기타시장안내 (관리종목 지정 사유 일부 해제 관련 시장조치 미진행)      | MARKET
		주권매매거래정지 (중요한 영업정지)                                    | MARKET
		주권매매거래정지기간변경 (파산신청)                                   | MARKET
		주권매매거래정지해제 (파산신청 사유 해소)                             | MARKET
		조회공시요구(풍문또는보도) (회계처리기준 위반)                        | MARKET
		""")
	void 제목만으로_방향을_알_수_없으면_판정하지_않는다(String reportName, DisclosureKind kind) {
		Classification c = classifier.classify(reportName, null);

		assertThat(c.kind()).isEqualTo(kind);
		assertThat(c.tone()).isNull();
		assertThat(c.impact()).isNull();
		assertThat(c.classifiedBy()).isNull();
		assertThat(c.decided()).isFalse();
	}

	@ParameterizedTest(name = "{0} → 영향도 {2}")
	@CsvSource(delimiter = '|', textBlock = """
		주식소각결정(자회사의 주요경영사항)                                   | POSITIVE | 2
		주요사항보고서(자기주식취득결정)(자회사의 주요경영사항)               | POSITIVE | 1
		현금ㆍ현물배당결정(자회사의 주요경영사항) (분기배당)                  | POSITIVE | 1
		영업정지(종속회사의주요경영사항)                                      | NEGATIVE | 2
		중대재해발생(종속회사의주요경영사항)                                  | NEGATIVE | 1
		벌금등의부과(자회사의 주요경영사항)                                   | NEGATIVE | 1
		""")
	void 자회사의_일은_영향도를_한_단계_낮추되_1보다_낮추지_않는다(String reportName, Tone tone, int impact) {
		Classification c = classifier.classify(reportName, null);

		assertThat(c.tone()).isEqualTo(tone);
		assertThat(c.impact()).isEqualTo(impact);
	}

	@ParameterizedTest(name = "{0}")
	@CsvSource(delimiter = '|', textBlock = """
		[기재정정]주식소각결정                                    | POSITIVE | 3
		[첨부추가]주식소각결정                                    | POSITIVE | 3
		[기재정정]주요사항보고서(자기주식취득결정)                | POSITIVE | 2
		[기재정정]주식소각결정(자회사의 주요경영사항)             | POSITIVE | 2
		[첨부정정]단일판매ㆍ공급계약해지                          | NEGATIVE | 2
		[기재정정]기타시장안내 (주가 미달에 따른 상장폐지 우려 관련 안내) | NEGATIVE | 3
		[기재정정]주요사항보고서(회생절차개시신청)                | NEGATIVE | 3
		""")
	void 정정_공시는_원래_공시와_같게_분류하고_정정으로_표시한다(String reportName, Tone tone, int impact) {
		Classification c = classifier.classify(reportName, null);

		assertThat(c.tone()).isEqualTo(tone);
		assertThat(c.impact()).isEqualTo(impact);
		assertThat(c.correction()).isTrue();
	}

	@Test
	void 판정을_보류한_공시도_정정이면_정정으로_표시한다() {
		Classification c = classifier.classify("[첨부정정]주요사항보고서(전환사채권발행결정)", "정");

		assertThat(c.kind()).isEqualTo(DisclosureKind.MAJOR);
		assertThat(c.tone()).isNull();
		assertThat(c.correction()).isTrue();
	}

	@Test
	void 머리말이_두_개_이어져도_정정으로_본다() {
		Classification c = classifier.classify("[기재정정][첨부추가]분기보고서 (2026.09)", null);

		assertThat(c.kind()).isEqualTo(DisclosureKind.PERIODIC);
		assertThat(c.correction()).isTrue();
	}

	@Test
	void 제목_중간의_대괄호는_머리말이_아니다() {
		Classification c = classifier.classify("대규모기업집단현황공시[연1회공시및1/4분기용(대표회사)]", "공");

		assertThat(c.correction()).isFalse();
	}

	@Test
	void 철회된_공시는_호재여도_중립으로_돌린다() {
		Classification c = classifier.classify("주식소각결정", "유철");

		assertThat(c.withdrawn()).isTrue();
		assertThat(c.tone()).isEqualTo(Tone.NEUTRAL);
		assertThat(c.impact()).isNull();
		assertThat(c.classifiedBy()).isEqualTo(ClassifiedBy.RULE);
	}

	@Test
	void 철회된_공시는_판정을_보류하지_않는다() {
		// 2026-10-21 접수 20261021000435. 본문을 읽을 필요가 없다.
		Classification c = classifier.classify("[기재정정]증권신고서(지분증권)", "철");

		assertThat(c.withdrawn()).isTrue();
		assertThat(c.correction()).isTrue();
		assertThat(c.decided()).isTrue();
	}

	@Test
	void 비고에_정정_표시만_있으면_철회가_아니다() {
		Classification c = classifier.classify("거래처와의거래중단", "유정");

		assertThat(c.withdrawn()).isFalse();
		assertThat(c.tone()).isEqualTo(Tone.NEGATIVE);
	}

	@Test
	void 공백과_탭_줄바꿈이_섞여도_같게_분류한다() {
		Classification c = classifier.classify("  기타시장안내\t(주가  미달에 따른\n상장폐지 우려 관련 안내 )   ", null);

		assertThat(c.tone()).isEqualTo(Tone.NEGATIVE);
		assertThat(c.impact()).isEqualTo(3);
	}

	@Test
	void 가운뎃점_글자가_달라도_같게_분류한다() {
		Classification c = classifier.classify("현금·현물배당결정", null);

		assertThat(c.tone()).isEqualTo(Tone.POSITIVE);
	}

	@Test
	void 빈_제목과_null은_판정하지_않는다() {
		assertThat(classifier.classify("", null).tone()).isNull();
		assertThat(classifier.classify(null, null).tone()).isNull();
		assertThat(classifier.classify(null, null).kind()).isEqualTo(DisclosureKind.ETC);
	}
}
