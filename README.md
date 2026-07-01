# atproto-client

`@etzhayyim/atproto-client` — AT Protocol client facade (`createAgent`/
`xrpc`) + PDS read/write helpers over `@atproto/api`.

| Module | What it does |
|---|---|
| `atproto.ts` | `AtpAgent`/`AtpBaseClient` + Bluesky lexicon type re-exports, `createAgent(opts)` (header-injecting agent factory), `xrpc(agent, method, nsid, body, opts)` (generic XRPC call helper) |
| `pds.ts` | `createAccount`/`login`/`getAgent`/`createRecord`/`getRecord`/`listRecords`/`health`/`resolvePds` (did:web only) — PDS session + record read/write |

Zero business-logic coupling beyond one default value
(`DEFAULT_PDS = "https://pds.etzhayyim.com"`) and error-message prefixes —
every service endpoint, DID, and collection is a plain parameter.

## Not the same thing as `kotoba-lang/atproto`

`kotoba-lang/atproto` is a **CLJC-only protocol-vocabulary library**
(`src/atproto/{core,sdk}.cljc`) — pure data functions (`did?`, `repo-uri`/
`parse-uri`, `xrpc-url`, a static NSID→HTTP-method route table) with
**zero HTTP/network code, no `@atproto/api` dependency, no session/auth
handling**. Its own roadmap (`resources/atproto/sdk_coverage.edn`) marks
its `:client` and `:server` features `:planned` — this package (verified
via investigation before relocating) is not a duplicate of anything that
exists there today; it's the actual executing HTTP client that repo does
not (yet) provide. If `kotoba-lang/atproto` ever grows a real TS or CLJC
client, reconciling the two is a separate future decision — this package
is not blocked on that happening.

## Provenance

Relocated 2026-07-01 from `etzhayyim/root:20-actors/etzhayyim-sdk/src/
{atproto,pds}.ts` to `kotoba-lang/atproto-client` per the org-taxonomy
library-placement rule (ADR-2606302300). Design authority remains
ADR-2605172000, in `etzhayyim/root`.

Unlike most of this session's other relocations, this one has **live
TypeScript consumers** outside `etzhayyim-sdk` itself:
`60-apps/etzhayyim-project-hrse` (imports `AtpBaseClient` via
`@etzhayyim/sdk/atproto`) and `60-apps/open-otology-uhl-r` (imports and
calls `getAgent`/`createRecord` via `@etzhayyim/sdk/pds` to write audit
records) — plus the `substrate-boundary` lint rule's guidance text.
`etzhayyim-sdk`'s own `src/{atproto,pds}.ts` become re-export shims so all
of those keep resolving unchanged; `src/pay.ts` (which imports
`createRecord`/`PdsConfig` from `./pds.js`) needed no changes at all,
since it consumes the shim the same way.

This is a **physical move only** (TypeScript unchanged) — no dedicated
unit tests existed for these two files in `etzhayyim-sdk` to bring along
(only a shared `fake-pds.mjs` test fixture used by other, unrelated test
files, which stays put), and a CLJC port is deferred to a later, separate
task.

