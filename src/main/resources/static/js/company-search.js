/*
 * 회사 검색창의 자동완성. 글자를 치면 회사 목록을 보여주고, 하나를 고르면 그 회사의 공시만 보는 주소로 간다.
 * 이 스크립트가 없어도 검색창은 동작한다(찾기 버튼을 누르면 이름이 맞는 회사를 모두 보여준다).
 */
(function () {
	'use strict';

	var box = document.querySelector('[data-company-search]');
	if (!box) {
		return;
	}
	var input = box.querySelector('input');
	var list = box.querySelector('ul');
	var status = box.querySelector('[role="status"]');
	var form = input.form;
	var api = box.getAttribute('data-api');

	var DELAY_MS = 200;
	var timer = null;
	var requestNo = 0; // 늦게 도착한 이전 응답이 최신 목록을 덮어쓰지 않게 한다
	var active = -1;

	function close() {
		// 기다리던 조회와 응답을 함께 버린다. 그러지 않으면 닫은 뒤에 도착한 응답이 목록을 다시 연다
		clearTimeout(timer);
		requestNo++;
		list.hidden = true;
		list.textContent = '';
		status.hidden = true;
		active = -1;
		input.setAttribute('aria-expanded', 'false');
		input.removeAttribute('aria-activedescendant');
	}

	function span(className, text) {
		var el = document.createElement('span');
		el.className = className;
		el.textContent = text; // 회사명은 외부에서 온 값이라 HTML로 넣지 않는다
		return el;
	}

	/**
	 * 고른 회사의 공시만 보는 주소. 판정과 종류 조건은 풀어서 그 회사의 공시 전체를 먼저 보여준다.
	 * 조건을 유지하면 "악재만 보기" 상태에서 회사를 골랐을 때 빈 화면이 나와 회사에 공시가 없는 것처럼 보인다.
	 */
	function urlFor(companyId) {
		return form.getAttribute('action') + '?company=' + encodeURIComponent(companyId);
	}

	function render(companies) {
		list.textContent = '';
		active = -1;
		input.removeAttribute('aria-activedescendant');
		if (companies.length === 0) {
			// 고를 항목이 없으므로 목록은 닫아 두고, 안내는 목록 밖에서 알린다
			list.hidden = true;
			input.setAttribute('aria-expanded', 'false');
			status.hidden = false;
			return;
		}
		status.hidden = true;
		companies.forEach(function (company, i) {
			var item = document.createElement('li');
			item.id = 'company-option-' + i;
			item.setAttribute('role', 'option');
			item.setAttribute('aria-selected', 'false');
			item.dataset.company = company.id;
			item.appendChild(span('name', company.name));
			item.appendChild(span('code num', company.stockCode));
			// 시장 구분은 영문 표기(KOSPI 등)가 화면 이름과 같아 응답 값을 그대로 쓴다
			if (company.market) {
				item.appendChild(span('market', company.market));
			}
			list.appendChild(item);
		});
		list.hidden = false;
		input.setAttribute('aria-expanded', 'true');
	}

	function search() {
		var keyword = input.value.trim();
		if (!keyword) {
			close();
			return;
		}
		var mine = ++requestNo;
		fetch(api + '?q=' + encodeURIComponent(keyword), { headers: { Accept: 'application/json' } })
			.then(function (response) {
				if (!response.ok) {
					// 오류를 "맞는 회사가 없습니다"로 보여주면 틀린 안내가 된다. 목록만 닫는다
					throw new Error('company search failed');
				}
				return response.json();
			})
			.then(function (companies) {
				if (mine === requestNo && document.activeElement === input) {
					render(companies);
				}
			})
			.catch(function () {
				if (mine === requestNo) {
					close();
				}
			});
	}

	function options() {
		return list.querySelectorAll('[role="option"]');
	}

	function move(step) {
		var items = options();
		if (items.length === 0) {
			return;
		}
		if (active >= 0) {
			items[active].setAttribute('aria-selected', 'false');
		}
		active = (active + step + items.length) % items.length;
		items[active].setAttribute('aria-selected', 'true');
		items[active].scrollIntoView({ block: 'nearest' });
		input.setAttribute('aria-activedescendant', items[active].id);
	}

	input.addEventListener('input', function () {
		clearTimeout(timer);
		timer = setTimeout(search, DELAY_MS);
	});

	input.addEventListener('keydown', function (event) {
		// 한글 조합 중의 키 입력은 건드리지 않는다. 일부 브라우저는 조합이 끝난 직후의 키를 keyCode 229로만 알려 준다
		if (event.isComposing || event.keyCode === 229) {
			return;
		}
		if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
			if (list.hidden) {
				search();
			}
			else {
				move(event.key === 'ArrowDown' ? 1 : -1);
			}
			event.preventDefault();
		}
		else if (event.key === 'Enter' && active >= 0) {
			// 목록에서 고른 회사가 있으면 그 회사로 간다. 없으면 폼이 그대로 제출된다
			event.preventDefault();
			location.href = urlFor(options()[active].dataset.company);
		}
		else if (event.key === 'Escape' && !list.hidden) {
			event.preventDefault();
			close();
		}
	});

	// click보다 먼저 일어나는 mousedown에서 막아야 입력창이 포커스를 잃지 않는다
	list.addEventListener('mousedown', function (event) {
		event.preventDefault();
	});

	list.addEventListener('click', function (event) {
		var item = event.target.closest('[role="option"]');
		if (item) {
			location.href = urlFor(item.dataset.company);
		}
	});

	input.addEventListener('blur', close);

	// 뒤로 가기로 돌아왔을 때 이전에 열려 있던 목록이 남지 않게 한다
	window.addEventListener('pageshow', close);
})();
