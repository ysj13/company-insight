package com.companyinsight.analysis.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 설정 묶음의 버전. 묶음의 값 전체를 정해진 순서로 JSON 글로 적어 해시를 내고, DB의 {@code setting_version}에서 그 해시의 줄을 찾는다.
 * 없으면 그 묶음의 번호를 하나 올린 줄을 더한다. 사람이 번호를 올리지 않는다.
 *
 * <p>번호는 처음 쓸 때 정하고 앱이 도는 동안 기억한다(지시문 파일은 앱이 뜰 때 한 번 읽으므로 도는 동안 바뀌지 않는다).
 */
@Component
public class AiSettingVersions {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final JdbcClient jdbc;
	private final Map<Bundle, Long> ids = new EnumMap<>(Bundle.class);

	public AiSettingVersions(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * 묶음의 지금 값에 해당하는 버전 ID. 처음 보는 값이면 새 번호의 줄을 더한다.
	 *
	 * @param settings 값 전체. 순서가 정해진 맵(LinkedHashMap 등)으로 준다. 같은 값이면 같은 글이 되어야 한다
	 */
	@Transactional
	public synchronized long idOf(Bundle bundle, Map<String, Object> settings) {
		Long cached = ids.get(bundle);
		if (cached != null) {
			return cached;
		}
		String json = JSON.writeValueAsString(settings);
		String hash = sha256(json);
		// 같은 묶음의 번호를 매기는 동안 다른 실행이 끼어들지 않게 잠근다 (앱이 둘 떠 있어도 번호가 겹치지 않게)
		jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:key))").param("key", "setting_version:" + bundle).query().singleRow();
		Long id = jdbc.sql("SELECT id FROM setting_version WHERE bundle = :bundle AND hash = :hash")
			.param("bundle", bundle.name())
			.param("hash", hash)
			.query(Long.class)
			.optional()
			.orElseGet(() -> jdbc.sql("""
					INSERT INTO setting_version (bundle, version, hash, settings)
					VALUES (:bundle, COALESCE((SELECT max(version) FROM setting_version WHERE bundle = :bundle), 0) + 1,
					        :hash, CAST(:settings AS jsonb))
					RETURNING id
					""")
				.param("bundle", bundle.name())
				.param("hash", hash)
				.param("settings", json)
				.query(Long.class)
				.single());
		ids.put(bundle, id);
		return id;
	}

	static String sha256(String text) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** 설정 묶음. DB의 {@code setting_version.bundle}에 이름 그대로 저장한다. */
	public enum Bundle {

		/** AI 판정의 모델과 지시문 */
		AI_JUDGE,

		/** AI 요약의 모델과 지시문 */
		AI_SUMMARY
	}
}
