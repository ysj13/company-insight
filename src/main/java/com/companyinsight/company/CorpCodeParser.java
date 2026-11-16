package com.companyinsight.company;

import com.companyinsight.common.dart.DartText;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.springframework.stereotype.Component;

/**
 * DART 고유번호 zip(CORPCODE.xml)을 읽어 상장사만 돌려준다.
 *
 * <p>파일에는 비상장사를 포함해 약 10만 개 회사가 들어 있다. DOM으로 한 번에 올리지 않고 StAX로 한 행씩 읽는다.
 * 종목코드가 비어 있으면 비상장사라 버린다.
 */
@Component
public class CorpCodeParser {

	private static final int CORP_CODE_MAX = 8;
	private static final int NAME_MAX = 200;

	/**
	 * @param zip DART 고유번호 zip 파일 내용
	 * @return 종목코드가 있는 회사 목록. 고유번호 형식이 틀린 건은 빠진다.
	 * @throws IllegalStateException zip 안에 XML이 없거나 XML 형식이 틀린 경우
	 */
	public List<CorpCode> parseListed(byte[] zip) {
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
			ZipEntry entry;
			while ((entry = in.getNextEntry()) != null) {
				if (entry.getName().toUpperCase().endsWith(".XML")) {
					return parseXml(in);
				}
			}
			throw new IllegalStateException("고유번호 zip에 XML 파일이 없습니다");
		}
		catch (IOException e) {
			throw new UncheckedIOException("고유번호 zip을 읽지 못했습니다", e);
		}
	}

	private List<CorpCode> parseXml(InputStream in) {
		XMLInputFactory factory = XMLInputFactory.newFactory();
		// 외부 엔티티를 막는다 (XXE).
		factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
		factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);

		List<CorpCode> result = new ArrayList<>();
		try {
			XMLStreamReader reader = factory.createXMLStreamReader(in);
			String corpCode = null, name = null, stockCode = null, modifyDate = null;
			String current = null;
			StringBuilder text = new StringBuilder();
			while (reader.hasNext()) {
				int event = reader.next();
				if (event == XMLStreamConstants.START_ELEMENT) {
					current = reader.getLocalName();
					text.setLength(0);
					if ("list".equals(current)) {
						corpCode = name = stockCode = modifyDate = null;
					}
				}
				else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
					text.append(reader.getText());
				}
				else if (event == XMLStreamConstants.END_ELEMENT) {
					String tag = reader.getLocalName();
					String value = text.toString().trim();
					switch (tag) {
						case "corp_code" -> corpCode = value;
						case "corp_name" -> name = value;
						case "stock_code" -> stockCode = value;
						case "modify_date" -> modifyDate = value;
						case "list" -> {
							if (isListed(stockCode) && isValidCorpCode(corpCode) && name != null && !name.isEmpty()) {
								result.add(new CorpCode(corpCode, stockCode, DartText.clean(name, NAME_MAX),
									DartText.parseDate(modifyDate).orElse(null)));
							}
						}
						default -> { }
					}
					text.setLength(0);
				}
			}
			reader.close();
		}
		catch (XMLStreamException e) {
			throw new IllegalStateException("CORPCODE.xml 형식이 올바르지 않습니다", e);
		}
		return result;
	}

	/** 고유번호는 8자리. 길이가 다르면 그 한 건만 건너뛴다. 한 건 때문에 전체 동기화가 실패하면 안 된다. */
	private static boolean isValidCorpCode(String corpCode) {
		return corpCode != null && !corpCode.isEmpty() && corpCode.length() <= CORP_CODE_MAX;
	}

	/** 종목코드가 6자리면 상장(또는 상장했던) 회사다. 비상장사는 종목코드가 비어 있다. */
	private static boolean isListed(String stockCode) {
		return stockCode != null && stockCode.length() == 6;
	}
}
