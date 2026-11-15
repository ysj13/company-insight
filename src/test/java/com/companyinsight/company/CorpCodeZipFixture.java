package com.companyinsight.company;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 테스트용 DART 고유번호 zip. 실제 파일과 같은 구조. */
final class CorpCodeZipFixture {

	static final String XML = """
		<?xml version="1.0" encoding="UTF-8"?>
		<result>
		    <list>
		        <corp_code>00126380</corp_code>
		        <corp_name>삼성전자</corp_name>
		        <corp_eng_name>SAMSUNG ELECTRONICS CO,.LTD</corp_eng_name>
		        <stock_code>005930</stock_code>
		        <modify_date>20250630</modify_date>
		    </list>
		    <list>
		        <corp_code>00164779</corp_code>
		        <corp_name>SK하이닉스</corp_name>
		        <corp_eng_name>SK hynix Inc.</corp_eng_name>
		        <stock_code>000660</stock_code>
		        <modify_date>20250701</modify_date>
		    </list>
		    <list>
		        <corp_code>00999999</corp_code>
		        <corp_name>비상장주식회사</corp_name>
		        <corp_eng_name></corp_eng_name>
		        <stock_code> </stock_code>
		        <modify_date>20240101</modify_date>
		    </list>
		    <list>
		        <corp_code>00126371</corp_code>
		        <corp_name>삼성SDI</corp_name>
		        <corp_eng_name>SAMSUNG SDI CO.,LTD.</corp_eng_name>
		        <stock_code>006400</stock_code>
		        <modify_date>20250520</modify_date>
		    </list>
		</result>
		""";

	private CorpCodeZipFixture() {
	}

	static byte[] zip() {
		return zip(XML);
	}

	static byte[] zip(String xml) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.putNextEntry(new ZipEntry("CORPCODE.xml"));
			zip.write(xml.getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return out.toByteArray();
	}
}
