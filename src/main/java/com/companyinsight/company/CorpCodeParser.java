package com.companyinsight.company;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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

	private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

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
							if (isListed(stockCode) && corpCode != null && !corpCode.isEmpty() && name != null) {
								result.add(new CorpCode(corpCode, stockCode, name, parseDate(modifyDate)));
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

	private static boolean isListed(String stockCode) {
		return stockCode != null && stockCode.length() == 6;
	}

	private static LocalDate parseDate(String value) {
		if (value == null || value.isEmpty()) {
			return null;
		}
		try {
			return LocalDate.parse(value, YYYYMMDD);
		}
		catch (DateTimeParseException e) {
			return null;
		}
	}
}
