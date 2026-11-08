# ADR-0001: оболочка приложения — Capacitor (вариант A)

Статус: **принято по итогам P1 на эмуляторе**, подтверждается на телефоне · 2026-11-08 · S1 §2.1, протокол: [`p1/README.md`](../../p1/README.md)

## Контекст
S1 §2.1: Capacitor (A), если проходят P1-a (весь трафик `app.<host>` через сетевой стек WebView с клиентским
сертификатом) и P1-b (мост через `addDocumentStartJavaScript` + `addWebMessageListener` с проверкой origin);
иначе свой WebView (B).

## Решение
**Вариант A: Capacitor 8.6.x** с обязательными условиями:
1. `server.url = https://app.<host>` **без пути**, `server.appStartPath = '/messenger-ui/'`, `allowNavigation = []`.
   Путь в `server.url` или хост `enroll` в `allowNavigation` переводят мост на `addJavascriptInterface` без проверки
   origin / расширяют разрешённые origin.
2. Переход на `enroll.<host>` разрешает `WolpWebViewClient.shouldOverrideUrlLoading` (не `allowNavigation`).
3. Проверки §3 — в `SplashActivity`; `MainActivity` (Bridge) стартует только с результатом «в мессенджер» и не экспортируется.
4. Порог `minWebViewMajor` (§3 шаг 1) не ниже версии с `DOCUMENT_START_SCRIPT` и `WEB_MESSAGE_LISTENER`;
   при старте — проверка `WebViewFeature.isFeatureSupported` обеих возможностей, иначе WebViewOutdatedScreen.
5. Плагины дополнительно сверяют origin в своём `WebMessageListener`-обёртке или полагаются на п. 1 — правила моста
   = `{https://app.<host>, https://localhost}`; тест «плагин недоступен с enroll и из iframe» входит в регрессию.
6. Обновление Capacitor (minor/major) — только с повтором P1-a/P1-b.

## Последствия для спецификаций (предложения)
- **S1 §2.1:** конфигурация варианта A — по п. 1 решения.
- **S1 §4.3, P1-d:** WebView применяет `pin-set` из `network_security_config` (WebView 113/API 34) → закрепление
  ключей `app.<host>` и `enroll.<host>` в NSC действует и на WebView; остаточный риск «MITM публичным CA» снижается.
  Нужны основной и запасной pin и процедура ротации серверного ключа (ошибка = приложение не открывается;
  обновление приложения идёт через `updateHttpClient()` без закрепления — §8 сохраняет путь восстановления).
  Перепроверить на свежем WebView.
- **S1 §7.3, P1-i:** нативный отказ через второе WS — только если WS веб-приложения закрыт (состояние после
  `wsClosed`/`about:blank` §7.0); иначе отказ через веб-приложение (`call:end` из JS).
- **S3 §5.8:** открытый WS живёт со старым сертификатом после ротации (P1-e) — ограничение долгих соединений на сервере обязательно.

## Отклонённое
- **B (свой WebView):** P1-a/P1-b проходят в A; B означал бы свой мост и потерю экосистемы плагинов без выигрыша в безопасности.
