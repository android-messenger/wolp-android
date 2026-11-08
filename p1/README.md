# P1 — прототип оболочки (S1 §2.1, §11 P1)

Цель — пройти проверки **P1-a … P1-i** и зафиксировать в ADR выбор: **A** (Capacitor) или **B** (свой WebView).
Прототип = вариант A на **Capacitor 8.6.0**. Вариант B пишется только если A не проходит P1-a/P1-b.

```
p1/
  stand/   локальный стенд mTLS: nginx (как в S3: mTLS только на app.<host>) + probe (Go)
  app/     Capacitor-проект; нативная часть — app/android (Kotlin)
  tools/   sync-ca.sh — корневой CA стенда в debug-сборку
```

## Стенд
```bash
cd p1/stand && ./pki/gen.sh && docker compose up -d --build && ./smoke.sh
```
- `enroll.wolp.test` — обычный TLS: SPA-вход (`/messenger-ui/login`, HttpOnly `ory_kratos_session`, Domain=`wolp.test`), выпуск клиентского сертификата `POST /p1/enroll` (заглушка S2/S3 enroll).
- `app.wolp.test` — `ssl_verify_client on`, `ssl_session_tickets off`, `ssl_session_timeout 5m` (как в S3). Страница-проба `/messenger-ui/`, `/p1/echo`, WS `/p1/ws`, `/v1/device/ping`, SW `/messenger-ui/sw.js`.
- Журнал: `docker compose logs -f nginx` (nginx: `serial`, `reused`, `sid`; probe: `agent` = webview|native по User-Agent, `dest` = `Sec-Fetch-Dest`) или `GET https://enroll.wolp.test/p1/journal` (на `app` без клиентского сертификата — 400).
- Устройство должно резолвить `app.wolp.test`/`enroll.wolp.test` в адрес хоста со стендом (эмулятор: `10.0.2.2` через `/etc/hosts` с `-writable-system`; телефон: DNS/hosts роутера или `adb reverse tcp:443 tcp:443` + запись в hosts на rooted-устройстве).

## Эмулятор
```bash
emulator -avd p1-api34 -writable-system -no-snapshot &
adb root && adb remount && adb reboot        # первый раз (system rw)
adb root && adb remount && adb shell 'printf "10.0.2.2 app.wolp.test\n10.0.2.2 enroll.wolp.test\n" >> /etc/hosts'
```
AVD: `system-images;android-34;google_apis;x86_64` (в образе System WebView 113). Записи в hosts сохраняются между запусками с `-writable-system`.

Если эмулятор падает с segfault в `libtcmalloc`, это глобальная предзагрузка в `/etc/ld.so.preload` (так было с Citrix
`libAppProtection.so`). Обход без root — `tools/emulator/run.sh`: эмулятор в контейнере со своим `/etc`.

## Сборка
```bash
./p1/tools/sync-ca.sh
cd p1/app && npm ci && npx cap sync android
cd android && JAVA_HOME=/opt/jdk-21.0.2 ./gradlew assembleStdDebug assemblePinbogusDebug   # Gradle 8.14 не работает на JDK 25
```
`-Pp1Host=<домен>` меняет хост стенда. Flavor `pinbogus` ставит заведомо неверный `<pin-set>` на `app.wolp.test` (P1-d).
Протокол на устройстве: `adb logcat -s P1`.

## Проверки
| # | Как проверяется | Что считается «пройдено» |
|---|---|---|
| P1-a | `request … -> network(WebView)` / `CAPACITOR-HANDLED` в logcat; журнал стенда: у всех запросов `app.*` есть `serial`, `agent=webview`, документ с `dest=document` | ни одного `CAPACITOR-HANDLED` для `app.*`; нет запросов `agent=native` кроме нативного ping |
| P1-b | проба показывает `androidBridge=WebMessageListener`/`JavascriptInterface`; logcat `bridge: allowedOriginRules=…`; вызов плагина из `enroll` и из iframe | `WebMessageListener`; с `enroll` и из iframe вызов отклонён |
| P1-c | кнопка «Cookie (натив)» после входа на `enroll` | `enrollHasSession` и `appHasSession` = true, в `document.cookie` cookie нет |
| P1-d | flavor `pinbogus`: страница грузится? «Нативный ping» падает? Кнопка CT → `no-sct.badssl.com` | натив отказывает; поведение WebView фиксируется как факт |
| P1-e | «Сменить сертификат» → новые XHR/fetch с новым serial; строка WS продолжает показывать старый; `reused` в журнале nginx | новые соединения — новый serial; срок жизни старых фиксируется |
| P1-f | «Холодный старт» — отметки от старта процесса до `jsReady(app)` | часть «WebView»; S2/WS/Signal — в P3/P5 |
| P1-g | открыть один раз, выключить сеть, убить процесс, запустить | проба грузится из SW (`(OFFLINE …)`) |
| P1-h | «Звонок: FGS+мик», выключить экран на 1–2 мин, «Разрывы таймера» | нет разрывов > 2.5 с при `callService=true`, уровень мика > 0 |
| P1-i | статически по коду бэкенда (см. ниже) | — |

