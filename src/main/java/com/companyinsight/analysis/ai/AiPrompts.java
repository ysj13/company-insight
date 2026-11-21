package com.companyinsight.analysis.ai;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * AI 지시문과 유형별 판정 기준. 리소스 파일({@code prompts/})에서 앱이 뜰 때 한 번 읽는다.
 * 파일을 고치면 설정 버전이 바뀌어({@link AiSettingVersions}) 어느 지시문으로 판정했는지 실행 기록에 남는다.
 */
@Component
public class AiPrompts {

	private final String judgeSystem;
	private final String summarySystem;
	private final String criteriaText;
	private final String casesText;
	private final Map<String, String> criteria;
	private final Map<String, List<CaseOption>> cases;

	public AiPrompts() {
		this(read("prompts/ai-judge.txt"), read("prompts/ai-summary.txt"), read("prompts/judge-criteria.tsv"),
				read("prompts/judge-cases.tsv"));
	}

	AiPrompts(String judgeSystem, String summarySystem, String criteriaText, String casesText) {
		this.judgeSystem = judgeSystem.strip();
		this.summarySystem = summarySystem.strip();
		this.criteriaText = criteriaText;
		this.casesText = casesText;
		this.criteria = parseCriteria(criteriaText);
		this.cases = parseCases(casesText);
	}

	/** AI 판정의 지시문. */
	public String judgeSystem() {
		return judgeSystem;
	}

	/** AI 요약의 지시문. */
	public String summarySystem() {
		return summarySystem;
	}

	/**
	 * 유형의 판정 기준.
	 *
	 * @return 적어 두지 않은 유형이면 null (일반 기준을 쓴다)
	 */
	public String criteria(String typeCode) {
		return criteria.get(typeCode);
	}

	/** AI가 함께 고르는 경우. 없는 유형이면 빈 목록. */
	public List<CaseOption> cases(String typeCode) {
		return cases.getOrDefault(typeCode, List.of());
	}

	/** 판정 기준 파일의 글 그대로 (설정 버전의 해시에 넣는다). */
	String criteriaText() {
		return criteriaText;
	}

	/** 경우 파일의 글 그대로 (설정 버전의 해시에 넣는다). */
	String casesText() {
		return casesText;
	}

	private static Map<String, String> parseCriteria(String text) {
		Map<String, String> parsed = new LinkedHashMap<>();
		for (String[] fields : rows(text)) {
			if (fields.length != 2) {
				throw new IllegalStateException("판정 기준 파일의 줄은 '유형<TAB>기준'이어야 합니다: " + String.join("\t", fields));
			}
			if (parsed.put(fields[0], fields[1]) != null) {
				throw new IllegalStateException("판정 기준 파일에 같은 유형이 둘입니다: " + fields[0]);
			}
		}
		return Map.copyOf(parsed);
	}

	private static Map<String, List<CaseOption>> parseCases(String text) {
		Map<String, List<CaseOption>> parsed = new LinkedHashMap<>();
		for (String[] fields : rows(text)) {
			if (fields.length != 3) {
				throw new IllegalStateException("경우 파일의 줄은 '유형<TAB>경우<TAB>설명'이어야 합니다: " + String.join("\t", fields));
			}
			parsed.computeIfAbsent(fields[0], k -> new ArrayList<>()).add(new CaseOption(fields[1], fields[2]));
		}
		Map<String, List<CaseOption>> copy = new LinkedHashMap<>();
		parsed.forEach((type, options) -> copy.put(type, List.copyOf(options)));
		return Map.copyOf(copy);
	}

	/** 빈 줄과 #로 시작하는 줄을 뺀 줄들을 탭으로 나눈다. */
	private static List<String[]> rows(String text) {
		List<String[]> rows = new ArrayList<>();
		for (String line : text.split("\\R")) {
			if (line.isBlank() || line.startsWith("#")) {
				continue;
			}
			String[] fields = line.split("\t");
			for (int i = 0; i < fields.length; i++) {
				fields[i] = fields[i].strip();
			}
			rows.add(fields);
		}
		return rows;
	}

	private static String read(String path) {
		try (InputStream in = new ClassPathResource(path).getInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException("지시문 파일을 읽지 못했습니다: " + path, e);
		}
	}

	/**
	 * AI가 함께 고르는 경우 하나.
	 *
	 * @param code        경우 이름 (크기 설정의 키)
	 * @param description AI에 보여주는 설명
	 */
	public record CaseOption(String code, String description) {
	}
}
