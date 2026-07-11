/// <reference path="../pb_data/types.d.ts" />
//
// Glow Tape SMS layer: phone verification, sign-in by text, and event
// reminders. Dormant until a provider is configured via environment
// variables — every send becomes a log line instead.
//
//   GLOWTAPE_SMS_PROVIDER = twilio | telnyx
//   TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN, TWILIO_FROM   (twilio)
//   TELNYX_API_KEY, TELNYX_FROM                          (telnyx)
//
// Safety rails: US numbers only, codes are hashed and expire in 10 minutes,
// max 3 codes per phone and 10 per IP per hour, 5 wrong guesses kills a
// code, sign-in codes are only ever sent to phones already verified on an
// account, and the phone/phoneVerified fields cannot be edited through the
// regular record API.

// --- provider adapter ---------------------------------------------------------

function smsConfigured() {
  const p = $os.getenv("GLOWTAPE_SMS_PROVIDER");
  return p === "twilio" || p === "telnyx";
}

function sendSmsReal(app, to, body) {
  const provider = $os.getenv("GLOWTAPE_SMS_PROVIDER");
  if (!smsConfigured()) {
    app.logger().info("glowtape sms (dormant, not sent)", "to", to, "body", body);
    return false;
  }
  try {
    if (provider === "twilio") {
      const sid = $os.getenv("TWILIO_ACCOUNT_SID");
      const token = $os.getenv("TWILIO_AUTH_TOKEN");
      const from = $os.getenv("TWILIO_FROM");
      const auth = $security.base64Encode(sid + ":" + token);
      const form =
        "To=" + encodeURIComponent(to) +
        "&From=" + encodeURIComponent(from) +
        "&Body=" + encodeURIComponent(body);
      const res = $http.send({
        url: "https://api.twilio.com/2010-04-01/Accounts/" + sid + "/Messages.json",
        method: "POST",
        headers: {
          "Content-Type": "application/x-www-form-urlencoded",
          Authorization: "Basic " + auth,
        },
        body: form,
        timeout: 15,
      });
      if (res.statusCode >= 300) {
        app.logger().error("glowtape sms: twilio error", "status", res.statusCode, "body", res.raw);
        return false;
      }
      return true;
    }
    if (provider === "telnyx") {
      const res = $http.send({
        url: "https://api.telnyx.com/v2/messages",
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: "Bearer " + $os.getenv("TELNYX_API_KEY"),
        },
        body: JSON.stringify({ from: $os.getenv("TELNYX_FROM"), to: to, text: body }),
        timeout: 15,
      });
      if (res.statusCode >= 300) {
        app.logger().error("glowtape sms: telnyx error", "status", res.statusCode, "body", res.raw);
        return false;
      }
      return true;
    }
  } catch (err) {
    app.logger().error("glowtape sms: send failed", "error", String(err));
  }
  return false;
}

// --- helpers ------------------------------------------------------------------

// US numbers only -> +1XXXXXXXXXX, or null.
function normalizeUsPhone(raw) {
  const digits = String(raw || "").replace(/\D/g, "");
  if (digits.length === 10) return "+1" + digits;
  if (digits.length === 11 && digits[0] === "1") return "+" + digits;
  return null;
}

function hashCode(phone, code) {
  return $security.sha256(phone + ":" + code);
}

function pbNow(offsetMs) {
  return new Date(Date.now() + (offsetMs || 0)).toISOString().replace("T", " ");
}

function assertRateLimit(app, phone, ip) {
  const hourAgo = pbNow(-60 * 60 * 1000);
  const byPhone = app.findRecordsByFilter(
    "phone_codes",
    "phone = {:phone} && created >= {:t}",
    "",
    10,
    0,
    { phone, t: hourAgo },
  );
  if (byPhone.length >= 3) {
    throw new BadRequestError("Too many codes requested for this number. Try again in an hour.");
  }
  if (ip) {
    const byIp = app.findRecordsByFilter("phone_codes", "ip = {:ip} && created >= {:t}", "", 20, 0, {
      ip,
      t: hourAgo,
    });
    if (byIp.length >= 10) {
      throw new BadRequestError("Too many codes requested. Try again in an hour.");
    }
  }
}

