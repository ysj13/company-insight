package com.companyinsight.analysis.body;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;
import org.jsoup.select.NodeFilter;
import org.jsoup.select.NodeTraversor;

/**
 * 공시 본문 문서 파일에서 글자를 뽑는다. 형식은 둘이다: 거래소 공시의 HTML, 금융위·공정위 문서의 DART 전용 XML.
 *
 * <p>뽑은 글의 모양: 문단과 표의 한 줄이 글의 한 줄이다. 표의 칸은 {@value #CELL_SEPARATOR}로 나누고 빈 칸은 뺀다.
 * 칸 안의 줄바꿈과 문단은 공백으로 잇는다(칸 안에 든 표는 줄을 바꾼다). 줄 안의 이어진 공백은 하나로 줄이고 빈 줄은 없앤다.
 * 스타일과 문서 머리(HTML의 head)는 버린다. 여러 줄에 걸친 칸(rowspan)은 첫 줄에만 나오고,
 * 빈 칸을 빼므로 칸의 자리는 남지 않는다. 표를 자리로 읽으려면 {@link BodyDocument#markup()}을 쓴다.
 *
 * <p>글자 집합은 파일 안의 표시를 믿지 않는다. 거래소 공시는 UTF-8인데 meta 태그에는 euc-kr이라고 적혀 있다.
 * UTF-8로 읽어 보고, 맞지 않는 바이트가 많을 때만 MS949로 읽는다.
 */
public final class BodyTextExtractor {

	/** 표의 칸을 나누는 글자 */
	public static final String CELL_SEPARATOR = " | ";

	private static final Charset MS949 = Charset.forName("MS949");

	/** 글자로 바꾸지 못한 바이트 자리에 들어가는 글자 (U+FFFD) */
	private static final char REPLACEMENT = (char) 0xFFFD;

	/** 파일 맨 앞에 붙을 수 있는 바이트 순서 표시 (U+FEFF) */
	private static final char BOM = (char) 0xFEFF;

	/** 뽑는 동안 칸의 끝을 표시하는 글자. 본문에 나오지 않는 제어 문자다 */
	private static final char CELL_END = '\u0001';

	/** 글이 들어 있지 않거나 본문이 아닌 요소. 통째로 건너뛴다 */
	private static final Set<String> SKIPPED = Set.of("head", "style", "script", "colgroup", "col", "formula-version", "summary");

	/** 표의 칸 (HTML의 td·th, DART 전용 XML의 td·th·tu·te) */
	private static final Set<String> CELLS = Set.of("td", "th", "tu", "te");

	/** 칸 안에서도 줄을 바꾸는 요소. 칸 안의 나머지 요소(br, p, div, li 등)는 공백으로 이어 표의 한 줄이 여러 줄로 갈라지지 않게 한다 */
	private static final Set<String> HARD_BREAKS = Set.of("table", "tr");

	/** 줄을 바꾸지 않는 요소. 여기에 없는 요소는 모두 앞뒤로 줄을 바꾼다 (처음 보는 요소가 글을 붙여 버리지 않게) */
	private static final Set<String> INLINE = Set.of("span", "a", "b", "i", "u", "font", "strong", "em", "sub", "sup",
		"small", "big", "label", "img");

	/** 공백으로 보는 글자의 이어짐. 줄바꿈 없는 공백(U+00A0)과 전각 공백(U+3000)도 공백이다 */
	private static final Pattern SPACES = Pattern.compile("[\\s\\u00A0\\u3000]+");

	private static final Pattern XML_START = Pattern.compile("^\\s*(<\\?xml|<DOCUMENT[\\s>])");

	private BodyTextExtractor() {
	}

	/**
	 * 문서 파일 하나를 글자로 읽는다.
	 *
	 * @throws BodyFormatException 파일이 비어 있을 때
	 */
	public static BodyDocument extract(BodyFile file) {
		String markup = decode(file.content());
		if (markup.isBlank()) {
			throw new BodyFormatException("본문 파일이 비어 있습니다");
		}
		BodyFormat format = XML_START.matcher(markup).find() ? BodyFormat.XML : BodyFormat.HTML;
		// 전용 XML을 HTML로 읽으면 HTML에 없는 칸 요소(TU, TE)가 표 밖으로 밀려난다. 형식에 맞는 읽기를 쓴다
		Document document = format == BodyFormat.XML ? Jsoup.parse(markup, "", Parser.xmlParser()) : Jsoup.parse(markup);
		return new BodyDocument(file.name(), format, markup, toText(document));
	}

	/**
	 * 바이트를 글자로 바꾼다. UTF-8이 맞으면 UTF-8. 맞지 않는 바이트가 드물면(천 글자에 하나 이하) 그 자리만 바꾼 UTF-8,
	 * 많으면 MS949로 읽는다. 맨 앞의 BOM은 뗀다.
	 */
	static String decode(byte[] bytes) {
		String text;
		try {
			text = StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(bytes))
				.toString();
		}
		catch (CharacterCodingException e) {
			String lenient = new String(bytes, StandardCharsets.UTF_8);
			long broken = lenient.chars().filter(c -> c == REPLACEMENT).count();
			// UTF-8 파일에 깨진 바이트가 몇 개 섞인 것을 MS949로 읽으면 글 전체가 깨진다
			text = broken * 1000 <= lenient.length() ? lenient : new String(bytes, MS949);
		}
		return !text.isEmpty() && text.charAt(0) == BOM ? text.substring(1) : text;
	}

	private static String toText(Document document) {
		StringBuilder raw = new StringBuilder();
		NodeTraversor.filter(new NodeFilter() {

			/** 지금 몇 겹의 칸 안에 있는지 */
			private int cellDepth;

			@Override
			public FilterResult head(Node node, int depth) {
				if (node instanceof TextNode text) {
					raw.append(text.getWholeText().replace('\n', ' ').replace('\r', ' ').replace(CELL_END, ' '));
				}
				else if (node instanceof Element element) {
					String name = element.normalName();
					if (SKIPPED.contains(name)) {
						return FilterResult.SKIP_ENTIRELY;
					}
					if (CELLS.contains(name)) {
						cellDepth++;
					}
					else if (breaksLine(name)) {
						raw.append(lineBreak(name));
					}
				}
				return FilterResult.CONTINUE;
			}

			@Override
			public FilterResult tail(Node node, int depth) {
				if (node instanceof Element element) {
					String name = element.normalName();
					if (CELLS.contains(name)) {
						cellDepth--;
						raw.append(CELL_END);
					}
					else if (breaksLine(name)) {
						raw.append(lineBreak(name));
					}
				}
				return FilterResult.CONTINUE;
			}

			private char lineBreak(String name) {
				return cellDepth > 0 && !HARD_BREAKS.contains(name) ? ' ' : '\n';
			}
		}, document);

		List<String> lines = new ArrayList<>();
		for (String rawLine : raw.toString().split("\n")) {
			List<String> cells = new ArrayList<>();
			for (String cell : rawLine.split(String.valueOf(CELL_END))) {
				String clean = SPACES.matcher(cell).replaceAll(" ").strip();
				if (!clean.isEmpty()) {
					cells.add(clean);
				}
			}
			if (!cells.isEmpty()) {
				lines.add(String.join(CELL_SEPARATOR, cells));
			}
		}
		return String.join("\n", lines);
	}

	private static boolean breaksLine(String name) {
		return !INLINE.contains(name) && !CELLS.contains(name);
	}
}
