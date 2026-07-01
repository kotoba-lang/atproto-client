/**
 * @etzhayyim/sdk/pds — PDS write/read helpers.
 *
 * v0.1.0: real @atproto/api wiring. Targets pds.etzhayyim.com by default.
 *
 * Per ADR-2605172000 — apps under etzhayyim/root MUST go through this
 * seam for PDS access; direct @atproto/api imports from app code are
 * prohibited by the substrate hard rules.
 */
import { AtpAgent } from "@atproto/api";
const DEFAULT_PDS = "https://pds.etzhayyim.com";
/** Create a new account on the PDS. Returns the new DID + session. */
export async function createAccount(opts) {
    const agent = new AtpAgent({ service: opts.service ?? DEFAULT_PDS });
    const res = await agent.com.atproto.server.createAccount({
        handle: opts.handle,
        email: opts.email,
        password: opts.password,
        inviteCode: opts.inviteCode,
    });
    if (!res.success) {
        throw new Error(`[etzhayyim-sdk/pds] createAccount failed: ${JSON.stringify(res)}`);
    }
    return {
        did: res.data.did,
        handle: res.data.handle,
        accessJwt: res.data.accessJwt,
        refreshJwt: res.data.refreshJwt,
    };
}
/** Log in with an existing handle + password. */
export async function login(opts) {
    const agent = new AtpAgent({ service: opts.service ?? DEFAULT_PDS });
    const res = await agent.login({
        identifier: opts.identifier,
        password: opts.password,
    });
    if (!res.success) {
        throw new Error(`[etzhayyim-sdk/pds] login failed: ${JSON.stringify(res)}`);
    }
    return {
        did: res.data.did,
        handle: res.data.handle,
        accessJwt: res.data.accessJwt,
        refreshJwt: res.data.refreshJwt,
    };
}
/**
 * Get an authenticated AtpAgent ready for record operations.
 * Pass an existing session OR a handle/password pair to log in.
 */
export async function getAgent(cfg) {
    const agent = new AtpAgent({ service: cfg.service ?? DEFAULT_PDS });
    if ("session" in cfg && cfg.session) {
        await agent.resumeSession({
            did: cfg.session.did,
            handle: cfg.session.handle,
            accessJwt: cfg.session.accessJwt,
            refreshJwt: cfg.session.refreshJwt,
            active: true,
        });
    }
    else if ("handle" in cfg && "password" in cfg) {
        await agent.login({
            identifier: cfg.handle,
            password: cfg.password,
        });
    }
    return agent;
}
/**
 * Create an AT Record. Returns AT URI + CID.
 */
export async function createRecord(agent, did, collection, record, rkey) {
    const res = await agent.com.atproto.repo.createRecord({
        repo: did,
        collection,
        rkey,
        // PDS validator requires $type matching the collection NSID
        record: { $type: collection, ...record },
    });
    if (!res.success) {
        throw new Error(`[etzhayyim-sdk/pds] createRecord failed: ${JSON.stringify(res)}`);
    }
    return { uri: res.data.uri, cid: res.data.cid };
}
/** Read a single record by AT URI components. */
export async function getRecord(agent, did, collection, rkey) {
    try {
        const res = await agent.com.atproto.repo.getRecord({
            repo: did,
            collection,
            rkey,
        });
        return res.success
            ? { uri: res.data.uri, cid: res.data.cid, value: res.data.value }
            : null;
    }
    catch (err) {
        const msg = err?.message ?? "";
        if (msg.includes("not found") || msg.includes("Record not found")) {
            return null;
        }
        throw err;
    }
}
/** List records in a collection with cursor pagination. */
export async function listRecords(agent, did, collection, opts = {}) {
    const res = await agent.com.atproto.repo.listRecords({
        repo: did,
        collection,
        limit: opts.limit ?? 50,
        cursor: opts.cursor,
        reverse: opts.reverse,
    });
    if (!res.success) {
        throw new Error(`[etzhayyim-sdk/pds] listRecords failed: ${JSON.stringify(res)}`);
    }
    return {
        records: res.data.records.map((r) => ({
            uri: r.uri,
            cid: r.cid,
            value: r.value,
        })),
        cursor: res.data.cursor,
    };
}
/**
 * Quick PDS liveness check. Returns true if the configured service
 * responds 200 at /xrpc/_health.
 */
export async function health(service) {
    const url = `${(service ?? DEFAULT_PDS).replace(/\/+$/, "")}/xrpc/_health`;
    try {
        const res = await fetch(url, { method: "GET" });
        return res.ok;
    }
    catch {
        return false;
    }
}
/**
 * Resolve a DID to its PDS endpoint via DID document.
 * v0.1: handles did:web only (most common for etzhayyim setups).
 */
export async function resolvePds(did) {
    if (did.startsWith("did:web:")) {
        const host = did.slice("did:web:".length).replace(/:/g, "/");
        const url = `https://${host}/.well-known/did.json`;
        const res = await fetch(url);
        if (!res.ok) {
            throw new Error(`[etzhayyim-sdk/pds] resolvePds: did.json not reachable at ${url}`);
        }
        const doc = (await res.json());
        const pds = doc.service?.find((s) => s.type === "AtprotoPersonalDataServer" ||
            s.id === "#atproto_pds" ||
            s.id === `${did}#atproto_pds`);
        if (!pds) {
            throw new Error(`[etzhayyim-sdk/pds] resolvePds: no AtprotoPersonalDataServer service entry in ${url}`);
        }
        return pds.serviceEndpoint;
    }
    throw new Error(`[etzhayyim-sdk/pds] resolvePds: did method not yet supported: ${did} (v0.1 = did:web only; did:plc next)`);
}