function createAndSendCode(app, phone, purpose, userId, ip) {
  const code = $security.randomStringWithAlphabet(6, "0123456789");
  const col = app.findCollectionByNameOrId("phone_codes");
  const rec = new Record(col);
  rec.set("phone", phone);
  rec.set("codeHash", hashCode(phone, code));
  rec.set("purpose", purpose);
  if (userId) rec.set("user", userId);
  rec.set("ip", ip || "");
  rec.set("expires", pbNow(10 * 60 * 1000));
  rec.set("attempts", 0);
  app.save(rec);
  sendSmsReal(app, phone, "Glow Tape code: " + code + ". It expires in 10 minutes.");
}

function consumeCode(app, phone, purpose, code) {
  const candidates = app.findRecordsByFilter(
    "phone_codes",
    "phone = {:phone} && purpose = {:purpose} && expires >= {:now}",
    "-created",
    1,
    0,
    { phone, purpose, now: pbNow(0) },
  );
  if (candidates.length === 0) {
    throw new BadRequestError("That code expired. Request a new one.");
  }
  const rec = candidates[0];
  const attempts = (rec.getInt("attempts") || 0) + 1;
  if (attempts > 5) {
    app.delete(rec);
    throw new BadRequestError("Too many tries. Request a new code.");
  }
  if (rec.get("codeHash") !== hashCode(phone, String(code || "").trim())) {
    rec.set("attempts", attempts);
    app.save(rec);
    throw new BadRequestError("That code didn't match.");
  }
  const userId = rec.get("user");
  app.delete(rec);
  return userId;
}

// --- routes -------------------------------------------------------------------

// Attach/verify a phone on the signed-in account.
routerAdd(
  "POST",
  "/api/glowtape/phone/start",
  (e) => {
    const data = new DynamicModel({ phone: "" });
    e.bindBody(data);
    const phone = normalizeUsPhone(data.phone);
    if (!phone) throw new BadRequestError("Enter a 10-digit US phone number.");
    assertRateLimit(e.app, phone, e.realIP());
    createAndSendCode(e.app, phone, "verify", e.auth.id, e.realIP());
    return e.json(200, { ok: true });
  },
  $apis.requireAuth(),
);

routerAdd(
  "POST",
  "/api/glowtape/phone/confirm",
  (e) => {
    const data = new DynamicModel({ phone: "", code: "" });
    e.bindBody(data);
    const phone = normalizeUsPhone(data.phone);
    if (!phone) throw new BadRequestError("Enter a 10-digit US phone number.");
    const userId = consumeCode(e.app, phone, "verify", data.code);
    if (userId !== e.auth.id) throw new BadRequestError("That code didn't match.");
    const user = e.app.findRecordById("users", e.auth.id);
    user.set("phone", phone);
    user.set("phoneVerified", true);
    user.set("smsOptIn", true);
    e.app.save(user);
    return e.json(200, { ok: true });
  },
  $apis.requireAuth(),
);

// Sign in by text. Codes are only sent to phones already verified on an
// account; unknown numbers get the same "ok" so the endpoint doesn't leak
// which numbers are registered (and can't be used to text strangers).
routerAdd("POST", "/api/glowtape/signin-sms/start", (e) => {
  const data = new DynamicModel({ phone: "" });
  e.bindBody(data);
  const phone = normalizeUsPhone(data.phone);
  if (!phone) throw new BadRequestError("Enter a 10-digit US phone number.");
  assertRateLimit(e.app, phone, e.realIP());
  try {
    const user = e.app.findFirstRecordByFilter(
      "users",
      "phone = {:phone} && phoneVerified = true",
      { phone },
    );
    createAndSendCode(e.app, phone, "signin", user.id, e.realIP());
  } catch {
    // no matching account: swallow silently
  }
  return e.json(200, { ok: true });
});

routerAdd("POST", "/api/glowtape/signin-sms/confirm", (e) => {
  const data = new DynamicModel({ phone: "", code: "" });
  e.bindBody(data);
  const phone = normalizeUsPhone(data.phone);
  if (!phone) throw new BadRequestError("Enter a 10-digit US phone number.");
  const userId = consumeCode(e.app, phone, "signin", data.code);
  if (!userId) throw new BadRequestError("That code didn't match.");
  const user = e.app.findRecordById("users", userId);
  return $apis.recordAuthResponse(e, user, "phone");
});

