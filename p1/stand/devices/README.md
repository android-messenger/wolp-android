# Стенд `devices` — регистрация, продление и `auth_request` (S3 задачи 3–5)

nginx (хосты `enroll` и `app`, S3 §10) → KratosGate с модулем `devices` → `device-signer` (unix-сокет) + Kratos + PostgreSQL; на хосте `app` запросы идут через `auth_request` → Oathkeeper → эхо-бэкенд (`traefik/whoami`, вместо мессенджера и `firebase-push`).
KratosGate и `device-signer` собираются из соседнего клона `android-messenger/gate` (`GATE_DIR`, по умолчанию `../../../../gate`).

```bash
./up.sh                                   # out/ (TLS, CA устройств), docker compose, ожидание /readyz
ANDROID_SERIAL=<serial> ./enroll.sh       # пользователь Kratos → вход → пароль онбординга → регистрация на телефоне
./app-check.sh                            # хост app без телефона: S3-N1, S3-N2, критерии 4 и 7a (синтетическое устройство)
ANDROID_SERIAL=<serial> ./renew.sh        # короткие сертификаты → регистрация → через минуту продление на телефоне
docker compose down -v                    # всё убрать (out/ остаётся)
```

`enroll.sh` ставит отладочный `sample` из `wolp-device-trust` (`./gradlew :sample:assembleDebug`) и запускает `StandEnrollActivity`: настоящий `DeviceTrust.enroll` (precheck → `enroll/start` → ключ в TEE с аттестацией на nonce шлюза → CSR → `enroll/finish`). Результат — строка `RESULT …` в logcat (`WOLP_STAND`), затем реестр устройств и журнал событий.

| Что | Как на стенде |
|---|---|
| Доступ телефона | `adb reverse tcp:9443 tcp:9443`, URL `https://localhost:9443` (8443 часто занят) — без root и без DNS |
| TLS | серверный сертификат от `pki/stand-root` — тестовый корень с **name constraints** (только `localhost`, `127.0.0.1`); debug-сборка `sample` доверяет ему только для этих адресов (NSC) и закрепляет ключ сервера + запасной (`out/backup.key`) |
| Сессия | `enroll.sh` входит в Kratos браузерным потоком (`curl`) и передаёт cookie `ory_kratos_session` в приложение |
| Приложение | `DEVICES_APP_PACKAGE=org.wolpmessenger.devicetrust.sample`, дайджест — debug-ключ (`APP_DIGEST`) |
| Список отзыва | настоящий `android.googleapis.com/attestation/status` |
| Хост `app` | порт `9444` (в контейнере 8444): mTLS с бандлом CA устройств (`out/device-ca-bundle.pem`), `auth_request /_device_check`, сниппеты `nginx/snippets/` (копия `strip-identity-headers.conf` из `deploy` + новые `strip-device-headers.conf`, `wolp-device-headers.conf`) |
| Приложение S1 (`app/`) | `APP_PACKAGE=org.wolpmessenger docker compose up -d kratosgate` (тот же debug-ключ); сборка — `app/tools/stand-config.sh`, затем `assembleProductDebug`. Вход в Kratos — страница стенда `https://localhost:9443/messenger-ui/login` (замена логина `messenger-ui`) через прокси `/.ory/kratos/public/` на хосте enroll |
| Веб-клиент на `app` | `/messenger-ui/` — **проба S1-3** (`stand-ui/app/`: эхо, ping, документ `/messenger/whoami`, камера/микрофон, файлы, `pushState`, базы `e2ee-*`, iframe) вместо сборки `messenger-ui`; nginx отдаёт статику за mTLS и `auth_request`, как на проде (путь без обязательной сессии). Эхо — `/messenger/headers` (у `traefik/whoami` путь `/echo` — WebSocket) |
| Порты хоста | `9443` enroll, `9444` app, `18091` admin API и `/readyz` модуля, `4433/4434` Kratos, `55433` PostgreSQL |

Телефон Samsung: выключить **Ajustes → Seguridad y privacidad → Bloqueador automático** (иначе «Depuración USB» не включается), режим разработчика — 7 нажатий на «Número de compilación».

