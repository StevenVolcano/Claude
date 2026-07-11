/// <reference path="../pb_data/types.d.ts" />
//
// Per-user secret token for the personal iCalendar feed. Hidden so it never
// appears in API responses (users are viewable by any signed-in user); the
// owner retrieves their feed URL via the /api/glowtape/calendar-url route.

migrate(
  (app) => {
    const users = app.findCollectionByNameOrId("users");
    users.fields.add(new Field({ name: "calendarToken", type: "text", max: 64, hidden: true }));
    app.save(users);
  },
  (app) => {
    const users = app.findCollectionByNameOrId("users");
    users.fields.removeByName("calendarToken");
    app.save(users);
  },
);