// --- guard: phone fields only change through the verified flow -----------------

onRecordUpdateRequest((e) => {
  if (!e.hasSuperuserAuth()) {
    const original = e.record.original();
    for (const f of ["phone", "phoneVerified"]) {
      if (e.record.get(f) !== original.get(f)) {
        throw new BadRequestError("Phone numbers are changed via the verification flow.");
      }
    }
  }
  e.next();
}, "users");

// --- reminder cron --------------------------------------------------------------
// Every 10 minutes: text people called for events ~10h out and ~2h out.
// reminders_sent dedupes across runs. Windows are 2h+ wide so a 10-minute
// cron can't skip past one.

cronAdd("glowtape_sms_reminders", "*/10 * * * *", () => {
  if (!smsConfigured()) return;

  const windows = [
    { kind: "10h", fromMs: 8 * 3600e3, toMs: 10 * 3600e3, word: "today" },
    { kind: "2h", fromMs: 0, toMs: 2.5 * 3600e3, word: "soon" },
  ];

  for (const w of windows) {
    const events = $app.findRecordsByFilter(
      "events",
      "start >= {:from} && start <= {:to}",
      "start",
      200,
      0,
      { from: pbNow(w.fromMs), to: pbNow(w.toMs) },
    );

    for (const event of events) {
      let production;
      try {
        production = $app.findRecordById("productions", event.get("production"));
      } catch {
        continue;
      }
      const called = event.get("called") || [];
      const members = $app.findRecordsByFilter(
        "members",
        "production = {:p}",
        "",
        500,
        0,
        { p: production.id },
      );

      for (const m of members) {
        if (called.length > 0 && !called.includes(m.id)) continue;
        let user;
        try {
          user = $app.findRecordById("users", m.get("user"));
        } catch {
          continue;
        }
        if (!user.get("smsOptIn") || !user.get("phoneVerified") || !user.get("phone")) continue;

        try {
          // unique index makes double-sends impossible even if two runs race
          const col = $app.findCollectionByNameOrId("reminders_sent");
          const marker = new Record(col);
          marker.set("event", event.id);
          marker.set("user", user.id);
          marker.set("kind", w.kind);
          $app.save(marker);
        } catch {
          continue; // already reminded
        }

        const when = formatPacific(event.get("start"));
        const loc = event.get("location") ? " at " + event.get("location") : "";
        sendSmsReal(
          $app,
          user.get("phone"),
          "Glow Tape: " +
            event.get("title") +
            " (" + production.get("title") + ") is " + w.word + " — " +
            when + loc + ". Reply STOP to opt out.",
        );
      }
    }
  }

  // housekeeping: drop expired codes
  const stale = $app.findRecordsByFilter("phone_codes", "expires < {:now}", "", 200, 0, {
    now: pbNow(0),
  });
  for (const s of stale) $app.delete(s);
});

// Format a UTC datetime for Grays Harbor (America/Los_Angeles) without Intl,
// which the JSVM lacks. DST: second Sunday of March to first Sunday of November.
function formatPacific(value) {
  const utc = new Date(String(value).replace(" ", "T"));

  const nthSunday = (year, month, n) => {
    const first = new Date(Date.UTC(year, month, 1));
    const offset = (7 - first.getUTCDay()) % 7;
    return 1 + offset + (n - 1) * 7;
  };
  const y = utc.getUTCFullYear();
  const dstStart = Date.UTC(y, 2, nthSunday(y, 2, 2), 10); // 2am PT = 10:00 UTC
  const dstEnd = Date.UTC(y, 10, nthSunday(y, 10, 1), 9); // 2am PDT = 09:00 UTC
  const t = utc.getTime();
  const offsetHours = t >= dstStart && t < dstEnd ? 7 : 8;

  const local = new Date(t - offsetHours * 3600e3);
  const days = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
  const months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
  let h = local.getUTCHours();
  const ampm = h >= 12 ? "pm" : "am";
  h = h % 12 || 12;
  const min = String(local.getUTCMinutes()).padStart(2, "0");
  return (
    days[local.getUTCDay()] + " " + months[local.getUTCMonth()] + " " + local.getUTCDate() +
    ", " + h + ":" + min + ampm
  );
}