**`dist/` is committed** (see `kotoba-lang/pqh`'s README for the rationale).

## Clojure/CLJC port

`src/kotoba/lang/atproto_client/{atproto,pds}.cljc` port the narrow,
*executing* facade surface above — not a general `@atproto/api`
reimplementation. Ported 1:1 (function-for-function, same default value,
same error-message shape):

| TS (`atproto.ts` / `pds.ts`) | CLJC (`kotoba.lang.atproto-client.{atproto,pds}`) |
|---|---|
| `createAgent(opts)` | `atproto/create-agent` |
| `xrpc(agent, method, nsid, body?, opts?)` | `atproto/xrpc` (3/4/5-arity) |
| `createAccount(opts)` | `pds/create-account` |
| `login(opts)` | `pds/login` |
| `getAgent(cfg)` | `pds/get-agent` |
| `createRecord(agent, did, collection, record, rkey?)` | `pds/create-record` (4/5-arity) |
| `getRecord(agent, did, collection, rkey)` | `pds/get-record` |
| `listRecords(agent, did, collection, opts?)` | `pds/list-records` (3/4-arity) |
| `health(service?)` | `pds/health` (0/1-arity) |
| `resolvePds(did)` (did:web only) | `pds/resolve-pds` |

**Not ported** (deliberately, per the package's own narrow scope): the
`AtpAgent`/`AtpBaseClient` class re-export and the Bluesky lexicon *type*
re-exports (`AppBskyActorDefs` etc.) — both are TypeScript-only concepts
(a class instance to wrap, and compile-time types) with no Clojure
equivalent; a plain Clojure map already plays the "typed record" role
lexicon types played in TS call sites.

**Architecture**: since this port has no generated typed client (unlike
`@atproto/api`'s `agent.com.atproto.repo.createRecord` etc.), `pds.cljc`'s
session/record functions are expressed directly as `atproto.cljc`'s
generic `xrpc` calls against their underlying NSIDs — e.g. `login` issues
`POST com.atproto.server.createSession`, `get-agent`'s session-resume path
issues `GET com.atproto.server.getSession` to verify, etc. (see each
function's docstring for its NSID). The "agent" is a plain immutable map
`{:service <url> :headers {...} :session? {:did :handle :accessJwt
:refreshJwt}}` rather than a stateful class instance; request/response
maps keep the *same camelCase keys as the wire JSON* (`:accessJwt`,
`:inviteCode`, ...) rather than kebab-casing them, to avoid inventing a
naming-translation layer over a straight wire-protocol facade.

**Scoped out**: the original `AtpAgent` transparently retries a request
once against `com.atproto.server.refreshSession` on an expired-token
response; this port does not reimplement that automatic-refresh retry
loop — callers needing long-lived sessions should re-`login`/re-`get-agent`
on auth failure themselves. `resolve-pds` also carries over a real,
pre-existing limitation of the original TS 1:1 rather than silently
"fixing" it: it never percent-decodes the did:web string, so a
did:web identifying a PDS host by `IP:port` isn't resolvable through it
(only bare-domain / domain+path-segment did:web identifiers work) — this
matches upstream's own "v0.1 = did:web only" scope note, not a new gap
introduced by the port.

**Did not build on `kotoba-lang/atproto`**: that sibling repo (a
CLJC-only protocol-vocabulary library — `did?`/`repo-uri`/`xrpc-url`/a
static NSID route table, zero HTTP code) wasn't checked out locally in
this working copy when this port was written, and its `:client`/`:server`
roadmap items are marked `:planned` (not present to build on today).
Depending on it would also mean a cross-repo `:local/root` dep whose path
assumptions don't hold for every clone layout. This port is fully
self-contained instead (its own tiny `xrpc-url`/query-string helpers);
reconciling the two, if `kotoba-lang/atproto` ever grows a real client, is
a separate future decision (same stance as this README's "Not the same
thing" section above).

**CLJS scope**: written as full `.cljc` (JVM babashka.http-client+cheshire
branch + CLJS browser-`fetch` branch), following `kotoba-lang/ipfs`'s
precedent, even though this package's two known live TS consumers
(`etzhayyim-project-hrse`'s `server-client.ts` — an explicitly
server-side Next.js Connect client using Clerk *server* auth — and
`open-otology-uhl-r/worker/src/app.ts` — a Cloudflare Worker AppView
backend calling `getAgent`/`createRecord` for audit writes) are both
server-side, not browser. Unlike `witness-quorum`'s JVM-only call (that
package has server-exclusive concerns baked into its design), this
facade's `xrpc`/`create-agent` surface is a thin, credential-parameterized
fetch wrapper with nothing in it that assumes a deployment context, and
the marginal cost of the CLJS branch is low (plain `fetch`, no extra
deps) — so it's kept in for architectural symmetry with `kotoba-lang/ipfs`
and to leave the door open for a future browser-side kotoba consumer,
even though no such consumer exists today.

Tests: `test/kotoba/lang/atproto_client/pds_test.clj` (plain `.clj`,
JVM-only — needs `com.sun.net.httpserver.HttpServer`, part of the JDK,
not on babashka's restricted class whitelist) spin up a real,
dependency-free mock PDS server mirroring the XRPC wire shapes of this
repo's own `test/fake-pds.mjs` (`getSession`/`createRecord`/`listRecords`)
plus the additional endpoints this port also exercises
(`createSession`/`createAccount`/`_health`/`.well-known/did.json`) that
`fake-pds.mjs` doesn't implement. 20 tests / 36 assertions covering
`create-agent`/`xrpc` (GET+POST, header injection + override, non-2xx
throw), `login`/`create-account`/`get-agent` (both handle+password and
session-resume paths), `create-record`/`get-record`/`list-records`
(including not-found → nil and limit/reverse), `health` (live + timeout),
and `resolve-pds` (success, no-service-entry, unreachable,
unsupported-method) — the last of these via a documented `alter-var-root`
seam on the private `fetch-json` helper, since `resolve-pds` hardcodes an
`https://` scheme this plain-HTTP JDK mock can't serve directly (see the
test file's comments for why `with-redefs` doesn't work here). Run with:

```bash
clj-kondo --lint src test
clojure -M:test
```

The TypeScript implementation (`src/{atproto,pds}.ts` + committed
`dist/`) is kept as-is: it remains the npm-consumable artifact the two
live consumers above depend on. New Clojure/babashka/CLJS consumers
should use the CLJC namespaces; existing Node/TS consumers are
unaffected.

## Development

```bash
npm install
npm run build
```

## License

Apache 2.0 + Charter Compliance Rider v3.6 (`/CHARTER-RIDER.md`).
