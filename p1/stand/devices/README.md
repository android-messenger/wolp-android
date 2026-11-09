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
