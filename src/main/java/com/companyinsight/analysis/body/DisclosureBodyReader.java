package com.companyinsight.analysis.body;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 저장한 공시 본문을 글자로 읽는다. 본문 표 읽기와 AI 판정·요약이 본문을 얻는 곳이다.
 *
 * <p>글자는 저장해 두지 않고 부를 때마다 원문에서 뽑는다. 뽑는 코드를 고치면 다음 실행부터 반영된다.
 * 여기서 읽은 글은 판정과 요약의 재료다. 화면에 그대로 내지 않는다.
 */
@Service
public class DisclosureBodyReader {

	private final DisclosureBodyRepository bodies;

	public DisclosureBodyReader(DisclosureBodyRepository bodies) {
		this.bodies = bodies;
	}

	/**
	 * 공시 한 건의 본문 문서들. 압축 파일에 들어 있던 순서대로다(대부분 하나).
	 *
	 * @return 저장한 본문이 없으면(아직 받지 않았거나 본문이 없는 공시) 비어 있음
	 * @throws BodyFormatException 저장한 파일을 풀거나 읽지 못했을 때
	 */
	public Optional<List<BodyDocument>> read(long disclosureId) {
		return bodies.findContent(disclosureId).map(DisclosureBodyReader::documents);
	}

	/**
	 * 본문 원문(출처가 준 압축 파일 그대로)을 문서들로 읽는다.
	 *
	 * @throws BodyFormatException 파일을 풀거나 읽지 못했을 때
	 */
	public static List<BodyDocument> documents(byte[] zip) {
		return BodyArchive.unzip(zip).stream().map(BodyTextExtractor::extract).toList();
	}
}
