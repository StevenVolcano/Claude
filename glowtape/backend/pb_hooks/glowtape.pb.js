/// <reference path="../pb_data/types.d.ts" />
//
// Glow Tape server hooks: passwordless signup, join-by-code, default
// channels, and email mirroring so people who never open the app still get
// the schedule.

// --- Signup: create an account from just email + name. -----------------------
// The client then immediately requests an OTP code, so the random password is
// never used or seen. Idempotent: an existing email returns ok so the UI can
// always follow with requestOTP.
routerAdd("POST", "/api/glowtape/signup", (e) => {
  const data = new DynamicModel({ email: "", name: "" });
  e.bindBody(data);
  const email = data.email.trim().toLowerCase();
  if (!email.includes("@")) {
    throw new BadRequestError("Please enter a valid email address.");
  }

  try {
    e.app.findAuthRecordByEmail("users", email);
    return e.json(200, { ok: true, existing: true });
  } catch {
    // not found -> create
  }

  const users = e.app.findCollectionByNameOrId("users");
  const record = new Record(users);
  record.set("email", email);
  record.set("name", data.name.trim());
  record.set("verified", true); // possession of the emailed OTP code proves the address
  record.set("emailVisibility", true); // castmates need it on the contact sheet
  record.setRandomPassword();
  e.app.save(record);
  return e.json(200, { ok: true, existing: false });
});

// --- Join a production with its join code. -----------------------------------
routerAdd(
  "POST",
  "/api/glowtape/join",
  (e) => {
    const data = new DynamicModel({ code: "" });
    e.bindBody(data);
    const code = data.code.trim().toUpperCase();
    if (code.length < 4) {
      throw new BadRequestError("That join code doesn't look right.");
    }

    let production;
    try {
      production = e.app.findFirstRecordByFilter("productions", "joinCode = {:code}", { code });
    } catch {
      throw new BadRequestError("No production found for that code. Double-check it with your stage manager.");
    }

    try {
      e.app.findFirstRecordByFilter(
        "members",
        "production = {:p} && user = {:u}",
        { p: production.id, u: e.auth.id },
      );
      return e.json(200, { ok: true, production: production.id, already: true });
    } catch {
      // not a member yet -> create
    }

    const members = e.app.findCollectionByNameOrId("members");
    const member = new Record(members);
    member.set("production", production.id);
    member.set("user", e.auth.id);
    member.set("role", "performer"); // managers adjust roles afterwards
    e.app.save(member);
    return e.json(200, { ok: true, production: production.id, already: false });
  },
  $apis.requireAuth(),
);

// --- Default channels + join code when a production is created. --------------
onRecordAfterCreateSuccess((e) => {
  if (!e.record.get("joinCode")) {
    // Unambiguous alphabet: no 0/O or 1/I confusion on a printed handout.
    const code = $security.randomStringWithAlphabet(6, "ABCDEFGHJKMNPQRSTUVWXYZ23456789");
    e.record.set("joinCode", code);
    e.app.save(e.record);
  }

  const channels = e.app.findCollectionByNameOrId("channels");
  const defaults = [
    ["All Call", "all"],
    ["Cast", "cast"],
    ["Crew", "crew"],
    ["Production Team", "team"],
  ];
  for (const [name, audience] of defaults) {
    const c = new Record(channels);
    c.set("production", e.record.id);
    c.set("name", name);
    c.set("audience", audience);
    e.app.save(c);
  }
  e.next();
}, "productions");

// --- Email mirroring. ---------------------------------------------------------
// Every schedule item and announcement goes out by email too. Nothing in
// Glow Tape is app-only.

function glowtapeRecipients(app, productionId, calledMemberIds) {
  const members = app.findRecordsByFilter("members", "production = {:p}", "", 500, 0, { p: productionId });
  const recipients = [];
  for (const m of members) {
    if (calledMemberIds && calledMemberIds.length > 0 && !calledMemberIds.includes(m.id)) {
      continue;
    }
    try {
      const u = app.findRecordById("users", m.get("user"));
      recipients.push({ address: u.email(), name: u.get("name") });
    } catch {
      /* skip broken member rows */
    }
  }
  return recipients;
}

function glowtapeSendMail(app, recipients, subject, html) {
  if (recipients.length === 0) return;
  const settings = app.settings();
  const message = new MailerMessage({
    from: { address: settings.meta.senderAddress, name: settings.meta.senderName },
    bcc: recipients,
    subject: subject,
    html: html,
  });
  app.newMailClient().send(message);
}

onRecordAfterCreateSuccess((e) => {
  try {
    const production = e.app.findRecordById("productions", e.record.get("production"));
    const recipients = glowtapeRecipients(e.app, production.id, e.record.get("called"));
    const start = e.record.getDateTime("start");
    const lines = [
      `<h2>${e.record.get("title")}</h2>`,
      `<p><strong>${production.get("title")}</strong></p>`,
      `<p>When: ${start}</p>`,
      e.record.get("location") ? `<p>Where: ${e.record.get("location")}</p>` : "",
      e.record.get("calledNote") ? `<p>Called: ${e.record.get("calledNote")}</p>` : "",
      e.record.get("notes") ? `<p>${e.record.get("notes")}</p>` : "",
      `<p>Open Glow Tape to tap "Got it" so your stage manager knows you saw this.</p>`,
    ];
    glowtapeSendMail(
      e.app,
      recipients,
      `[${production.get("title")}] New on the schedule: ${e.record.get("title")}`,
      lines.join("\n"),
    );
  } catch (err) {
    e.app.logger().error("glowtape: event mail failed", "error", String(err));
  }
  e.next();
}, "events");

onRecordAfterCreateSuccess((e) => {
  try {
    const production = e.app.findRecordById("productions", e.record.get("production"));
    const recipients = glowtapeRecipients(e.app, production.id, null);
    glowtapeSendMail(
      e.app,
      recipients,
      `[${production.get("title")}] ${e.record.get("title")}`,
      `<h2>${e.record.get("title")}</h2><p>${e.record.get("body") || ""}</p><p>— ${production.get("title")} on Glow Tape</p>`,
    );
  } catch (err) {
    e.app.logger().error("glowtape: announcement mail failed", "error", String(err));
  }
  e.next();
}, "announcements");
