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
export interface PdsConfig {
    /** PDS HTTPS endpoint. Default: https://pds.etzhayyim.com */
    service?: string;
    /** Existing session JWTs (for re-use across calls). */
    session?: {
        did: string;
        accessJwt: string;
        refreshJwt: string;
        handle: string;
    };
}
/** Create a new account on the PDS. Returns the new DID + session. */
export declare function createAccount(opts: {
    service?: string;
    handle: string;
    email: string;
    password: string;
    inviteCode?: string;
}): Promise<{
    did: string;
    handle: string;
    accessJwt: string;
    refreshJwt: string;
}>;
/** Log in with an existing handle + password. */
export declare function login(opts: {
    service?: string;
    identifier: string;
    password: string;
}): Promise<{
    did: string;
    handle: string;
    accessJwt: string;
    refreshJwt: string;
}>;
/**
 * Get an authenticated AtpAgent ready for record operations.
 * Pass an existing session OR a handle/password pair to log in.
 */
export declare function getAgent(cfg: PdsConfig & ({
    handle: string;
    password: string;
} | {
    session: NonNullable<PdsConfig["session"]>;
})): Promise<AtpAgent>;
/**
 * Create an AT Record. Returns AT URI + CID.
 */
export declare function createRecord(agent: AtpAgent, did: string, collection: string, record: unknown, rkey?: string): Promise<{
    uri: string;
    cid: string;
}>;
/** Read a single record by AT URI components. */
export declare function getRecord(agent: AtpAgent, did: string, collection: string, rkey: string): Promise<{
    uri: string;
    cid: string;
    value: unknown;
} | null>;
/** List records in a collection with cursor pagination. */
export declare function listRecords(agent: AtpAgent, did: string, collection: string, opts?: {
    limit?: number;
    cursor?: string;
    reverse?: boolean;
}): Promise<{
    records: Array<{
        uri: string;
        cid: string;
        value: unknown;
    }>;
    cursor?: string;
}>;
/**
 * Quick PDS liveness check. Returns true if the configured service
 * responds 200 at /xrpc/_health.
 */
export declare function health(service?: string): Promise<boolean>;
/**
 * Resolve a DID to its PDS endpoint via DID document.
 * v0.1: handles did:web only (most common for etzhayyim setups).
 */
export declare function resolvePds(did: string): Promise<string>;
