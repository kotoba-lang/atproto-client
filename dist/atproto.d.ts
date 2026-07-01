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
export { AtpAgent, AtpBaseClient } from "@atproto/api";
export type { AppBskyActorDefs, AppBskyFeedDefs, AppBskyRichtextFacet, } from "@atproto/api";
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
export declare function createAgent(opts: CreateAgentOpts): AtpAgent;
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
export declare function xrpc<T = unknown>(agent: AtpAgent, method: "GET" | "POST", nsid: string, body?: Record<string, unknown>, opts?: XrpcOpts): Promise<T>;
