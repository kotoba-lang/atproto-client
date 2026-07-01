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

const DEFAULT_PDS = "https://pds.etzhayyim.com";

/** Create a new account on the PDS. Returns the new DID + session. */
export async function createAccount(opts: {
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
}> {
  const agent = new AtpAgent({ service: opts.service ?? DEFAULT_PDS });
  const res = await agent.com.atproto.server.createAccount({
    handle: opts.handle,
    email: opts.email,
    password: opts.password,
    inviteCode: opts.inviteCode,
  });
  if (!res.success) {
    throw new Error(
      `[etzhayyim-sdk/pds] createAccount failed: ${JSON.stringify(res)}`
    );
  }
  return {
    did: res.data.did,
    handle: res.data.handle,
    accessJwt: res.data.accessJwt,
    refreshJwt: res.data.refreshJwt,
  };
}

/** Log in with an existing handle + password. */
export async function login(opts: {
  service?: string;
  identifier: string;
  password: string;
}): Promise<{
  did: string;
  handle: string;
  accessJwt: string;
  refreshJwt: string;
}> {
  const agent = new AtpAgent({ service: opts.service ?? DEFAULT_PDS });
  const res = await agent.login({
    identifier: opts.identifier,
    password: opts.password,
  });
  if (!res.success) {
    throw new Error(
      `[etzhayyim-sdk/pds] login failed: ${JSON.stringify(res)}`
    );
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
export async function getAgent(
  cfg: PdsConfig &
    (
      | { handle: string; password: string }
      | { session: NonNullable<PdsConfig["session"]> }
    )
): Promise<AtpAgent> {
  const agent = new AtpAgent({ service: cfg.service ?? DEFAULT_PDS });
  if ("session" in cfg && cfg.session) {
    await agent.resumeSession({
      did: cfg.session.did,
      handle: cfg.session.handle,
      accessJwt: cfg.session.accessJwt,
      refreshJwt: cfg.session.refreshJwt,
      active: true,
    });
  } else if ("handle" in cfg && "password" in cfg) {
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
export async function createRecord(
  agent: AtpAgent,
  did: string,
  collection: string,
  record: unknown,
  rkey?: string
): Promise<{ uri: string; cid: string }> {
  const res = await agent.com.atproto.repo.createRecord({
    repo: did,
    collection,
    rkey,
    // PDS validator requires $type matching the collection NSID
    record: { $type: collection, ...(record as Record<string, unknown>) },
  });
  if (!res.success) {
    throw new Error(
      `[etzhayyim-sdk/pds] createRecord failed: ${JSON.stringify(res)}`
    );
  }
  return { uri: res.data.uri, cid: res.data.cid };
}

/** Read a single record by AT URI components. */
export async function getRecord(
  agent: AtpAgent,
  did: string,
  collection: string,
  rkey: string
): Promise<{ uri: string; cid: string; value: unknown } | null> {
  try {
    const res = await agent.com.atproto.repo.getRecord({
      repo: did,
      collection,
      rkey,
    });
    return res.success
      ? { uri: res.data.uri, cid: res.data.cid as string, value: res.data.value }
      : null;
  } catch (err) {
    const msg = (err as Error)?.message ?? "";
    if (msg.includes("not found") || msg.includes("Record not found")) {
      return null;
    }
    throw err;
  }
}

/** List records in a collection with cursor pagination. */
export async function listRecords(
  agent: AtpAgent,
  did: string,
  collection: string,
  opts: { limit?: number; cursor?: string; reverse?: boolean } = {}
): Promise<{
  records: Array<{ uri: string; cid: string; value: unknown }>;
  cursor?: string;
}> {
  const res = await agent.com.atproto.repo.listRecords({
    repo: did,
    collection,
    limit: opts.limit ?? 50,
    cursor: opts.cursor,
    reverse: opts.reverse,
  });
  if (!res.success) {
    throw new Error(
      `[etzhayyim-sdk/pds] listRecords failed: ${JSON.stringify(res)}`
    );
  }
  return {
    records: res.data.records.map((r) => ({
      uri: r.uri,
      cid: r.cid as string,
      value: r.value,
    })),
    cursor: res.data.cursor,
  };
}

/**
 * Quick PDS liveness check. Returns true if the configured service
 * responds 200 at /xrpc/_health.
 */
export async function health(service?: string): Promise<boolean> {
  const url = `${(service ?? DEFAULT_PDS).replace(/\/+$/, "")}/xrpc/_health`;
  try {
    const res = await fetch(url, { method: "GET" });
    return res.ok;
  } catch {
    return false;
  }
}

/**
 * Resolve a DID to its PDS endpoint via DID document.
 * v0.1: handles did:web only (most common for etzhayyim setups).
 */
export async function resolvePds(did: string): Promise<string> {
  if (did.startsWith("did:web:")) {
    const host = did.slice("did:web:".length).replace(/:/g, "/");
    const url = `https://${host}/.well-known/did.json`;
    const res = await fetch(url);
    if (!res.ok) {
      throw new Error(
        `[etzhayyim-sdk/pds] resolvePds: did.json not reachable at ${url}`
      );
    }
    const doc = (await res.json()) as {
      service?: Array<{ id: string; type: string; serviceEndpoint: string }>;
    };
    const pds = doc.service?.find(
      (s) =>
        s.type === "AtprotoPersonalDataServer" ||
        s.id === "#atproto_pds" ||
        s.id === `${did}#atproto_pds`
    );
    if (!pds) {
      throw new Error(
        `[etzhayyim-sdk/pds] resolvePds: no AtprotoPersonalDataServer service entry in ${url}`
      );
    }
    return pds.serviceEndpoint;
  }
  throw new Error(
    `[etzhayyim-sdk/pds] resolvePds: did method not yet supported: ${did} (v0.1 = did:web only; did:plc next)`
  );
}
