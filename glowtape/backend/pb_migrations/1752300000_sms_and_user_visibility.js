/// <reference path="../pb_data/types.d.ts" />
//
// SMS layer + user visibility.
//
// - users gain smsOptIn/phoneVerified and become viewable by any signed-in
//   user (the contact sheet expands members -> user; PocketBase's default
//   "own record only" rule would return empty expands for castmates).
//   Scoping visibility to shared-production members is finer-grained than
//   rules can express cheaply; any-authenticated is acceptable for a closed
//   community pilot.
// - phone_codes and reminders_sent are server-internal (all rules null =
//   superuser/hooks only).

migrate(
  (app) => {
    const users = app.findCollectionByNameOrId("users");
    users.fields.add(new Field({ name: "smsOptIn", type: "bool" }));
    users.fields.add(new Field({ name: "phoneVerified", type: "bool" }));
    users.listRule = "@request.auth.id != ''";
    users.viewRule = "@request.auth.id != ''";
    app.save(users);

    const events = app.findCollectionByNameOrId("events");

    const phoneCodes = new Collection({
      type: "base",
      name: "phone_codes",
      fields: [
        { type: "text", name: "phone", required: true, max: 20 },
        { type: "text", name: "codeHash", required: true, max: 100 },
        { type: "select", name: "purpose", values: ["verify", "signin"], required: true, maxSelect: 1 },
        { type: "relation", name: "user", collectionId: users.id, maxSelect: 1, cascadeDelete: true },
        { type: "text", name: "ip", max: 60 },
        { type: "date", name: "expires", required: true },
        { type: "number", name: "attempts" },
        { type: "autodate", name: "created", onCreate: true },
      ],
      listRule: null,
      viewRule: null,
      createRule: null,
      updateRule: null,
      deleteRule: null,
    });
    app.save(phoneCodes);

    const remindersSent = new Collection({
      type: "base",
      name: "reminders_sent",
      fields: [
        { type: "relation", name: "event", collectionId: events.id, required: true, maxSelect: 1, cascadeDelete: true },
        { type: "relation", name: "user", collectionId: users.id, required: true, maxSelect: 1, cascadeDelete: true },
        { type: "select", name: "kind", values: ["10h", "2h"], required: true, maxSelect: 1 },
        { type: "autodate", name: "created", onCreate: true },
      ],
      indexes: [
        "CREATE UNIQUE INDEX idx_reminders_sent_unique ON reminders_sent (event, user, kind)",
      ],
      listRule: null,
      viewRule: null,
      createRule: null,
      updateRule: null,
      deleteRule: null,
    });
    app.save(remindersSent);
  },
  (app) => {
    for (const name of ["reminders_sent", "phone_codes"]) {
      const c = app.findCollectionByNameOrId(name);
      if (c) app.delete(c);
    }
    const users = app.findCollectionByNameOrId("users");
    users.fields.removeByName("smsOptIn");
    users.fields.removeByName("phoneVerified");
    users.listRule = "id = @request.auth.id";
    users.viewRule = "id = @request.auth.id";
    app.save(users);
  },
);