## Результат 2026-11-09, Galaxy A37 (SM-A376B, Android 16, TEE)

- `RESULT OK`, `state Active(notAfter = +72 ч, renewAfter = +24 ч)`; сертификат выдан `CN=Wolp Device CA 2026`.
- Реестр: `active`, `TEE`, `rkp`, `osVersion 160000`, `patchLevel 202608`, `appVersionCode 10300`; события `enroll_started` → `enrolled`.
- Повторная регистрация того же пользователя → новое устройство, прежнее `revoked` / `replaced` (D15), событие `device_replaced`.
- Метрики: `attestation_chain_total{rkp,tee} 2`, `sessions_revoked_total 4` (прочие сессии Kratos завершены, текущая сохранена).
- Первая попытка: `EPERM` на сокете — у `sample` не было `INTERNET` (добавлено в debug-манифест).

## Задача 5, 2026-11-09

**`app-check.sh` — 27/27** (устройство синтетическое: ключ у скрипта, сертификат от промежуточного CA стенда, строки реестра вставлены напрямую):
- без клиентского сертификата nginx завершает рукопожатие TLS 1.3 и сам отвечает **400** «No required SSL certificate was sent» (не обрыв TLS); до `auth_request` запрос не доходит;
- S3-N1: рекурсии `auth_request` нет; `X-Device-Renew`/`X-Device-Deny` доходят в проксируемой location через include; значения `auth_request_set` **сохраняются** при переходе в `@dt_401`/`@dt_403` (`X-Device-Deny: NO_SESSION`, тело `{"error":"NO_SESSION"}`);
- в `location /v1/device/` `wolp-device-headers.conf` не подключается: модуль сам ставит `X-Device-Renew` (ping) и `X-Device-Deny` (отказы renew), иначе заголовки дублировались бы или затирались;
- S3-N2: поддельные `X-Device-Id`/`X-Device-User`/`X-Client-*`/`X-User-Id` снаружи не доходят; бэкенд получает один `X-Device-Id` (настоящий) и `X-User-Id` от Oathkeeper — и по правилу `app-firebase-push`; на хосте enroll поддельные заголовки тоже снимаются;
- критерий 7a: сертификат действителен для TLS, но истёк по реестру → `401 CERT_EXPIRED`; `ping` без сессии → `204`;
- критерий 4: отзыв админом → следующий же запрос `403 DEVICE_REVOKED` (LISTEN/NOTIFY, не ждёт TTL кэша);
- `/internal/devices/active` с HMAC отвечает; через хост `app` `/internal/*` недоступен (Oathkeeper: нет правила → 404).

**`renew.sh`, Galaxy A37:** регистрация → через минуту `StandRenewActivity`: `ping` 204 + `X-Device-Renew: 1`, `whoami` → `match`, запрос через `auth_request` и Oathkeeper — бэкенд видит `X-Device-Id` и `X-User-Id`; `ensureFresh()` → `renew/challenge` → новый ключ в TEE с аттестацией на nonce → `renew` (mTLS старым сертификатом, `continuitySig`) → `confirm` новым; после — те же запросы новым сертификатом, `X-Device-Renew` нет. Реестр: старый `retired`, новый `active` (использован), события `renewed` → `renew_confirmed`.

Находки:
- ключ устройства был создан только с `DIGEST_SHA256` — Conscrypt подписывает `CertificateVerify` через `NONEwithECDSA`, и телефон молча рвал рукопожатие mTLS (nginx: `peer closed connection in SSL handshake`). Исправлено в `wolp-device-trust` (`DIGEST_NONE`), есть инструментальный тест;
- `DeviceTrust.init()` — один раз на процесс: после `StandEnrollActivity` (оба URL — enroll) продление уходило на хост enroll (404). `renew.sh` перезапускает процесс приложения.

## S1-3, 2026-11-09, Galaxy A37 (приложение `app/`, productDebug)

