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

## Development

```bash
npm install
npm run build
```

## License

Apache 2.0 + Charter Compliance Rider v3.6 (`/CHARTER-RIDER.md`).
