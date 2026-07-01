/**
 * @etzhayyim/sdk/atproto — AT Protocol client re-exports + helpers.
 *
 * Per ADR-2605172000 substrate-boundary rules, apps must import AT Protocol
 * types and utilities through this facade, not directly from @atproto/api.
 *
 * Exports:
 * - Client: AtpAgent, AtpBaseClient
 * - Bluesky Lexicon types: AppBskyActorDefs, AppBskyFeedDefs, AppBskyRichtextFacet
 * - Helper: createAgent(opts), xrpc(agent, method, nsid, body, opts?)
 */

// ─── Client Re-exports ──────────────────────────────────────────────────

export { AtpAgent, AtpBaseClient } from "@atproto/api";

// ─── Bluesky Lexicon Type Re-exports ────────────────────────────────────

export type {
  AppBskyActorDefs,
  AppBskyFeedDefs,
  AppBskyRichtextFacet,
} from "@atproto/api";

// ─── Agent Factory ──────────────────────────────────────────────────────

import { AtpAgent } from "@atproto/api";

export interface CreateAgentOpts {
  /** AT Protocol PDS service endpoint. */
  service: string;
  /** Optional custom headers to inject into all XRPC calls. */
  headers?: Record<string, string>;
}

/**
 * Factory to create an AtpAgent with optional custom headers.
 * Wraps new AtpAgent({ service }) and configures header injection.
 *
 * Example:
 *   const agent = await createAgent({
 *     service: 'https://pds.etzhayyim.com',
 *     headers: { 'atproto-proxy': 'did:web:yoro.etzhayyim.com#appview' }
 *   });
 */
export function createAgent(opts: CreateAgentOpts): AtpAgent {
  const agent = new AtpAgent({ service: opts.service });

  // If custom headers are provided, inject them into the agent's HTTP layer.
  // This wraps the underlying XrpcClient to add headers to every request.
  if (opts.headers && Object.keys(opts.headers).length > 0) {
    const origApi = agent.api;
    const customHeaders = opts.headers;

    // Create a wrapper around the api.call method to inject headers.
    const origCall = origApi.call.bind(origApi);
    agent.api.call = async function (
      nsid: string,
      args?: Record<string, unknown>,
      opts?: { encoding?: string; timeout?: number; headers?: Record<string, string> }
    ) {
      const mergedOpts = {
        ...opts,
        headers: {
          ...customHeaders,
          ...(opts?.headers ?? {}),
        },
      };
      return origCall(nsid, args, mergedOpts);
    } as typeof origApi.call;
  }

  return agent;
}

// ─── XRPC Helper ────────────────────────────────────────────────────────

export interface XrpcOpts {
  /** Custom headers to inject (e.g., atproto-proxy, org-id). */
  headers?: Record<string, string>;
  /** Request timeout in milliseconds. */
  timeout?: number;
  /** Encoding for request body. */
  encoding?: string;
}

/**
 * Generic XRPC call helper that wraps agent.api.call() with custom headers.
 * Handles both GET queries and POST procedures.
 *
 * Example:
 *   const result = await xrpc(agent, 'POST', 'com.atproto.repo.createRecord', {
 *     repo: did,
 *     collection: 'app.bsky.feed.post',
 *     record: { text: 'Hello', createdAt: new Date().toISOString(), $type: 'app.bsky.feed.post' }
 *   }, {
 *     headers: { 'atproto-proxy': 'did:web:yoro.etzhayyim.com#appview' }
 *   });
 */
export async function xrpc<T = unknown>(
  agent: AtpAgent,
  method: "GET" | "POST",
  nsid: string,
  body?: Record<string, unknown>,
  opts?: XrpcOpts
): Promise<T> {
  const callOpts = {
    headers: opts?.headers,
    timeout: opts?.timeout,
    encoding: opts?.encoding,
  };

  // For GET, body parameters become URL query params.
  // The agent's api.call handles this distinction internally.
  const response = await agent.api.call(nsid, body, callOpts);

  if (!response.success) {
    throw new Error(
      `[etzhayyim-sdk] xrpc ${nsid} failed: ${JSON.stringify(response)}`
    );
  }

  return response.data as T;
}
