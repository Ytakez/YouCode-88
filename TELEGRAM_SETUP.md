# Вход через Telegram (YouCode 1.3.7)

Вход по почте убран. Приложение открывает бота по ссылке, пользователь подтверждает вход кнопкой, а Edge Function выдаёт сессию Supabase.

## Настройка (один раз)
1. В Telegram откройте @BotFather → `/newbot`, получите **токен** и **username** бота.
2. Supabase Dashboard → SQL Editor → запустите `supabase/setup.sql` заново (появится таблица `telegram_login_requests`).
3. Установите Supabase CLI и задайте секреты (придумайте случайную строку для `TELEGRAM_WEBHOOK_SECRET`, только `A-Za-z0-9_-`):
   ```
   supabase secrets set TELEGRAM_BOT_TOKEN=123456:ABC... TELEGRAM_BOT_USERNAME=your_bot TELEGRAM_WEBHOOK_SECRET=случайная_строка --project-ref gzuzyagiqgvpeqkksaml
   ```
4. Задеплойте функции (флаг `--no-verify-jwt` обязателен: приложение шлёт publishable key, а Telegram — свой заголовок):
   ```
   supabase functions deploy telegram-auth --no-verify-jwt --project-ref gzuzyagiqgvpeqkksaml
   supabase functions deploy telegram-webhook --no-verify-jwt --project-ref gzuzyagiqgvpeqkksaml
   ```
5. Подключите webhook бота:
   ```
   curl "https://api.telegram.org/bot<ТОКЕН>/setWebhook" \
     -d "url=https://gzuzyagiqgvpeqkksaml.supabase.co/functions/v1/telegram-webhook" \
     -d "secret_token=<TELEGRAM_WEBHOOK_SECRET>"
   ```
6. Authentication → Sign In / Providers → Email: провайдер оставьте включённым (через него выдаётся сессия), а **«Allow new users to sign up» выключите**: тогда создать аккаунт по почте напрямую через API нельзя, пользователей создаёт только функция.

## Как это работает
- Приложение → `telegram-auth` (`start`) → получает одноразовый токен и ссылку `t.me/<бот>?start=<токен>`.
- Бот присылает кнопку «Это я, войти»; после нажатия Telegram ID записывается в запрос.
- Приложение опрашивает `telegram-auth` (`poll`) и получает сессию. Токен живёт 10 минут и работает один раз.
- Для каждого Telegram ID создаётся пользователь `tg<id>@users.youcode.app` (почта служебная, письма не отправляются).

## Важно
- Если кто-то пришлёт жертве свою ссылку входа и она нажмёт «Это я», злоумышленник войдёт в её аккаунт. Поэтому бот предупреждает об этом в тексте. Полностью это не убрать без кода, который пользователь сверяет между приложением и ботом.
- Старые аккаунты по почте остаются в базе, но войти в них из приложения больше нельзя.
