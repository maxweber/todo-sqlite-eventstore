# Todo app with an S3 event store and SQLite read model

A basic todo app that stores events in Tigris Data and uses SQLite for the
projected todos read model. Events are the source of truth; the todos table is
rebuilt from projections.

Mostly written by Claude Code.

## Why

Relational databases mix essential state with derived state. Imagine an Excel
spreadsheet where cells containing formulas do not update automatically, and
worse, they store the computed result instead of the formula itself. It's
immediately obvious that this is something you want to avoid.

Event sourcing separates the two. Events are immutable values, stored forever.
The read-model (todos table) is derived state. If you later discover that a
projection was wrong, you can delete the read-model and replay all events to
rebuild it in a single SQLite transaction.

Events also force you to assign a meaning to what happened. Transactions in
relational databases can be fairly arbitrary. External event streams, such as
those from [a billing
provider](https://developer.paddle.com/api-reference/events/list-events), make
this especially clear: you can build your own read-model and keep it up to date
simply by applying new events as they arrive.

## Architecture

### Event store

Events are appended as EDN commits to a Tigris Data bucket using the public
[`simplemono/eventstore`](https://github.com/simplemono/eventstore) library's
`simplemono.eventstore.s3` module. SQLite only stores derived read-model data.

Object keys use the configured stream prefix:

```text
{EVENT_STORE_PREFIX}/commits/{inverted-commit-number-019d}
```

Commit `0` is stored as `9223372036854775807`, commit `1` as
`9223372036854775806`, and so on. This makes the latest commit sort first in
LIST results. Requests include `X-Tigris-Consistent: true`.

Replay uses `simplemono.eventstore.s3-packs`, the optional pack-aware replay
store from the same library. Full 1000-commit packs live under:

```text
{EVENT_STORE_PREFIX}/packs/{inverted-pack-index-019d}
```

The app can create missing full packs from the REPL:

```clojure
(app.event-store/pack-completed-ranges!)
```

### Data-driven registration

Queries, commands, and projections are all registered as data maps in a single
register:

```clojure
;; in app.todo
(def register
  [{:query/kind     :query/todos      :query/fn #'query-todos}
   {:command/kind   :command/add-todo  :command/fn ...}
   {:projection/event-kind :todo/created  :projection/fn #'proj/todo-created}
   ...])
```

### Processing flow

1. Browser sends a command via `/command`
2. Command handler (pure function) returns `{:ok [events]}` or `{:error msg}`
3. Events are appended to the S3 event store
4. Projections update the SQLite read-model
5. Queries read from the projected todos table

### Replay

Rebuild the read-model from scratch at the REPL:

```clojure
(app.event-processor/replay-all-events! (app.db/get-ds))
```

This drops the todos table and replays every event through the projections,
with the read-model rebuild in one SQLite transaction. The replay path uses the
pack-aware event store and falls back to individual commit objects for the
unpacked tail.

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
