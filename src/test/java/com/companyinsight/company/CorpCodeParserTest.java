package com.companyinsight.company;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class CorpCodeParserTest {

	private final CorpCodeParser parser = new CorpCodeParser();

	@Test
	void 상장사만_읽는다() {
		List<CorpCode> rows = parser.parseListed(CorpCodeZipFixture.zip());

		assertThat(rows).extracting(CorpCode::name).containsExactly("삼성전자", "SK하이닉스", "삼성SDI");
		assertThat(rows.getFirst())
			.isEqualTo(new CorpCode("00126380", "005930", "삼성전자", LocalDate.of(2025, 6, 30)));
	}

	@Test
	void 수정일이_비어있거나_형식이_틀리면_null() {
		String xml = """
			<result><list><corp_code>00000001</corp_code><corp_name>가나다</corp_name>
			<stock_code>111111</stock_code><modify_date></modify_date></list>
			<list><corp_code>00000002</corp_code><corp_name>라마바</corp_name>
			<stock_code>222222</stock_code><modify_date>2025-01-01</modify_date></list></result>
			""";

		List<CorpCode> rows = parser.parseListed(CorpCodeZipFixture.zip(xml));

		assertThat(rows).extracting(CorpCode::modifyDate).containsOnlyNulls();
	}

	@Test
	void 외부_엔티티를_처리하지_않는다() {
		String xml = """
			<?xml version="1.0"?>
			<!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]>
			<result><list><corp_code>00000001</corp_code><corp_name>&x;</corp_name>
			<stock_code>111111</stock_code></list></result>
			""";

		assertThatThrownBy(() -> parser.parseListed(CorpCodeZipFixture.zip(xml)))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void zip_안에_xml이_없으면_실패() {
		assertThatThrownBy(() -> parser.parseListed(new byte[] { 'P', 'K', 5, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 }))
			.isInstanceOf(IllegalStateException.class);
	}
}
