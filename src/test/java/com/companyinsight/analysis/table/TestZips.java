package com.companyinsight.analysis.table;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 테스트용 본문 원문(zip)을 만든다. */
final class TestZips {

	private TestZips() {
	}

	/** 문서 하나가 든 본문 원문. 글은 UTF-8로 적는다. */
	static byte[] single(String fileName, String markup) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.putNextEntry(new ZipEntry(fileName));
			zip.write(markup.getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return out.toByteArray();
	}
}
