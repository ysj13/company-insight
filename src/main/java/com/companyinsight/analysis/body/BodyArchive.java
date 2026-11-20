package com.companyinsight.analysis.body;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 출처가 준 본문 압축 파일(zip)을 푼다. 안에 문서 파일이 하나 이상 있다(대부분 하나, 사업보고서는 둘이나 셋).
 *
 * <p>밖에서 받은 파일이므로 푸는 양에 상한을 둔다. 작은 zip이 풀면 메모리를 다 쓰는 크기가 되는 일을 막는다.
 */
public final class BodyArchive {

	/** 파일 수 상한. 본 것 가운데 가장 많은 것이 3개다 */
	static final int MAX_FILES = 50;

	/**
	 * 푼 크기의 합 상한. 받는 본문 가운데 본 것으로 가장 큰 것은 연 1회 내는 대규모기업집단현황공시(대표회사용)로 약 8MB다.
	 * 푼 글과 읽은 문서가 함께 메모리에 올라가므로 그 몇 배까지만 받는다
	 */
	static final long MAX_TOTAL_BYTES = 32L * 1024 * 1024;

	/** 파일 이름의 글자 집합. 이름이 UTF-8이 아니어도 예외 없이 읽는다 (본 이름은 모두 접수번호.xml) */
	private static final Charset NAME_CHARSET = Charset.forName("MS949");

	private BodyArchive() {
	}

	/**
	 * zip을 풀어 안의 파일을 들어 있는 순서대로 돌려준다. 폴더와 빈 파일은 뺀다.
	 *
	 * @throws BodyFormatException zip이 깨졌거나, 파일이 없거나, 상한을 넘을 때
	 */
	public static List<BodyFile> unzip(byte[] zip) {
		List<BodyFile> files = new ArrayList<>();
		long total = 0;
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), NAME_CHARSET)) {
			byte[] buffer = new byte[8192];
			for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
				if (entry.isDirectory()) {
					continue;
				}
				if (files.size() >= MAX_FILES) {
					throw new BodyFormatException("본문 zip 안의 파일이 너무 많습니다 (상한 " + MAX_FILES + "개)");
				}
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
					total += read;
					if (total > MAX_TOTAL_BYTES) {
						throw new BodyFormatException("본문 zip을 푼 크기가 상한을 넘습니다 (상한 " + MAX_TOTAL_BYTES + "바이트)");
					}
					out.write(buffer, 0, read);
				}
				if (out.size() > 0) {
					files.add(new BodyFile(entry.getName(), out.toByteArray()));
				}
			}
		}
		catch (IOException | IllegalArgumentException e) {
			throw new BodyFormatException("본문 zip을 풀지 못했습니다: " + e.getClass().getSimpleName());
		}
		if (files.isEmpty()) {
			throw new BodyFormatException("본문 zip 안에 파일이 없습니다");
		}
		return List.copyOf(files);
	}
}
