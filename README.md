# Todo app with an S3 event store and SQLite read model

A basic todo app that stores events in Tigris Data and uses SQLite for the
projected todos read model. Events are the source of truth; the SQLite DB is
derived state.

## Why

Relational databases mix essential state with derived state. Imagine an Excel
spreadsheet where cells containing formulas do not update automatically, and
worse, they store the computed result instead of the formula itself. It's
immediately obvious that this is something you want to avoid.

Event sourcing separates the two. Events are immutable values, stored forever.
The read model is derived state. If a projection changes, build a new SQLite DB
from the event stream and switch to it when it is ready.

Events also force you to assign a meaning to what happened. Transactions in
relational databases can be fairly arbitrary. External event streams, such as
those from [a billing
provider](https://developer.paddle.com/api-reference/events/list-events), make
this especially clear: you can build your own read model and keep it up to date
simply by applying new events as they arrive.

## Architecture

### Event store

Events are appended as EDN commits to a Tigris Data bucket using
[`simplemono/eventstore`](https://github.com/simplemono/eventstore)'s
pack-aware `simplemono.eventstore.s3-packs` module.

Object keys use the configured stream prefix:

```text
{EVENT_STORE_PREFIX}/commits/{inverted-commit-number-019d}
```

Commit `0` is stored as `9223372036854775807`, commit `1` as
`9223372036854775806`, and so on. This makes the latest commit sort first in
LIST results. Requests include `X-Tigris-Consistent: true`.

The same EventStore value is used for appends and replay. It writes normal S3
commit objects and reads from immutable 1000-commit packs when packs exist,
falling back to commit objects for the unpacked tail. Missing full packs can be
created from the REPL:

```clojure
(app.event-store/pack-completed-ranges!)
```

### SQLite projections

SQLite projection tables are maintained by
[`simplemono/sqlite-event-projection`](https://github.com/simplemono/sqlite-event-projection).
The app catches SQLite up from the EventStore before queries and after command
writes. This keeps the read model current even when more than one process writes
to the same event stream.

The SQLite DB file includes the projection version:

```text
data/db-v1.db
```

When projection logic or schema changes, bump `app.db/projection-version` and
build a new DB file from the event stream instead of mutating the old one.

### Data-driven registration

Queries, commands, projection schema, and projection handlers are registered as
data maps:

```clojure
;; in app.todo
(def register
  [{:query/kind :query/todos
    :query/fn #'query-todos}
   {:command/kind :command/add-todo
    :command/fn ...}
   {:projection/create #'proj/create-todos}
   {:projection/event-kind :todo/created
    :projection/fn #'proj/todo-created}
   ...])
```

### Processing flow

1. Browser sends a command via `/command`
2. The app catches SQLite up from the EventStore
3. Command handler reads the SQLite read model and returns `{:ok [events]}` or
   `{:error msg}`
4. Events are appended to the S3 event store
5. The app catches SQLite up again so this process observes its own write
6. Queries catch SQLite up before reading from the projected todos table

### Rebuild

Build a new SQLite DB file from the event stream at the REPL:

```clojure
(require '[simplemono.sqlite-event-projection :as projection]
         '[app.db :as db]
         '[app.event-store :as event-store]
         '[app.system.register :as register])

(projection/build-db-file! {:event-store @event-store/store
                            :db/path "data/db-v2.db"
                            :projection/version 2
                            :projection/register (register/get-register)})
```

Switch the app to the new file by bumping `app.db/projection-version` and
restarting. Rollback means switching back to the old versioned DB file and
catching it up from the EventStore.

## Tigris Data configuration

Copy `.env.example` to `.env` and fill in your bucket details:

```sh
export EVENT_STORE_BUCKET=your-tigris-bucket
export EVENT_STORE_PREFIX=todo/dev
export EVENT_STORE_ENDPOINT=https://t3.storage.dev
export EVENT_STORE_REGION=auto
export EVENT_STORE_ACCESS_KEY_ID=your-access-key-id
export EVENT_STORE_SECRET_ACCESS_KEY=your-secret-access-key
```

`EVENT_STORE_PREFIX` scopes this app to one event stream inside the bucket.
Use a different prefix for each environment or tenant.

## Development

    bin/dev-start

Starts the JVM backend (nREPL on port 4000) and shadow-cljs (browser on port
3001).

## Licence

MIT
