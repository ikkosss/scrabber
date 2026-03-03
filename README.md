# BankScraperLogger

Примитивное Android-приложение (minSdk 26), которое ведёт себя как встроенный браузер на базе `WebView` и **собирает локально**:

- посещённые URL (включая переходы/редиректы best-effort),
- HTML исходник загруженных страниц (через `evaluateJavascript(document.documentElement.outerHTML)`),
- cookies (строкой через `CookieManager.getCookie(url)`),
- сетевые запросы, которые видит `WebViewClient.shouldInterceptRequest` (URL, method, request headers, флаги mainFrame/redirect и т.п.).

Данные пишутся в приватное хранилище приложения в реальном времени (`filesDir/bsl_sessions/<sessionId>/...`) и **экспортируются** через системный диалог “Save As” (Storage Access Framework, без опасных storage-permissions):

- **ZIP (рекомендуется)**: внутри `export.json`, `events.jsonl`, `pages.jsonl`, `meta.json`
- **JSON**: один файл с `meta`, `events[]`, `pages[]`

## Как запустить

- Откройте проект в Android Studio.
- Запустите конфигурацию `app`.

## Как пользоваться

- Введите URL и нажмите **Go**.
- Нажмите **Start** чтобы начать сбор.
- Логин/пароль вводите вручную на сайте (приложение их не сохраняет).
- В любой момент нажмите **Stop**.
- Нажмите **Export** и выберите формат (**ZIP** или **JSON**) и место сохранения.

## Важные замечания

- Это инструмент для личного использования/исследований. Банки могут блокировать подозрительную активность.
- WebView не даёт полноценный доступ к телам ответов/статус-кодам для всех запросов; логирование реализовано best‑effort на уровне, доступном через стандартные API.

