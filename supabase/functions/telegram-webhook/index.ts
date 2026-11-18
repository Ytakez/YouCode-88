// Webhook Telegram-бота: принимает /start <токен>, просит подтверждение и записывает Telegram ID в запрос входа.
import { createClient } from "npm:@supabase/supabase-js@2";

const admin = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
  auth: { persistSession: false, autoRefreshToken: false },
});
const BOT_TOKEN = Deno.env.get("TELEGRAM_BOT_TOKEN")!;
const SECRET = Deno.env.get("TELEGRAM_WEBHOOK_SECRET")!;
const TTL_MS = 10 * 60 * 1000;
const TABLE = "telegram_login_requests";
const TOKEN_RE = /^[0-9a-f]{48}$/;

async function tg(method: string, payload: Record<string, unknown>) {
  await fetch(`https://api.telegram.org/bot${BOT_TOKEN}/${method}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
}

const ok = () => new Response("ok");

Deno.serve(async (req) => {
  if (!SECRET || req.headers.get("x-telegram-bot-api-secret-token") !== SECRET) {
    return new Response("forbidden", { status: 403 });
  }
  const update = await req.json().catch(() => null);
  if (!update) return ok();

  const msg = update.message;
  if (msg?.chat?.type === "private" && typeof msg.text === "string") {
    const m = msg.text.match(/^\/start(?:@\w+)?\s+([0-9a-f]{48})$/);
    if (!m) {
      await tg("sendMessage", {
        chat_id: msg.chat.id,
        text: "Нажмите «Войти через Telegram» в приложении YouCode: бот откроется по ссылке оттуда.",
      });
      return ok();
    }
    const { data: row } = await admin.from(TABLE).select("*").eq("token", m[1]).maybeSingle();
    const alive = row && row.telegram_id == null && Date.now() - new Date(row.created_at).getTime() < TTL_MS;
    if (!alive) {
      await tg("sendMessage", { chat_id: msg.chat.id, text: "Ссылка устарела. Вернитесь в приложение и начните вход заново." });
      return ok();
    }
    await tg("sendMessage", {
      chat_id: msg.chat.id,
      text: "Вход в YouCode.\n\nПодтверждайте, только если вы сами только что нажали «Войти через Telegram» в приложении. Если эту ссылку вам прислал кто-то другой — не подтверждайте.",
      reply_markup: { inline_keyboard: [[{ text: "✅ Это я, войти", callback_data: `ok:${m[1]}` }]] },
    });
    return ok();
  }

  const cb = update.callback_query;
  if (cb && typeof cb.data === "string" && cb.data.startsWith("ok:") && cb.message?.chat?.type === "private") {
    const token = cb.data.slice(3);
    if (!TOKEN_RE.test(token)) return ok();
    const { data } = await admin.from(TABLE)
      .update({
        telegram_id: cb.from.id,
        telegram_username: cb.from.username ?? null,
        first_name: cb.from.first_name ?? null,
        confirmed_at: new Date().toISOString(),
      })
      .eq("token", token)
      .is("telegram_id", null)
      .gt("created_at", new Date(Date.now() - TTL_MS).toISOString())
      .select("token");
    const done = !!data && data.length > 0;
    await tg("answerCallbackQuery", { callback_query_id: cb.id });
    await tg("editMessageText", {
      chat_id: cb.message.chat.id,
      message_id: cb.message.message_id,
      text: done ? "Готово! Вернитесь в приложение YouCode." : "Ссылка устарела. Начните вход в приложении заново.",
    });
  }
  return ok();
});
