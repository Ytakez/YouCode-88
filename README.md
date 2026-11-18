# YouCode

Премиум Android-чат с ИИ. Интерфейс в духе Grok на основе Material Design 3: нейтральные поверхности, спокойные акценты, без лишних декораций и смайликов.  
Бесплатные модели работают сразу. Можно прикреплять и сохранять файлы.

---

## Как получить APK только с телефона (через GitHub)

### 1. Создай репозиторий на GitHub
- Открой github.com → **New repository**
- Название: `YouCode`
- Сделай **Public**
- Нажми Create

### 2. Залей файлы
Самый простой способ с телефона:

1. Скачай zip ниже
2. Распакуй
3. В репозитории нажми **Add file → Upload files**
4. Загрузи **всё** содержимое папки YouCode (включая папки `.github`, `app`, `gradle`)

### 3. Дождись сборки
- Перейди во вкладку **Actions**
- Увидишь workflow **Build APK**
- Подожди 3–5 минут пока станет зелёным ✓

### 4. Скачай APK
1. Открой успешный запуск (зелёная галочка)
2. Внизу найди **Artifacts** → **YouCode-APK**
3. Скачай zip → внутри будет `app-debug.apk`
4. Установи на телефон (разреши установку из неизвестных источников)

---

Если Actions не запустился автоматически — зайди в Actions и нажми **Run workflow**.

## Дизайн (Grok + Material Design 3)

Интерфейс вдохновлён спокойным тёмным стилем Grok, но построен на Material Design 3.

- Нейтральные почти чёрные поверхности в тёмной теме и чистые светлые — в светлой.
- Акценты (кнопки, выделения) могут браться из системной палитры Material You на Android 12+.
- Скругления умеренные, без «пузырчатого» Expressive-стиля.
- Без смайликов в режимах, подсказках и чипах.
- Пустое состояние чата — простой иконкой и текстом, без морфинг-анимаций.

Цвета и формы задаются в `ui/theme/Theme.kt`. На Android 11 и ниже используется встроенная палитра.

## Подключение собственного облачного ИИ

В разделе **Провайдеры → Свой Cloud / API** можно указать:

1. Base URL или домен OpenAI-совместимого API (например, `https://api.example.com/v1`). Можно вставить и полный адрес `/v1/chat/completions`.
2. Точный ID модели, выданный провайдером.
3. API-ключ (если провайдер требует авторизацию).
4. Выбери протокол **OpenAI API** или **Claude API**.

OpenAI API использует Chat Completions (`POST .../chat/completions`); Claude API использует Anthropic Messages (`POST .../v1/messages`). Обычный сайт без API не заработает: нужен именно API endpoint и ID модели, поддерживаемые выбранным провайдером. Ключ хранится локально в приватных настройках приложения; не публикуй APK со своим ключом.

## Реальное подключение облачных моделей

В приложении откройте **Провайдеры** и заполните Base URL, точный ID модели и API-ключ.

Рекомендуемые базовые адреса:

- OpenCode Go: `https://opencode.ai/zen/go/v1` (OpenAI Chat Completions API)
- OpenCode Zen: `https://opencode.ai/zen/v1` (для моделей, у которых доступен Chat Completions endpoint)
- Anthropic: `https://api.anthropic.com/v1` и переключатель Claude API
- Другие OpenAI-совместимые API: укажите выданный провайдером Base URL.

Ключи OpenCode Go/Zen создаются в консоли OpenCode. Подписка/кредиты провайдера оплачиваются отдельно; само приложение не предоставляет бесплатный доступ к платным моделям. Для Zen некоторые модели используют Responses API или другие протоколы и не совместимы с текущим Chat Completions адаптером. Используйте модель с Chat Completions либо подключайте её через совместимый шлюз.

Если провайдер не настроен, приложение теперь показывает явное сообщение о необходимости подключения вместо демонстрационного ответа.


## Подключение бесплатных моделей OpenCode к Android

Бесплатный тариф OpenCode разрешает запускать эти модели внутри официального OpenCode. Прямые запросы из стороннего Android-клиента к бесплатным inference endpoint блокируются OpenCode (403 FreeTierError). Поэтому YouCode подключается к вашему собственному OpenCode Server: именно сервер OpenCode выполняет запрос к выбранной бесплатной модели.

1. На компьютере установите и запустите OpenCode, авторизуйте провайдера OpenCode через `/connect` и убедитесь, что нужная бесплатная модель доступна в каталоге.
2. Запустите защищённый сервер (пример для Linux/macOS/WSL):

```bash
OPENCODE_PASSWORD='замените-на-длинный-пароль' opencode serve --hostname 0.0.0.0 --port 4096
```

3. Телефон и компьютер должны быть в одной Wi-Fi сети. Узнайте LAN IP компьютера (например, `192.168.1.10`).
4. В YouCode откройте «Провайдеры» → «OpenCode Server (бесплатные модели)», введите `http://192.168.1.10:4096` и тот же пароль.
5. Выберите одну из моделей в YouCode. Приложение получает реальный каталог моделей с сервера и отправляет генерацию через OpenCode Server API.

Не открывайте порт 4096 напрямую в интернет. Для удалённого доступа используйте защищённый туннель/официальное pairing OpenCode. API-подключение требует работающего компьютера/сервера с OpenCode; один только ZIP не может предоставить бесплатные вычисления.


## Pollinations API integration

The 12 model choices in the app are routed through the Pollinations OpenAI-compatible API:
`https://gen.pollinations.ai/v1`.

1. Open **Providers** in the app.
2. Select **Pollinations AI (12 моделей)**.
3. Create a secret API key at https://enter.pollinations.ai and paste it into the API-key field.
4. Save the provider, then choose any of the 12 Pollinations-labelled models in the model picker.

