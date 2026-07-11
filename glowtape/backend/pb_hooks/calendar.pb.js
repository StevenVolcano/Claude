/// <reference path="../pb_data/types.d.ts" />
//
// Personal iCalendar feed: each user gets a secret URL listing every event
// they're called for, across all their productions. Subscribing to it in
// Google Calendar / Apple Calendar / Outlook keeps their own calendar in
// sync automatically (Google refreshes subscribed feeds slowly — hours, not
// minutes; Apple is usually faster).

function icsEscape(text) {
  return String(text || "")
    .replace(/\\/g, "\\\\")
    .replace(/;/g, "\\;")
    .replace(/,/g, "\\,")
    .replace(/\r?\n/g, "\\n");
}

// "2026-07-15 19:00:00.000Z" -> "20260715T190000Z"
function icsDate(value) {
  const d = String(value);
  return (
    d.slice(0, 4) + d.slice(5, 7) + d.slice(8, 10) +
    "T" + d.slice(11, 13) + d.slice(14, 16) + d.slice(17, 19) + "Z"
  );
}

function icsDateFromMs(ms) {
  return icsDate(new Date(ms).toISOString().replace("T", " "));
}

// Get (or lazily create) the signed-in user's feed URL.
routerAdd(
  "GET",
  "/api/glowtape/calendar-url",
  (e) => {
    const user = e.app.findRecordById("users", e.auth.id);
    let token = user.get("calendarToken");
    if (!token) {
      token = $security.randomString(32);
      user.set("calendarToken", token);
      e.app.save(user);
    }
    let base = e.app.settings().meta.appURL || "";
    if (base.endsWith("/")) base = base.slice(0, -1);
    return e.json(200, { url: base + "/api/glowtape/cal/" + token + "/calls.ics" });
  },
  $apis.requireAuth(),
);

routerAdd("GET", "/api/glowtape/cal/{token}/calls.ics", (e) => {
  const token = e.request.pathValue("token");
  if (!token || token.length < 16) throw new NotFoundError();

  let user;
  try {
    user = e.app.findFirstRecordByFilter("users", "calendarToken = {:t}", { t: token });
  } catch {
    throw new NotFoundError();
  }

  const memberships = e.app.findRecordsByFilter("members", "user = {:u}", "", 100, 0, {
    u: user.id,
  });

  const weekAgo = new Date(Date.now() - 7 * 86400e3).toISOString().replace("T", " ");
  const lines = [
    "BEGIN:VCALENDAR",
    "VERSION:2.0",
    "PRODID:-//Glow Tape//Calls//EN",
    "CALSCALE:GREGORIAN",
    "METHOD:PUBLISH",
    "X-WR-CALNAME:" + icsEscape((user.get("name") || "My") + " — theater calls"),
  ];

  for (const m of memberships) {
    let production;
    try {
      production = e.app.findRecordById("productions", m.get("production"));
    } catch {
      continue;
    }
    const events = e.app.findRecordsByFilter(
      "events",
      "production = {:p} && start >= {:from}",
      "start",
      500,
      0,
      { p: production.id, from: weekAgo },
    );

    for (const ev of events) {
      const called = ev.get("called") || [];
      if (called.length > 0 && !called.includes(m.id)) continue;

      const start = String(ev.get("start"));
      const end = ev.get("end")
        ? String(ev.get("end"))
        : null;
      const desc = [ev.get("calledNote"), ev.get("notes")].filter(Boolean).join("\n");

      lines.push("BEGIN:VEVENT");
      lines.push("UID:evt-" + ev.id + "@glowtape");
      lines.push("DTSTAMP:" + icsDateFromMs(Date.now()));
      lines.push("DTSTART:" + icsDate(start));
      lines.push(
        "DTEND:" + (end ? icsDate(end) : icsDateFromMs(new Date(start.replace(" ", "T")).getTime() + 2 * 3600e3)),
      );
      lines.push("SUMMARY:" + icsEscape(ev.get("title") + " — " + production.get("title")));
      if (ev.get("location")) lines.push("LOCATION:" + icsEscape(ev.get("location")));
      if (desc) lines.push("DESCRIPTION:" + icsEscape(desc));
      lines.push("END:VEVENT");
    }
  }

  lines.push("END:VCALENDAR");
  const ics = lines.join("\r\n") + "\r\n";

  e.response.header().set("Content-Disposition", 'inline; filename="calls.ics"');
  return e.blob(200, "text/calendar; charset=utf-8", toBytes(ics));
});
