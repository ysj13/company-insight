package com.companyinsight.disclosure;

import com.companyinsight.common.source.Source;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Component;

/**
 * 공시 보고서명만 보고 유형을 붙이고, 제목으로 방향이 정해지는 공시는 방향까지 정한다.
 *
 * <p>규칙은 코드가 아니라 대응표({@link TitleRuleBook})에 있다. 앱이 뜰 때 DB에서 한 번 읽는다.
 *
 * <p>순서: 머리말·공백·기간 표기를 떼고({@link TitleNormalizer}) → 속성 꼬리(자회사, 자율공시 등)를 떼어 속성으로 적고
 * → 대응표에서 규칙을 찾는다. 대응표에 없는 제목은 미분류 유형을 받는다.
 *
 * <p>방향은 판정 방법이 "제목"인 공시에만 붙인다. 본문 표나 AI가 정하는 공시는 유형만 붙이고 방향을 비워 둔다.
 * 모르는 것을 중립으로 단정하지 않기 위해서다. 영향도는 여기서 정하지 않는다.
 */
@Component
@DependsOnDatabaseInitialization
public class DisclosureClassifier {

	/** 비고의 철회 표시 */
	private static final String WITHDRAWN_MARK = "철";

	/** 제목 꼬리의 철회 표시. "신규시설투자등(철회)"처럼 이미 낸 결정을 거둔다는 공시다 */
	private static final String WITHDRAWAL_WORD = "철회";

	/** 꼬리에 철회가 든 공시의 속성 이름. 대응표의 속성 꼬리와 달리 꼬리 안의 글자로 찾는다 */
	static final String WITHDRAWAL = "WITHDRAWAL";

	private final TitleRuleBook book;

	@Autowired
	public DisclosureClassifier(TitleRuleRepository rules) {
		this(rules.load(Source.DART));
	}

	DisclosureClassifier(TitleRuleBook book) {
		this.book = book;
	}

	/** 지금 쓰는 대응표의 버전. {@code disclosure.title_rule_version}에 저장한다. */
	public int version() {
		return book.version();
	}

	/**
	 * 공시 한 건을 분류한다.
	 *
	 * @param reportName 보고서명. 머리말과 공백이 섞여 있어도 된다.
	 * @param remark     DART 비고. 없으면 null.
	 */
	public Classification classify(String reportName, String remark) {
		TitleNormalizer.Normalized normalized = TitleNormalizer.normalize(reportName);

		String title = normalized.title();
		List<String> attributes = new ArrayList<>();
		for (TitleRuleBook.Attribute attribute : book.attributes()) {
			if (title.contains(attribute.token())) {
				title = title.replace(attribute.token(), "");
				if (!attributes.contains(attribute.attribute())) {
					attributes.add(attribute.attribute());
				}
			}
		}
		int tail = title.indexOf('(');
		boolean withdrawal = tail >= 0 && title.indexOf(WITHDRAWAL_WORD, tail) >= 0;
		if (withdrawal) {
			attributes.add(WITHDRAWAL);
		}

		TitleRuleBook.Rule rule = book.find(title).orElse(null);
		Tone tone = book.titleToneOf(rule);
		if (withdrawal && (tone == Tone.POSITIVE || tone == Tone.NEGATIVE)) {
			// 서식의 방향은 결정을 냈을 때의 것이다. 결정을 거둔다는 공시에 같은 방향을 붙이지 않고 판정을 미룬다
			tone = null;
		}
		boolean withdrawn = isWithdrawn(remark);
		if (withdrawn) {
			// 철회된 공시는 없던 일이 된다. 호재·악재로 세지 않고, 본문을 읽을 대상에서도 뺀다.
			tone = Tone.NEUTRAL;
		}
		return new Classification(rule == null ? DisclosureType.UNCLASSIFIED : rule.typeCode(),
			rule == null ? null : rule.id(), rule == null ? null : rule.caseCode(), title, List.copyOf(attributes), tone,
			tone == null ? null : ClassifiedBy.RULE, normalized.hasPrefix(), withdrawn);
	}

	/** 비고에 철회 표시가 있는지. */
	public static boolean isWithdrawn(String remark) {
		return remark != null && remark.contains(WITHDRAWN_MARK);
	}
}