The app maps its 12 stable model choices to Pollinations model aliases. Pollinations may change its live model catalog, aliases, access rules, and pricing. API use requires a Pollinations key and consumes the account's Pollen credits; this integration does **not** promise unlimited or permanently free inference. Check the Pollinations dashboard before use.

## Каталог OpenCode (live)

The provider screen now reads the live provider integrations from `GET /api/integration` and the model catalogue from `GET /api/model` on the configured OpenCode Server. Model IDs retain their `providerID` so identically named models from different providers remain distinct. Provider API keys entered for a live integration are sent to the server's `POST /api/integration/{providerID}/connect/key` endpoint; they are not stored in YouCode preferences. Refreshing the provider screen reloads both catalogues. OAuth/browser sign-in and Claude Code's local external-process flow are not yet implemented in this YouCode port.


## Авторизация провайдеров через OpenCode OAuth

Для провайдеров, у которых OpenCode Server объявляет OAuth-метод, экран **Провайдеры** показывает кнопку входа. YouCode вызывает официальные маршруты OpenCode:

- `POST /api/integration/{provider}/connect/oauth`
- `GET /api/integration/{provider}/connect/oauth/{attempt}`
- `POST /api/integration/{provider}/connect/oauth/{attempt}/complete`

Браузер открывает страницу авторизации. Для обычного browser-flow приложение ожидает статус `complete`; если сервер использует режим `code`, приложение просит вставить код со страницы. OAuth работает через подключённый пользователем OpenCode Server — YouCode не содержит собственных client secrets провайдеров.

## Android 17 / Material You visual refresh

The interface has been refreshed around Material 3 expressive styling:
- wallpaper-based dynamic colors on Android 12 and newer, with light/dark system theme support;
- softer, more expressive corner radii and tonal surfaces;
- refreshed YouCode chat header and model selector;
- updated empty-chat welcome state;
- redesigned navigation drawer brand panel.

The redesign changes presentation only; existing chat, provider, model, and settings flows are retained.


## Анимации и дизайн (v1.2.0)

Общие помощники лежат в `ui/theme/Motion.kt`:

- `pressScale()` — пружинное сжатие кнопок, карточек и пунктов меню под пальцем;
- `entrance()` — упругое каскадное появление (прозрачность + сдвиг + zoom);
- `aurora()` — живой фон из дрейфующих пятен цвета Material You;
- `MorphingOrb` — логотип с морфингом формы, парением и пульсирующим свечением;
- `floating()` — плавное «парение».

Что изменилось в интерфейсе:

- чат: сообщения въезжают с нужной стороны, прыгающие точки набора, кнопка «вниз», индикатор загрузки в кнопке отправки, вибро-отклик, подсказки в пустом чате, тень верхней панели при прокрутке;
- переходы между экранами с учётом направления (shared axis) и predictive back на Android 14+ (экран уменьшается и скругляется под жестом);
- шторка, настройки, скиллы, провайдеры и выбор модели получили анимации появления и реакцию на нажатие;
- скругления, поверхности и заголовки приведены к более выразительному стилю Material 3.

Если в системе отключены анимации, бесконечные эффекты (фон, свечение, парение) Compose не запускает.

## Встроенный OpenCode: бесплатные модели без ключей (v1.3.0)

Приложение запускает настоящий `opencode serve` на самом телефоне (`127.0.0.1`), а YouCode общается с ним
по обычному HTTP API, так же как раньше с внешним OpenCode Server. Запросы идут от официального opencode,
поэтому бесплатные модели OpenCode не блокируются, и компьютер не нужен.

- `libopencode.so` собирает GitHub Actions скриптом `engine/build-engine.sh` (opencode v2.2.5 на Bun для Android,
  патч в `engine/patches/`). Первая сборка долгая, дальше результат берётся из кэша.
  Файл в репозиторий не заливается (он большой), заливай исходники, а APK бери из Actions.
- Нужен Android 9+ и arm64 (`minSdk` = 28).
- При старте приложение само подключает встроенный сервер и выбирает бесплатную модель.
  Если в «Провайдерах» указан внешний сервер, он остаётся приоритетным.
- Модели «Pollinations» требуют ключ и не помечены как бесплатные.
- У бесплатных моделей OpenCode есть лимит использования, судя по всему, он считается по IP-адресу.
- Пока идёт ответ, работает уведомление-сервис, чтобы система не замораживала приложение.
- Документация по встраиванию opencode в JS-приложения (`@opencode/sdk`) здесь не используется:
  она работает внутри JS-рантайма и не открывает HTTP-порт, а приложению на Kotlin нужен именно сервер.

Лицензии сторонних компонентов: `THIRD_PARTY_LICENSES.md`.


## Supabase backend foundation (1.3.4)

See `SUPABASE_SETUP.md` and run `supabase/setup.sql` in the Supabase SQL Editor. Project URL: `https://gzuzyagiqgvpeqkksaml.supabase.co`. The publishable key is intended for client use; never put a service-role/secret key in the APK. This release adds the database/RLS foundation only; Android auth UI and the metered public AI gateway remain to be implemented. Quotas are not enforced until all model requests pass through that gateway.


## Вход по электронной почте (Supabase)

В версии 1.3.5 добавлены экраны входа и регистрации через Supabase Auth. Перед сборкой APK откройте Supabase → Authentication → Providers → Email и включите Email provider. Если включено подтверждение почты, после регистрации пользователь должен перейти по ссылке из письма и затем войти в приложение.

В приложении токены сессии сохраняются в зашифрованном виде с использованием Android Keystore. Публичный publishable key используется на клиенте; секретный service_role ключ в приложение добавлять нельзя.