`app-check.sh` — 31/31 (+ `/messenger-ui/` без сессии и с чужой сессией → 200 с `X-Device-Renew`; после отзыва → `403 DEVICE_REVOKED`). На телефоне, проба в WebView `MainActivity`:
- эхо через WebView: mTLS + cookie → бэкенд видит `X-Device-Id` и `X-User-Id`; вставки: `env(safe-area-inset-*)` 44px / 60px (edge-to-edge, Capacitor `SystemBars`);
- `getUserMedia` на `app` → системные запросы `CAMERA`/`RECORD_AUDIO` → дорожки audio + video; та же WebView на origin `enroll` → `NotAllowedError` (отказ в `WolpWebChromeClient`);
- выбор файла (DocumentsUI) → файл в `<input type=file>`; «Назад»: `#b` → `#a` → корень → приложение в фон; возврат — порядок §3 → `Messenger`, страница не перезагружается, ping при `onResume`;
- VaultReset (флаг через `run-as`): `e2ee-*` удалены до скриптов страницы, `chatDB` остался, флаг снят по подтверждению со страницы;
- `NO_SESSION` на документе (сессии Kratos удалены admin API) → `LoginActivity` → вход → `Messenger`;
- `USER_MISMATCH` (cookie bob подложена через CDP) → закреплённый `whoami` подтвердил → logout Kratos (сессия bob → 401) → экран «Другой пользователь» → «Войти снова» → `Messenger`;
- `Page.crash` (CDP) → `onRenderProcessGone`, процесс жив, новая `MainActivity`;
- продление при открытом WebView (`STAND_CERT_TTL=2h STAND_RENEW_BEFORE=119m`): `renew` → `confirm`, `credentialsVersion 2` → `clearClientCertPreferences`, страница не перезагружалась; следующий `fetch` WebView — уже с новым серийным номером;
- отзыв админом → перезагрузка документа → `X-Device-Deny: DEVICE_REVOKED` → `VERIFY` (ping) → `Blocked(REVOKED)`, очистка, экран блокировки.

Находки:
- **кэш `check` (`DEVICES_CHECK_CACHE_TTL`, 30 с) держит сессию Kratos после её удаления**: в этом окне запрос проходит `auth_request`, и 401 отдаёт уже Oathkeeper — **без `X-Device-Deny`**. Документ `app.<host>` тогда показывает JSON Oathkeeper, а не вход; после TTL — `NO_SESSION` как положено. В проде окно такое же (выход в другом месте, отзыв сессий админом);
- `input.click()` на `<input type=file>` из CDP не открывает выбор файла (нужен жест) — для автоматизации касание по координатам элемента × `devicePixelRatio`;
- первая загрузка страницы до применения вставок даёт `env(safe-area-inset-*) = 0`, затем Capacitor запрашивает вставки заново (`onPageCommitVisible`).

## S1-4, 2026-11-09, Galaxy A37 (productDebug)

Проба `stand-ui/app/` дополнена: кнопка «Все проверки» (`dtAll()`) прогоняет матрицу плагина `DeviceTrust` там, где страница открыта; на `app` — два iframe: того же origin и `enroll`; та же проба отдаётся на `enroll` (`/messenger-ui/`), кнопка «Открыть enroll» — переход главного фрейма. Результаты — в `#out` и в консоль (`PROBE DT …`).
- `app`, главный фрейм — 14/14: `getState` (`ACTIVE`, `notAfter`), `getDeviceInfo` (`TEE`, `strongBox=false`, 36, `2026-08-05`), `wrap`→`unwrap` = исходные байты; `unwrap` как `e2ee-plaintext` и испорченный блоб → `VAULT_CORRUPT`; `names`/`push-key`/`E2EE`/без назначения/не base64 → `INVALID_ARGUMENT`;
- iframe `app` → `PERMISSION_DENIED` на каждый вызов сразу (logcat `bridge guard: refused … mainFrame=false`);
- iframe `enroll` и главный фрейм на `enroll` — нет ни `Capacitor`, ни `androidBridge`; «Назад» на `app` — плагин снова работает;
- вопрос 8 HANDOFF: сессии Kratos удалены admin API → `fetch` в окне кэша `check` — `401` без `X-Device-Deny`; документ `/messenger/whoami` → `CHECK_SESSION` → Kratos сессии не видит → вход → `Messenger` (петли нет).

