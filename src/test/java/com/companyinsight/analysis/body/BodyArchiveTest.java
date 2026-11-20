package com.companyinsight.analysis.body;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class BodyArchiveTest {

	@Test
	void 실제_공시의_zip을_푼다() {
		// DART가 준 zip은 파일의 날짜 칸이 달력에 없는 값이다(2107-15-13 등). 그래도 풀린다
		List<BodyFile> files = BodyArchive.unzip(BodyFixtures.zip(BodyFixtures.VOLUNTARY));

		assertThat(files).singleElement().satisfies(file -> {
			assertThat(file.name()).isEqualTo("20251128800899.xml");
			assertThat(file.content()).hasSize(4326);
		});
	}

	@Test
	void 파일이_여럿이면_들어_있는_순서대로_돌려준다() throws IOException {
		byte[] zip = zip("20260310002820.xml", "첫 파일", "20260310002820_00760.xml", "둘째 파일");

		assertThat(BodyArchive.unzip(zip)).extracting(BodyFile::name)
			.containsExactly("20260310002820.xml", "20260310002820_00760.xml");
	}

	@Test
	void 폴더와_빈_파일은_뺀다() throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream out = new ZipOutputStream(bytes)) {
			out.putNextEntry(new ZipEntry("folder/"));
			out.closeEntry();
			out.putNextEntry(new ZipEntry("empty.xml"));
			out.closeEntry();
			out.putNextEntry(new ZipEntry("body.xml"));
			out.write("본문".getBytes(StandardCharsets.UTF_8));
			out.closeEntry();
		}

		assertThat(BodyArchive.unzip(bytes.toByteArray())).extracting(BodyFile::name).containsExactly("body.xml");
	}

	@Test
	void 안에_파일이_없는_zip은_읽지_못한다() throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		new ZipOutputStream(bytes).close();

		assertThatThrownBy(() -> BodyArchive.unzip(bytes.toByteArray())).isInstanceOf(BodyFormatException.class);
	}

	@Test
	void 중간에_잘린_zip은_읽지_못한다() {
		byte[] whole = BodyFixtures.zip(BodyFixtures.VOLUNTARY);
		byte[] cut = java.util.Arrays.copyOf(whole, 300);

		assertThatThrownBy(() -> BodyArchive.unzip(cut)).isInstanceOf(BodyFormatException.class);
	}

	@Test
	void zip이_아닌_바이트는_읽지_못한다() {
		assertThatThrownBy(() -> BodyArchive.unzip("<result><status>014</status></result>".getBytes(StandardCharsets.UTF_8)))
			.isInstanceOf(BodyFormatException.class);
	}

	@Test
	void 파일이_상한보다_많으면_읽지_못한다() throws IOException {
		String[] entries = new String[(BodyArchive.MAX_FILES + 1) * 2];
		for (int i = 0; i <= BodyArchive.MAX_FILES; i++) {
			entries[i * 2] = "file" + i + ".xml";
			entries[i * 2 + 1] = "본문";
		}

		assertThatThrownBy(() -> BodyArchive.unzip(zip(entries))).isInstanceOf(BodyFormatException.class)
			.hasMessageContaining("너무 많습니다");
	}

	/** 이름과 내용을 번갈아 받아 zip을 만든다. */
	static byte[] zip(String... nameAndContent) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream out = new ZipOutputStream(bytes)) {
			for (int i = 0; i < nameAndContent.length; i += 2) {
				out.putNextEntry(new ZipEntry(nameAndContent[i]));
				out.write(nameAndContent[i + 1].getBytes(StandardCharsets.UTF_8));
				out.closeEntry();
			}
		}
		return bytes.toByteArray();
	}
}
