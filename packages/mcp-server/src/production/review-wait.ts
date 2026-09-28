import { setTimeout as delay } from "node:timers/promises";

type State = Record<string, unknown>;
export type ReviewCursor = { draft_hash: string; after_submission_sequence: number };
const object = (value: unknown): State => value !== null && typeof value === "object" ? value as State : {};
export function reviewCursor(state: State): ReviewCursor {
  return { draft_hash: String(state.draft_hash ?? ""), after_submission_sequence: Number(state.review_submission_sequence ?? 0) };
}

/** Polls only inside the sidecar: no model calls, reopened screens, or synthetic decisions. */
export async function waitForReview(initial: State, cursor: ReviewCursor, timeoutMs: number,
  read: (signal?: AbortSignal) => Promise<State>, signal?: AbortSignal): Promise<State> {
  const deadline = Date.now() + timeoutMs;
  let state = initial;
  const done = (status: string) => ({ ...state, review_wait: { status, ...cursor } });
  while (true) {
    signal?.throwIfAborted();
    if (state.review_submission_version !== 1) return done("unsupported");
    if (state.draft_hash !== cursor.draft_hash) return done("superseded");
    const event = object(state.review_submission);
    if (Number(event.sequence ?? 0) > cursor.after_submission_sequence && event.draft_hash === cursor.draft_hash) {
      if (event.approved === true && state.locked_hash !== cursor.draft_hash) return done("invalid_approval");
      return done(event.approved === true ? "approved" : "commented");
    }
    if (state.locked_hash === cursor.draft_hash) return done("already_approved");
    const presentation = object(state.presentation).state;
    if (presentation === "closed" || presentation === "blocked" || presentation === "not_requested") return done(String(presentation));
    if (timeoutMs === 0) return done("not_waiting");
    const remaining = deadline - Date.now();
    if (remaining <= 0) return done("timeout");
    await delay(Math.min(500, remaining), undefined, signal ? { signal } : {});
    state = await read(signal);
  }
}

export async function waitForExportReview(initial: State, binding: string, afterSequence: number, timeoutMs: number,
  read: (signal?: AbortSignal) => Promise<State>, signal?: AbortSignal): Promise<State> {
  const deadline=Date.now()+timeoutMs;
  let state=initial;
  const done=(status:string)=>({...state,export_review_wait:{status,binding,after_decision_sequence:afterSequence}});
  while(true) {
    signal?.throwIfAborted();
    if(state.export_decision_version!==1) return done("unsupported");
    if(object(state.report).binding!==binding) return done("superseded");
    const event=object(state.export_decision);
    if(event.binding===binding && Number(event.sequence??0)>afterSequence) {
      if(event.decision==="rejected") return done("rejected");
      if(event.decision==="accepted" && object(state.override).binding===binding) return done("accepted");
      return done("invalid_decision");
    }
    if(object(state.override).binding===binding) return done("accepted");
    const presentation=object(state.export_presentation).state;
    if(presentation==="closed") return done("deferred");
    if(presentation==="blocked" || presentation==="not_requested") return done(String(presentation));
    if(timeoutMs===0) return done("not_waiting");
    const remaining=deadline-Date.now(); if(remaining<=0) return done("timeout");
    await delay(Math.min(500,remaining),undefined,signal?{signal}:{});
    state=await read(signal);
  }
}