nginx: `nginx.conf` смонтирован файлом — после правки (новый inode) `nginx -s reload` видит старый текст, нужен `docker compose up -d --force-recreate nginx`.

## Живой мессенджер на стенде (`messenger-up.sh`), 2026-11-09

`./messenger-up.sh` (после `./up.sh`) добавляет к стенду бэкенд Hormigas как есть и сборку `messenger-ui`: `docker-compose.messenger.yml` — `messenger` (jar из `repos/HormigaMessanger/target/quarkus-app`, профиль `%prod`), `keydirectory` (образ `wolp-stand/key-directory:local`, собран из `HormigasMessenger/hormiga-key-directory` — образ в GHCR приватный), Redis, Kafka (KRaft, один узел), MinIO (`bitnamilegacy/minio`: MinIO больше не публикует образы), `front4mess` (сборка `repos/messenger/front`, `BASE=/messenger-ui/`). IDS — KratosGate стенда (gRPC `:9090`, REST `:8080`). Без webpush и coturn. `./messenger-up.sh down` — обратно к пробе.

| | Режим пробы (по умолчанию) | Режим мессенджера |
|---|---|---|
| `app` `/messenger-ui/` | проба S1-3/S1-4 | `messenger-ui` (`front4mess`); проба — `/probe/` |
| `app` прочее | Oathkeeper → эхо | Oathkeeper → `messenger`, `key-directory`, `/people/*`, `/ids/admin/*` (правила прода, `oathkeeper/messenger-rules.yml`); `/.ory/kratos/public/`, `/messenger-attachments` → MinIO; WebSocket |
| браузерный хост | — | `https://localhost:9445/messenger-ui/` без mTLS — второй пользователь в браузере компьютера (сертификат стенда — принять предупреждение) |

Переключается переменной `STAND_WEBCLIENT` (`nginx/webclient-<режим>/`, смонтирован в `/etc/nginx/webclient`). `app-check.sh` рассчитан на режим пробы (эхо на `/messenger/`).

Пользователи: `a37@stand.test` (телефон) и `bob@stand.test`, пароль `stand-Password-2026!`; трейты `name` и `role: client` (схема стенда дополнена полями прода). Oathkeeper стенда передаёт и `X-User-Email` (нужен `messenger`).

**Проверено на A37:** приложение после `Messenger` открывает живой `messenger-ui` (`isNativePlatform() = true`, защита моста установлена); имя пользователя из IDS; поиск bob (`/people/search`), создание чата (`POST /messenger/api/chats` 201), ключи E2EE телефона загружены в `key-directory`; bob в headless Chrome на `:9445` → сообщение → на телефоне по WebSocket через mTLS; ответ с телефона → у bob, оба ✓✓.

**Для S1-5** (видно на телефоне): `messenger-ui` не учитывает вставки — шапка под строкой состояния, **поле ввода под панелью навигации: касание уходит системе, набрать текст нельзя** (`viewport-fit=cover` + `env(safe-area-inset-*)`); в поиске у bob показан email вместо имени.

Находки:
- MinIO с конца 2025 не публикует образы (Docker Hub и quay пусты) — `bitnamilegacy/minio` (последняя сборка Bitnami) создаёт бакет сам (`MINIO_DEFAULT_BUCKETS`);
- подписанные ссылки MinIO подписывают `Host`: стенд отдаёт `/messenger-attachments` с обоих хостов с `Host: localhost:9444` (`MINIO_ENDPOINT`). Ссылки абсолютные на `:9444` — из браузера на `:9445` вложения не откроются (нужен клиентский сертификат); на проде один edge-хост;
- Oathkeeper прода ставит `X-User-Id` только при `role` = `client`/`master`.


## UI в APK (`security/09`), 2026-11-10, Galaxy A37 (productDebug)

