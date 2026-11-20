package com.companyinsight.disclosure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.companyinsight.TestcontainersConfiguration;
import com.companyinsight.common.dart.DartClient;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 마이그레이션으로 들어간 대응표를 그대로 읽어 분류한다. 규칙이 코드가 아니라 DB에 있으므로 DB 없이 시험하지 않는다.
 *
 * <p>기대값은 코드에서 뽑지 않았다. 유형은 상장사 공시 1년치의 제목 2,229가지에 사람이 확정한 대응표
 * ({@code disclosure/title-type-expected.tsv})이고, 방향은 유형별로 확정한 판정 기준(docs/PLAN.md 6절)에서 옮겼다.
 * 표의 보고서명은 실제로 접수된 공시 제목이다. 실제 데이터에 없어 지어낸 입력은 "가정한 제목"이라고 적었다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DisclosureClassifierTest {

	@Autowired
	DisclosureClassifier classifier;

	@Autowired
	JdbcClient jdbc;

	@MockitoBean
	DartClient dart;

	@Test
	void 일년치_제목_2229가지가_확정된_대응표와_같은_유형을_받는다() throws IOException {
		List<String> wrong = new ArrayList<>();
		int count = 0;
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(
				getClass().getResourceAsStream("/disclosure/title-type-expected.tsv"), StandardCharsets.UTF_8))) {
			for (String line = reader.readLine(); line != null; line = reader.readLine()) {
				String[] cols = line.split("\t");
				String actual = classifier.classify(cols[0], null).typeCode();
				if (!actual.equals(cols[1])) {
					wrong.add(cols[0] + " → " + actual + " (기대 " + cols[1] + ")");
				}
				count++;
			}
		}

		assertThat(count).isEqualTo(2229);
		assertThat(wrong).isEmpty();
	}

	@ParameterizedTest(name = "{0} → {1} {2}")
	@CsvSource(delimiter = '|', textBlock = """
		단일판매ㆍ공급계약체결                                              | C1 | POSITIVE
		주요사항보고서(무상증자결정)                                        | D2 | POSITIVE
		주식소각결정                                                        | E1 | POSITIVE
		주요사항보고서(자기주식취득결정)                                    | E2 | POSITIVE
		주요사항보고서(자기주식취득신탁계약체결결정)                        | E2 | POSITIVE
		현금ㆍ현물배당결정                                                  | E6 | POSITIVE
		기업가치제고계획(자율공시) (PBR 개선 계획 제출 기업)                | E7 | POSITIVE
		공개매수신고서                                                      | F7 | POSITIVE
		신규시설투자등                                                      | F8 | POSITIVE
		파산신청기각                                                        | I3 | POSITIVE
		회생계획인가                                                        | I3 | POSITIVE
		회생절차종결결정                                                    | I3 | POSITIVE
		생산재개(자율공시)                                                  | I4 | POSITIVE
		주권매매거래정지해제(상장유지결정)                                  | J4 | POSITIVE
		단일판매ㆍ공급계약해지                                              | C2 | NEGATIVE
		주요사항보고서(전환사채권발행결정)                                  | D3 | NEGATIVE
		전환청구권행사                                                      | L2 | NEGATIVE
		주요사항보고서(자기전환사채매도결정)                                | L4 | NEGATIVE
		최대주주변경을수반하는주식담보제공계약체결                          | G3 | NEGATIVE
		소송등의제기ㆍ신청(경영권분쟁소송)                                  | G5 | NEGATIVE
		소송등의제기ㆍ신청(일정금액이상의청구)                              | I1 | NEGATIVE
		횡령ㆍ배임혐의발생                                                  | I2 | NEGATIVE
		벌금등의부과                                                        | I2 | NEGATIVE
		회계처리기준위반행위로인한증권선물위원회의검찰고발등                | I2 | NEGATIVE
		파산신청                                                            | I3 | NEGATIVE
		주요사항보고서(회생절차개시신청)                                    | I3 | NEGATIVE
		대출원리금연체사실발생                                              | I3 | NEGATIVE
		주요사항보고서(영업정지)                                            | I4 | NEGATIVE
		생산중단                                                            | I4 | NEGATIVE
		거래처와의거래중단                                                  | I4 | NEGATIVE
		중대재해발생                                                        | I4 | NEGATIVE
		자본잠식50%이상또는매출액50억원미만사실발생                         | I5 | NEGATIVE
		반기검토의견부적정,의견거절또는완전자본잠식사실발생                 | I5 | NEGATIVE
		불성실공시법인지정예고 (공시불이행 3건)                             | J2 | NEGATIVE
		불성실공시법인지정 (공시번복 3건)                                   | J2 | NEGATIVE
		주권매매거래정지(상장폐지사유발생)                                  | J4 | NEGATIVE
		주권매매거래정지해제(상장폐지에따른정리매매개시)                    | J4 | NEGATIVE
		조회공시요구(풍문또는보도)(감사의견비적정설)                        | J7 | NEGATIVE
		""")
	void 제목으로_호재와_악재를_판정한다(String reportName, String typeCode, Tone tone) {
		Classification c = classifier.classify(reportName, null);

		assertThat(c.typeCode()).isEqualTo(typeCode);
		assertThat(c.tone()).isEqualTo(tone);
		assertThat(c.classifiedBy()).isEqualTo(ClassifiedBy.RULE);
		assertThat(c.correction()).isFalse();
	}

	@ParameterizedTest(name = "{0} → {1} 중립")
	@CsvSource(delimiter = '|', textBlock = """
		분기보고서 (2026.09)                                                | A1
		사업보고서 (2025.12)                                                | A1
		임원ㆍ주요주주특정증권등거래계획보고서                              | A3
		증권신고서(지분증권)                                                | A4
		주주총회소집공고                                                    | A5
		기업설명회(IR)개최(안내공시)                                        | A6
		기업가치제고계획예고(안내공시)                                      | A6
		영업실적등에대한전망(공정공시)                                      | B2
		감사보고서                                                          | B5
		자산재평가실시결정                                                  | B6
		증권발행결과(자율공시)                                              | D5
		자기주식취득결과보고서                                              | E5
		감자완료                                                            | E8
		주식분할결정                                                        | E9
		공개매수설명서                                                      | F7
		대표이사변경                                                        | G4
		최대주주변경을수반하는주식담보제공계약해제ㆍ취소등                  | G3
		주요사항보고서(전환사채매수선택권행사자지정)                        | L4
		특수관계인과의내부거래                                              | H4
		불성실공시법인미지정(지정유예)                                      | J2
		주권매매거래정지(주식분할)                                          | J3
		주권매매거래정지기간변경(상장폐지사유발생)                          | J4
		매매거래정지및정지해제(중요내용공시)                                | J5
		조회공시요구(현저한시황변동)에대한답변(미확정)                      | J7
		풍문또는보도에대한해명(미확정)                                      | J7
		소속부변경                                                          | J8
		기타시장안내(단기과열종목지정해제)                                  | J8
		""")
	void 제목으로_중립이_정해지는_공시(String reportName, String typeCode) {
		Classification c = classifier.classify(reportName, null);

		assertThat(c.typeCode()).isEqualTo(typeCode);
		assertThat(c.tone()).isEqualTo(Tone.NEUTRAL);
		assertThat(c.classifiedBy()).isEqualTo(ClassifiedBy.RULE);
	}

	@ParameterizedTest(name = "{0} → {1} 판정 미룸")
	@CsvSource(delimiter = '|', textBlock = """
		임원ㆍ주요주주특정증권등소유상황보고서                              | A2
		주식등의대량보유상황보고서(일반)                                    | A2
		연결재무제표기준영업(잠정)실적(공정공시)                            | B1
		매출액또는손익구조30%(대규모법인은15%)이상변동                      | B4
		감사보고서제출                                                      | B5
		자산재평가결과                                                      | B6
		주요사항보고서(유상증자결정)                                        | D1
		전환가액의조정                                                      | L1
		타인에대한채무보증결정                                              | H1
		금전대여결정                                                        | H2
		단기차입금증가결정                                                  | H3
		주요사항보고서(자기주식처분결정)                                    | E4
		주요사항보고서(감자결정)                                            | E8
		주요사항보고서(회사합병결정)                                        | F1
		타법인주식및출자증권취득결정                                        | F4
		최대주주변경                                                        | G1
		소송등의판결ㆍ결정                                                  | G5
		소송등의판결ㆍ결정(일정금액이상의청구)                              | I1
		주요사항보고서(소송등의제기)                                        | I1
		횡령ㆍ배임혐의진행사항                                              | I2
		해산사유발생(자회사의 주요경영사항)                                 | I3
		회생절차폐지결정                                                    | I3
		대출원리금연체사실발생(대출원리금연체사유해소)                      | I3
		파생상품거래손실발생                                                | I4
		풍문또는보도에대한해명                                              | J7
		기타시장안내(상장폐지관련)                                          | J1
		기타시장안내(기업심사위원회개최결과안내)                            | J1
		투자유의안내(처음보는꼬리)                                          | J1
		투자판단관련주요경영사항(임상시험결과)                              | K1
		기타시장안내                                                        | K1
		사채권자집회소집                                                    | Z9
		""")
	void 본문을_읽어야_하는_공시는_유형만_붙이고_판정을_비운다(String reportName, String typeCode) {
		Classification c = classifier.classify(reportName, null);

		assertThat(c.typeCode()).isEqualTo(typeCode);
		assertThat(c.tone()).isNull();
		assertThat(c.classifiedBy()).isNull();
		assertThat(c.decided()).isFalse();
	}

	/** 관리종목·상장폐지 관련 안내는 꼬리가 방향과 경우를 정한다. 마지막 줄만 가정한 제목이 아니라 모두 실제 제목이다. */
	@ParameterizedTest(name = "{0} → {1} {2}")
	@CsvSource(delimiter = '|', textBlock = """
		기타시장안내(코스닥시장위원회개최결과및상장폐지결정안내)            | NEGATIVE | DELISTING
		기타시장안내(상장적격성실질심사대상결정)                            | NEGATIVE | DELISTING
		기타시장안내 (상장적격성 실질심사 사유발생)                         | NEGATIVE | DELISTING
		투자유의안내(상장폐지기준해당)                                      | NEGATIVE | DELISTING
		지정자문인선임계약의해지                                            | NEGATIVE | DELISTING
		투자유의안내                                                        | NEGATIVE | WARNING
		투자유의안내 (관리종목지정 우려 예고)                               | NEGATIVE | WARNING
		기타시장안내 (주권 상장폐지 우려 예고)                              | NEGATIVE | WARNING
		기타시장안내(관리종목지정우려종목)(시가총액200억원미달)             | NEGATIVE | REPEATED_WARNING
		기타시장안내(주가미달에따른상장폐지우려관련안내)                    | NEGATIVE | REPEATED_WARNING
		기타시장안내 (관리종목 지정사유 추가우려)                           | NEGATIVE | REPEATED_WARNING
		기타시장안내(상장적격성실질심사대상제외결정)                        | POSITIVE | RELIEF
		기타시장안내(기업심사위원회심의·의결결과및상장유지결정안내)         | POSITIVE | RELIEF
		기타시장안내(개선계획서제출)                                        | NEUTRAL  | PROCEDURE
		기타시장안내(상장폐지관련이의신청서접수)                            | NEUTRAL  | PROCEDURE
		기타시장안내(상장적격성실질심사대상결정기한안내)                    | NEUTRAL  | PROCEDURE
		기타시장안내(개선기간부여)                                          | NEUTRAL  | PROCEDURE
		기타시장안내(정리매매보류관련)                                      | NEUTRAL  | PROCEDURE
		상장적격성실질심사관련주요개선계획(자율공시)                        | NEUTRAL  | PROCEDURE
		""")
	void 관리종목과_상장폐지_안내는_꼬리로_방향과_경우를_정한다(String reportName, Tone tone, String caseCode) {
		Classification c = classifier.classify(reportName, null);

		assertThat(c.typeCode()).isEqualTo("J1");
		assertThat(c.tone()).isEqualTo(tone);
		assertThat(c.caseCode()).isEqualTo(caseCode);
	}

	/** 대응표 버전 2에서 고친 줄. 제목은 모두 실제 공시의 제목이다. 방향이 비어 있으면 본문을 읽는 줄이다. */
	@ParameterizedTest(name = "{0} → {1} {2}")
	@CsvSource(delimiter = '|', textBlock = """
		기업가치제고계획(자율공시) (2025년 이행현황)                                   | E7 | NEUTRAL
		기업가치제고계획(자율공시) (2026년 3분기 이행현황)                             | E7 | NEUTRAL
		기업가치제고계획(자율공시) (이행현황)                                          | E7 | NEUTRAL
		기업가치제고계획(자율공시)((2025년이행현황))                                   | E7 | NEUTRAL
		기업가치제고계획(자율공시)(효성티앤씨㈜기업가치제고계획이행현황(2025년))       | E7 | NEUTRAL
		기업가치제고계획(자율공시)(이행현황재공시(고배당기업에해당))                   | E7 | NEUTRAL
		기업가치제고계획(자율공시)(고배당기업표시를위한재공시)                         | E7 | NEUTRAL
		기업가치제고계획(자율공시)                                                     | E7 | POSITIVE
		기업가치제고계획(자율공시)(2026년)                                             | E7 | POSITIVE
		기업가치제고계획(자율공시)((2025년이행현황및2026년기업가치제고계획))           | E7 | POSITIVE
		교환청구권행사                                                                 | L2 | NEUTRAL
		교환청구권행사(제1회차)                                                        | L2 | NEUTRAL
		전환청구권ㆍ신주인수권ㆍ교환청구권행사                                         | L2 | NEGATIVE
		종류주식의보통주식으로전환(안내공시)                                           | L2 | NEGATIVE
		전환주식의전환청구권행사(상환전환우선주)                                       | L2 | NEGATIVE
		주식소각결정(상환전환우선주)                                                   | E1 | NEUTRAL
		주식소각결정                                                                   | E1 | POSITIVE
		조회공시요구(외부감사의견)                                                     | J7 | NEGATIVE
		소송등의제기ㆍ신청(경영권분쟁소송)(주주총회결의취소의소(항소))                 | G5 |
		소송등의제기ㆍ신청(일정금액이상의청구)(해고무효확인(항소))                     | I1 |
		주권매매거래정지해제(회생절차개시결정)                                         | J4 |
		효력발생안내( 2026.10.23. 제출 증권신고서(지분증권) )                          | A4 | NEUTRAL
		정정신고서제출요구( 2026.10.28. 제출 증권신고서(합병) )                        | A4 | NEGATIVE
		정정명령부과( 2026.10.30. 제출 주요사항보고서(회사합병 결정) )                 | A4 | NEGATIVE
		""")
	void 꼬리에_따라_방향을_고친_줄과_금융감독원_조치_서식(String reportName, String typeCode, Tone tone) {
		Classification c = classifier.classify(reportName, null);

		assertThat(c.typeCode()).isEqualTo(typeCode);
		assertThat(c.tone()).isEqualTo(tone);
		assertThat(c.classifiedBy()).isEqualTo(tone == null ? null : ClassifiedBy.RULE);
	}

	/**
	 * 꼬리에 든 낱말로 거는 줄(FORM_WORD). "실제"는 실제 공시의 제목이고 "가정"은 아직 나오지 않은 꼬리를 가정한 제목이다.
	 * 방향이 비어 있으면 본문을 읽는 줄이다. 마지막 칸은 걸려야 하는 줄의 대응 방식이다.
	 * 이행현황·재공시·항소의 실제 제목은 대부분 버전 2에서 제목 전체 줄로 적어 그 줄에 먼저 걸린다. 항고의 실제 제목 일곱은 낱말 줄에 걸린다.
	 */
	@ParameterizedTest(name = "{0} → {1} {2} ({3}, {4})")
	@CsvSource(delimiter = '|', textBlock = """
		기업가치제고계획(자율공시)(2025년재공시)                                       | E7 | NEUTRAL  | 실제 | FORM_WORD
		기업가치제고계획(자율공시)(고배당기업)                                         | E7 | NEUTRAL  | 실제 | EXACT
		기업가치제고계획(자율공시)(2025금호석유화학기업가치제고계획이행현황)           | E7 | NEUTRAL  | 실제 | EXACT
		기업가치제고계획(자율공시) (2026년 4분기 이행현황)                             | E7 | NEUTRAL  | 가정 | FORM_WORD
		기업가치제고계획(자율공시)(2027년이행현황)                                     | E7 | NEUTRAL  | 가정 | FORM_WORD
		[기재정정]기업가치제고계획(자율공시)((주)가나다 2027년 이행 현황)              | E7 | NEUTRAL  | 가정 | FORM_WORD
		기업가치제고계획(자율공시)((2025년이행현황및2026년기업가치제고계획))           | E7 | POSITIVE | 실제 | EXACT
		기업가치제고계획(자율공시)(2027년(주)가나다기업가치제고계획)                   | E7 | POSITIVE | 가정 | FORM
		기업가치제고계획예고(안내공시)                                                 | A6 | NEUTRAL  | 실제 | FORM
		소송등의제기ㆍ신청(경영권분쟁소송)(주주총회결의무효확인등의소(항소))           | G5 |          | 실제 | EXACT
		소송등의제기ㆍ신청(경영권분쟁소송)(이사해임의소(항소))                         | G5 |          | 가정 | FORM_WORD
		소송등의제기ㆍ신청(경영권분쟁소송)(이사해임의소(상고))                         | G5 |          | 가정 | FORM_WORD
		소송등의제기ㆍ신청(일정금액이상의청구)(손해배상청구(상고))                     | I1 |          | 가정 | FORM_WORD
		소송등의제기ㆍ신청(자율공시:일정금액미만의청구)(물품대금청구(항소))            | I1 |          | 가정 | FORM_WORD
		소송등의제기ㆍ신청(일정금액이상의청구)(손해배상청구(항소취하))                 | I1 |          | 가정 | FORM_WORD
		소송등의제기ㆍ신청(경영권분쟁소송)(임시주주총회소집허가신청(항고))             | G5 |          | 실제 | FORM_WORD
		소송등의제기ㆍ신청(경영권분쟁소송)(주주총회소집허가(재항고))                   | G5 |          | 실제 | FORM_WORD
		소송등의제기ㆍ신청(경영권분쟁소송)(임시주주총회개최금지가처분-즉시항고)        | G5 |          | 실제 | FORM_WORD
		소송등의제기ㆍ신청(경영권분쟁소송)(임시주주총회소집허가-항고)                  | G5 |          | 실제 | FORM_WORD
		소송등의제기ㆍ신청(경영권분쟁소송)(장부등열람허용가처분(항고))                 | G5 |          | 실제 | FORM_WORD
		소송등의제기ㆍ신청(경영권분쟁소송)(주주총회개최금지가처분(항고))               | G5 |          | 실제 | FORM_WORD
		소송등의제기ㆍ신청(경영권분쟁소송)(직무집행정지등가처분신청서-즉시항고)        | G5 |          | 실제 | FORM_WORD
		소송등의제기ㆍ신청(일정금액이상의청구)(채권가압류결정에대한즉시항고)           | I1 |          | 가정 | FORM_WORD
		소송등의제기ㆍ신청(경영권분쟁소송)(주주명부열람및등사가처분신청)               | G5 | NEGATIVE | 가정 | FORM
		소송등의제기ㆍ신청(일정금액이상의청구)                                         | I1 | NEGATIVE | 실제 | FORM
		소송등의제기ㆍ신청(경영권분쟁소송)(주주총회결의취소의소)                       | G5 | NEGATIVE | 가정 | FORM
		""")
	void 꼬리에_든_낱말로_방향을_가른다(String reportName, String typeCode, Tone tone, String kind, String matchKind) {
		Classification c = classifier.classify(reportName, null);

		assertThat(c.typeCode()).isEqualTo(typeCode);
		assertThat(c.tone()).isEqualTo(tone);
		assertThat(jdbc.sql("SELECT match_kind FROM title_rule WHERE id = ?").param(c.titleRuleId()).query(String.class).single())
			.isEqualTo(matchKind);
	}

	@Test
	void 낱말_줄은_서식_이름_줄보다_먼저이고_제목_전체_줄보다는_뒤다() {
		TitleRuleBook.Type z9 = new TitleRuleBook.Type("Z9", JudgeMethod.AI, null);
		TitleRuleBook.Type e7 = new TitleRuleBook.Type("E7", JudgeMethod.TITLE, Tone.POSITIVE);
		TitleRuleBook book = new TitleRuleBook(1, List.of(z9, e7), List.of(
			new TitleRuleBook.Rule(1, TitleRuleBook.MatchKind.FORM, "계획", "E7", null, null, null, null),
			new TitleRuleBook.Rule(2, TitleRuleBook.MatchKind.FORM_WORD, "계획", "E7", Tone.NEUTRAL, null, null, "현황"),
			new TitleRuleBook.Rule(3, TitleRuleBook.MatchKind.EXACT, "계획(현황과새계획)", "E7", null, null, null, null)),
			List.of());

		assertThat(book.find("계획(올해현황)")).map(TitleRuleBook.Rule::id).contains(2L);
		assertThat(book.find("계획(현황과새계획)")).map(TitleRuleBook.Rule::id).contains(3L);
		assertThat(book.find("계획(올해)")).map(TitleRuleBook.Rule::id).contains(1L);
		// 낱말은 서식 이름 뒤의 꼬리에서만 찾는다. 꼬리가 없으면 서식 이름 줄이다
		assertThat(book.find("계획")).map(TitleRuleBook.Rule::id).contains(1L);
		assertThat(book.find("현황(계획)")).isEmpty();
	}

	@Test
	void 꼬리가_유형을_정하는_서식에_처음_보는_꼬리가_붙으면_꼬리_미상이다() {
		// 가정한 제목: 대응표에 없는 꼬리
		assertThat(classifier.classify("주권매매거래정지(처음보는사유)", null).typeCode()).isEqualTo("K2");
		assertThat(classifier.classify("주권매매거래정지해제(처음보는사유)", null).typeCode()).isEqualTo("K2");
		assertThat(classifier.classify("주권매매거래정지기간변경(처음보는사유)", null).typeCode()).isEqualTo("K2");
		Classification c = classifier.classify("기타시장안내(처음보는안내)", null);
		assertThat(c.typeCode()).isEqualTo("K2");
		assertThat(c.titleRuleId()).isNotNull();
		assertThat(c.tone()).isNull();
	}

	@Test
	void 제목_전체_줄이_서식_줄보다_먼저이고_서식_줄은_가장_긴_것이_이긴다() {
		// 서식 "소송등의판결ㆍ결정"은 G5, 더 긴 서식 "소송등의판결ㆍ결정(일정금액이상의청구)"는 I1
		assertThat(classifier.classify("소송등의판결ㆍ결정(직무집행정지등가처분신청서-즉시항고)", null).typeCode()).isEqualTo("G5");
		assertThat(classifier.classify("소송등의판결ㆍ결정(일정금액이상의청구)(자회사의 주요경영사항)", null).typeCode()).isEqualTo("I1");
		// 제목 전체 줄
		assertThat(classifier.classify("소송등의판결ㆍ결정(사채금지급청구)", null).typeCode()).isEqualTo("I1");
		// 괄호가 든 서식 이름 뒤에 꼬리가 더 붙은 것. 가정한 제목
		assertThat(classifier.classify("주요사항보고서(유상증자결정)(제3자배정)", null).typeCode()).isEqualTo("D1");
		// 서식 이름으로 시작하지만 바로 뒤가 괄호가 아니면 다른 서식이다
		assertThat(classifier.classify("파산신청기각", null).tone()).isEqualTo(Tone.POSITIVE);
		assertThat(classifier.classify("파산신청", null).tone()).isEqualTo(Tone.NEGATIVE);
		// 가정한 제목: 서식 이름 뒤에 괄호 없이 글자가 붙으면 걸리지 않는다
		assertThat(classifier.classify("파산신청취하", null).typeCode()).isEqualTo("Z9");
	}

	@Test
	void 머리말과_공백과_기간_표기와_속성_꼬리를_떼고_찾는다() {
		Classification c = classifier.classify("[기재정정] 단일판매ㆍ공급계약체결 (자회사의 주요경영사항)", null);
		assertThat(c.typeCode()).isEqualTo("C1");
		assertThat(c.correction()).isTrue();
		assertThat(c.normalizedTitle()).isEqualTo("단일판매ㆍ공급계약체결");
		assertThat(c.attributes()).containsExactly("SUBSIDIARY");

		Classification two = classifier.classify("해산사유발생(자율공시)(종속회사의주요경영사항)", null);
		assertThat(two.typeCode()).isEqualTo("I3");
		assertThat(two.attributes()).containsExactlyInAnyOrder("SUBSIDIARY", "VOLUNTARY");

		// 머리말이 둘, 줄바꿈과 탭, 다른 가운뎃점. 가정한 제목
		Classification messy = classifier.classify("[첨부추가][기재정정]단일판매\t·\n공급계약체결", null);
		assertThat(messy.typeCode()).isEqualTo("C1");
		assertThat(messy.correction()).isTrue();

		Classification periodic = classifier.classify("분기보고서 (2026.09)", null);
		assertThat(periodic.normalizedTitle()).isEqualTo("분기보고서");
		assertThat(periodic.correction()).isFalse();
		assertThat(periodic.attributes()).isEmpty();
	}

	@Test
	void 꼬리에_철회나_취하가_든_공시는_서식의_방향을_붙이지_않는다() {
		// 실제 제목. 시설 투자(호재) 결정을 거둔다는 공시다
		Classification c = classifier.classify("신규시설투자등(철회)", null);
		assertThat(c.typeCode()).isEqualTo("F8");
		assertThat(c.tone()).isNull();
		assertThat(c.attributes()).containsExactly("WITHDRAWAL");

		// 실제 제목. 경영권 분쟁 소송의 제기(악재) 서식이지만 신청을 거뒀다는 공시다
		Classification dropped = classifier.classify("소송등의제기ㆍ신청(경영권분쟁소송)(주주명부열람및등사가처분신청취하)", null);
		assertThat(dropped.typeCode()).isEqualTo("G5");
		assertThat(dropped.tone()).isNull();
		assertThat(dropped.attributes()).containsExactly("WITHDRAWAL");

		// 중립인 서식은 그대로 중립이다
		Classification neutral = classifier.classify("주식병합결정(철회)", null);
		assertThat(neutral.typeCode()).isEqualTo("E9");
		assertThat(neutral.tone()).isEqualTo(Tone.NEUTRAL);
	}

	@Test
	void 비고에_철회_표시가_있으면_중립이다() {
		Classification c = classifier.classify("주식소각결정", "유철");
		assertThat(c.typeCode()).isEqualTo("E1");
		assertThat(c.withdrawn()).isTrue();
		assertThat(c.tone()).isEqualTo(Tone.NEUTRAL);
		assertThat(c.classifiedBy()).isEqualTo(ClassifiedBy.RULE);

		assertThat(classifier.classify("주식소각결정", "유").withdrawn()).isFalse();
	}

	@Test
	void 빈_제목과_대응표에_없는_제목은_미분류다() {
		for (String name : new String[] {null, "", "   ", "[기재정정]", "세상에없는공시(가정한제목)"}) {
			Classification c = classifier.classify(name, null);
			assertThat(c.typeCode()).isEqualTo("Z9");
			assertThat(c.titleRuleId()).isNull();
			assertThat(c.tone()).isNull();
		}
	}

	@Test
	void 대응표의_줄_수와_버전이_마이그레이션과_같다() {
		assertThat(classifier.version()).isEqualTo(4);
		assertThat(jdbc.sql("SELECT count(*) FROM disclosure_type").query(Integer.class).single()).isEqualTo(75);
		assertThat(jdbc.sql("SELECT count(*) FROM title_rule WHERE match_kind = 'EXACT'").query(Integer.class).single())
			.isEqualTo(284);
		assertThat(jdbc.sql("SELECT count(*) FROM title_rule WHERE match_kind = 'FORM'").query(Integer.class).single())
			.isEqualTo(332);
		assertThat(jdbc.sql("SELECT count(*) FROM title_rule WHERE match_kind = 'FORM_WORD'").query(Integer.class).single())
			.isEqualTo(11);
		assertThat(jdbc.sql("SELECT count(*) FROM title_attribute").query(Integer.class).single()).isEqualTo(6);
		// 관리종목·상장폐지 관련 안내는 줄마다 방향이 있거나 본문을 읽는 줄이다. 방향 없이 제목 판정으로 남은 줄이 없다
		assertThat(jdbc.sql("""
			SELECT count(*) FROM title_rule
			 WHERE type_code = 'J1' AND tone IS NULL AND judge_method IS DISTINCT FROM 'AI'
			""").query(Integer.class).single()).isZero();
		// 본문을 받지 않는 유형은 정기보고서와 증권 발행 서류뿐이다
		assertThat(jdbc.sql("SELECT code FROM disclosure_type WHERE NOT fetch_body ORDER BY code").query(String.class).list())
			.containsExactly("A1", "A4");
	}

	@Test
	void 앞뒤가_맞지_않는_대응표는_읽지_않는다() {
		TitleRuleBook.Type z9 = new TitleRuleBook.Type("Z9", JudgeMethod.AI, null);
		TitleRuleBook.Type b1 = new TitleRuleBook.Type("B1", JudgeMethod.CODE, null);

		// 방향이 있는데 제목으로 판정하는 줄이 아니다
		assertThatThrownBy(() -> new TitleRuleBook(1, List.of(z9, b1), List.of(new TitleRuleBook.Rule(1,
			TitleRuleBook.MatchKind.FORM, "영업(잠정)실적", "B1", Tone.POSITIVE, null, null, null)), List.of()))
			.isInstanceOf(IllegalStateException.class);
		// 없는 유형
		assertThatThrownBy(() -> new TitleRuleBook(1, List.of(z9), List.of(new TitleRuleBook.Rule(1,
			TitleRuleBook.MatchKind.FORM, "영업(잠정)실적", "B1", null, null, null, null)), List.of()))
			.isInstanceOf(IllegalStateException.class);
		// 다듬은 제목과 모양이 다른 글자(공백)는 영영 걸리지 않는다
		assertThatThrownBy(() -> new TitleRuleBook(1, List.of(z9, b1), List.of(new TitleRuleBook.Rule(1,
			TitleRuleBook.MatchKind.FORM, "영업(잠정) 실적", "B1", null, null, null, null)), List.of()))
			.isInstanceOf(IllegalStateException.class);
		// 낱말 줄인데 낱말이 없다, 낱말 줄이 아닌데 낱말이 있다
		assertThatThrownBy(() -> new TitleRuleBook(1, List.of(z9, b1), List.of(new TitleRuleBook.Rule(1,
			TitleRuleBook.MatchKind.FORM_WORD, "영업(잠정)실적", "B1", null, null, null, null)), List.of()))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new TitleRuleBook(1, List.of(z9, b1), List.of(new TitleRuleBook.Rule(1,
			TitleRuleBook.MatchKind.FORM, "영업(잠정)실적", "B1", null, null, null, "정정")), List.of()))
			.isInstanceOf(IllegalStateException.class);
		// 미분류 유형이 없다
		assertThatThrownBy(() -> new TitleRuleBook(1, List.of(b1), List.of(), List.of()))
			.isInstanceOf(IllegalStateException.class);
	}
}
