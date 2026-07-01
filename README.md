# atproto-client

`kotoba.lang.atproto-client` — AT Protocol client facade (XRPC + PDS session /
record helpers) as a **pure portable Clojure (`.cljc`) core over an injected
`IHttp` transport**.

The library performs **zero network I/O**: every XRPC call and DID-document
fetch goes through a host-supplied `kotoba.lang.atproto-client.atproto/IHttp`
(`-request`). The host backs it with `java.net` / `babashka.http-client` (JVM)
or `fetch` (CLJS/WASM). This is the kotoba-lang layer contract — pure `.cljc`,
zero network I/O, zero vendor SDK (ADR-2606302300 §Step-1). The original
`@atproto/api` vendor SDK has no place inside the library; callers inject an
`IHttp` that may itself wrap `@atproto/api` on the host side.

| Group | Functions |
|---|---|
| agent / xrpc | `create-agent`, `xrpc` (atproto) |
| session | `create-account`, `login`, `get-agent` (pds) |
| records | `create-record`, `get-record`, `list-records` (pds) |
| liveness / did | `health`, `resolve-pds` (did:web) (pds) |

The "agent" is an immutable map `{:service :http :headers :session?}`. JVM is
synchronous; CLJS returns `js/Promise` (mirroring the original async TS).

## Provenance

Relocated 2026-07-01 from `etzhayyim/root:20-actors/etzhayyim-sdk/src/{atproto,pds}.ts`
to `kotoba-lang/atproto-client`. Originally relocated as TypeScript (a physical
move), then ported to `.cljc`. The TypeScript has been **deleted** — the `.cljc`
core is the single canonical implementation.

## Develop

```bash
clojure -M:lint     # clj-kondo (errors fail)
clojure -M:test     # mock-PDS (JDK HttpServer) + injected babashka IHttp
```