Приложение грузит app-сборку `messenger-ui` из `assets/public` как `https://localhost/` (`wolp.uiHost=localhost`, same-site с `localhost:9444`); API, WebSocket и Kratos — кросс-origin на `https://localhost:9444`. nginx хоста `app`: CORS только для `Origin: https://localhost` (`map $wolp_ui_origin` в `nginx.conf`, сниппет `snippets/wolp-ui-cors.conf` в каждой location, куда ходит UI, включая `@dt_401`/`@dt_403`); preflight `OPTIONS` отвечает `return 204` на уровне `server` — до `auth_request` (у preflight нет cookie). Заголовки CORS от upstream (`Kratos`) снимаются.

Сборка: `VITE_API_ORIGIN=https://localhost:9444 npm run build:app` в `messenger/front` (+ `VITE_TURN_HOST`, `VITE_MESSENGER_ADMIN_KEY` как у `front4mess`), затем `assembleProductDebug` (`wolp.uiDist` по умолчанию — этот `dist-app`).

| Проверка | Результат |
|---|---|
| UI из APK | `Handling local request: https://localhost/assets/…` — в сеть за ассетами не ходит; `location.origin = https://localhost`, `document.cookie` пуст (cookie Kratos — у `localhost:9444`) |
| preflight из WebView | **несёт клиентский сертификат**: `OPTIONS … 204 verify=SUCCESS` (curl без сертификата — `400`) |
| XHR/fetch с cookie и mTLS | `whoami`, `/messenger/api/chats`, `/people/*`, `/key-directory/v1/*` — `200` |
| `fetch` с `credentials: "omit"` | сертификат тоже уходит (`verify=SUCCESS`), cookie — нет → `401 X-Device-Deny: NO_SESSION`; заголовок читается из JS (`Access-Control-Expose-Headers`) |
| WebSocket | `wss://localhost:9444/messenger/ws` → `101`, кадры E2EE от телефона в логе `messenger` |
| мост | `bridge guard: installed for https://localhost`; `vaultWrap` с главного фрейма UI проходит |
| переход документа на `localhost:9444` | `navigation to the API host refused: /probe/`, страница остаётся на `https://localhost/` |
| `X-Device-Deny` из XHR | `onReceivedHttpError` срабатывает: `webview deny NO_SESSION (401, mainFrame=false) → NONE` |
| уход веб-клиента на `/login` при живой сессии | `web client went to /login: native login` → снова `https://localhost/` |
| вложения: PUT/GET на `/messenger-attachments` с `credentials`/`withCredentials` | preflight `204`, запрос доходит до MinIO (ответ MinIO читается) |

Находки:
- без `credentials` presigned-загрузка вложения получила бы `NO_SESSION` (`auth_request` на `/messenger-attachments`): в `messenger-ui` загрузка и выгрузка теперь с cookie;
- вложения на стенде не работали и без UI в APK: `messenger` сам ходит в MinIO по `MINIO_ENDPOINT=https://localhost:9444` (один endpoint для себя и клиентов), а изнутри контейнера это `Connection refused`. **Исправлено (2026-11-10):** спутник `minio-loopback` (`network_mode: service:messenger`, `nginx/minio-loopback.conf`) слушает `localhost:9444` в сетевом пространстве `messenger` с TLS стенда и проксирует в MinIO с `Host: localhost:9444`; JVM мессенджера доверяет корню стенда (`out/stand-trust.p12`, собирает `messenger-up.sh`, `JAVA_TOOL_OPTIONS`). Полный цикл из UI в APK: `upload-url` → PUT `200` → `confirm` `202` → `download-url` → GET `200`, байты совпадают;
- отзыв устройства на UI из APK не прогонялся (нужна новая регистрация): ветка `onReceivedHttpError` → `DenyPolicy` та же, что проверена в S1-3, изменился только origin документа.

## Плагин `Files` (S1 §5.4), 2026-11-10, Galaxy A37

`Capacitor.Plugins.Files.saveDownload({url, fileName, mimeType})` с presigned-ссылкой вложения → системный диалог «Guardar» с именем файла → `{"saved":true}`, `/sdcard/Download/wolp-test.txt` побайтно равен выгруженному. URL на чужом хосте и на origin самого UI → `INVALID_ARGUMENT`; отмена диалога → `{"saved":false}`; ответ MinIO `403` → `DOWNLOAD_FAILED`, недописанный документ удалён.