## Результаты — эмулятор API 34 (google_apis x86_64), System WebView 113, Capacitor 8.6.0, 2026-11-08

| # | Итог | Факты |
|---|---|---|
| P1-a | **пройдено** | logcat: все запросы `app.*` — `network(WebView)`, ни одного `CAPACITOR-HANDLED`; `clientCertRequest … -> proceed`. nginx: документ, `probe.js`, XHR, fetch, SW, WS — все с `serial`, HTTP/2 одним соединением. До выпуска сертификата `app` не запрашивается (Splash). |
| P1-b | **пройдено при исправленной конфигурации** | `server.url` **с путём** (как в S1 §2.1) → мост молча уходит на `JavascriptInterface`, правила `[app/messenger-ui/, localhost, app, enroll]`. `server.url = https://app.<host>` + `appStartPath` + пустой `allowNavigation` → `WebMessageListener`, правила `[https://localhost, https://app.<host>]`. На `enroll` нет ни `androidBridge`, ни `Capacitor`. iframe того же origin: объект есть, натив отклоняет (`Plugin execution is allowed in Main Frame only`), JS-промис при этом не завершается. |
| P1-c | **пройдено** | После SPA-входа на `enroll` `doUpdateVisitedHistory` сразу видит `ory_kratos_session`; `CookieManager`: `enrollHasSession=true`, `appHasSession=true`; в `document.cookie` нет (HttpOnly); запрос на `app` несёт cookie. |
| P1-d | **закрепление: WebView применяет NSC `pin-set`**; CT: не проверено | Неверный pin на `app.<host>` → WebView `sslError primary=3`, страница не грузится; верный pin → грузится, нативный ping 200. Ожидание S1 («нет») не подтвердилось — в лучшую сторону. CT: `no-sct.badssl.com` с истёкшим сертификатом (12.2025), проверка невозможна — нужен другой хост без SCT. |
| P1-e | **пройдено** | После `clearClientCertPreferences` первый же запрос — новое соединение с новым serial; старое HTTP/2-соединение больше не используется. Открытый WS живёт со старым serial (ключ уже удалён). Возобновления TLS-сессий нет (`reused=.`, tickets off). |
| P1-f | частично | Старт процесса → `jsReady(app)`: ~3.7 с с выпуском сертификата, ~1.3–1.5 с с готовым. Без S2, WS мессенджера и Signal — замер на реальном телефоне с `messenger-ui` в P3/P5. |
| P1-g | **пройдено** | Без сети: сертификат из Keystore, оболочка из SW, `jsReady` 1.5 с; API — `ERR_ADDRESS_UNREACHABLE`. |
| P1-h | **пройдено** | Bridge не паузит WebView. Контроль (фон + экран выкл., 90 с, без FGS): разрывов таймера нет, WS жив — подтверждает проблему §7.0. Со службой: FGS `types=0x84` (phoneCall\|microphone), `startForeground(phoneCall)` на API 34 без `SecurityException`, запись `silenced:false`, разрывов нет. Негативный контроль (глушение мика без FGS) на эмуляторе не определяется — на телефоне. |
| P1-i | **не проходит (по коду бэкенда)** | Одно WS на пользователя: второе вытесняет первое (`4409 session superseded`, `PresenceCoordinator.join`), веб-клиент на 4409 не переподключается. Нативный отказ через второе WS — только когда WS веб-приложения закрыт (§7.0). Кадр: один `SIGNAL_IN`, без hello. |

### Что не закрыто эмулятором
- реальный телефон: P1-f полностью, P1-h негативный контроль, Android 13 (API 33);
- свежий System WebView (в образе 113): повторить P1-a/P1-b/P1-d — особенно применение `pin-set` в WebView;
- CT — нужен публичный хост без SCT.

## Статический разбор (до запуска)
- **P1-a.** В Capacitor 8.6 `Bridge.loadWebView()` внедряет мост через `addDocumentStartJavaScript`, если WebView поддерживает `DOCUMENT_START_SCRIPT`, и тогда `jsInjector = null`; `WebViewLocalServer.handleProxyRequest()` проксирует документ через `HttpURLConnection` **только** при `jsInjector != null`. На WebView без `DOCUMENT_START_SCRIPT` проксирование вернётся → порог `minWebViewMajor` (§3 шаг 1) обязателен.
- **P1-b.** Обработчик Capacitor проверяет только `isMainFrame`, `sourceOrigin` не проверяет; origin-правила = хост приложения + `server.url` + `allowNavigation`; при недопустимом правиле — откат на `addJavascriptInterface` без предупреждения в UI.
- **Порядок §3.** `Bridge` создаётся только в `onCreate` (регистрирует ActivityResult) и сразу грузит `server.url` → проверки §3 в отдельной `SplashActivity`, `MainActivity` не экспортируется.
- **P1-g.** `messenger-ui`: `vite-plugin-pwa` `generateSW`, precache `index.html` и бандлов, scope `/messenger-ui/`.
