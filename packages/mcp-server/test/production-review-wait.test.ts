import { afterEach, describe, expect, it, vi } from "vitest";
import { reviewCursor, waitForReview, waitForExportReview } from "../src/production/review-wait.js";

const draft = {draft_hash:"draft-a",review_submission_version:1,presentation:{state:"visible"}};
const event = (approved:boolean) => ({...draft,review_submission_sequence:1,
  review_submission:{sequence:1,draft_hash:"draft-a",approved,comment:"Eight shots"},
  ...(approved ? {locked_hash:"draft-a"} : {})});
describe("review wait", () => {
  it("consumes delayed export decisions after a timeout or restart and distinguishes Later",async()=>{
    const state={export_decision_version:1,report:{binding:"export"},export_presentation:{state:"closed"}};
    expect(await waitForExportReview(state,"export",0,0,vi.fn())).toMatchObject({export_review_wait:{status:"deferred"}});
    const decision={sequence:1,binding:"export",decision:"rejected"};
    expect(await waitForExportReview({...state,export_decision:decision},"export",0,0,vi.fn())).toMatchObject({export_review_wait:{status:"rejected"}});
    expect(await waitForExportReview({...state,export_decision:{...decision,decision:"accepted"},override:{binding:"export"}},"export",0,0,vi.fn())).toMatchObject({export_review_wait:{status:"accepted"}});
    expect(await waitForExportReview({...state,report:{binding:"new"}},"export",0,0,vi.fn())).toMatchObject({export_review_wait:{status:"superseded"}});
  });
  afterEach(() => vi.useRealTimers());
  it("resumes on a submitted comment without approving", async () => {
    vi.useFakeTimers();
    const read=vi.fn(async()=>event(false));
    const pending=waitForReview(draft,reviewCursor(draft),5000,read);
    await vi.advanceTimersByTimeAsync(500);
    expect(await pending).toMatchObject({review_wait:{status:"commented"},review_submission:{comment:"Eight shots"}});
    expect(read).toHaveBeenCalledTimes(1);
  });
  it("finds approval submitted between timeout and continuation, even after closure", async () => {
    const read=vi.fn();
    const result=await waitForReview({...event(true),presentation:{state:"closed"}},reviewCursor(draft),5000,read);
    expect(result).toMatchObject({review_wait:{status:"approved"},locked_hash:"draft-a"});
    expect(read).not.toHaveBeenCalled();
  });
  it("times out with a reusable cursor without manufacturing authority", async () => {
    vi.useFakeTimers();
    const pending=waitForReview(draft,reviewCursor(draft),1000,async()=>draft);
    await vi.advanceTimersByTimeAsync(1000);
    expect(await pending).toMatchObject({review_wait:{status:"timeout",draft_hash:"draft-a",after_submission_sequence:0}});
    expect(await pending).not.toHaveProperty("locked_hash");
  });
  it("ignores an old submission, rejects replaced drafts, and distinguishes cancel and unsupported", async () => {
    expect(await waitForReview(event(false),reviewCursor(event(false)),0,vi.fn())).toMatchObject({review_wait:{status:"not_waiting"}});
    expect(await waitForReview({...draft,draft_hash:"new"},reviewCursor(draft),5000,vi.fn())).toMatchObject({review_wait:{status:"superseded"}});
    expect(await waitForReview({...draft,presentation:{state:"closed"}},reviewCursor(draft),5000,vi.fn())).toMatchObject({review_wait:{status:"closed"}});
    expect(await waitForReview({},reviewCursor(draft),5000,vi.fn())).toMatchObject({review_wait:{status:"unsupported"}});
  });
  it("does not trust an approval event without its matching persisted lock", async () => {
    expect(await waitForReview({...event(true),locked_hash:"other"},reviewCursor(draft),5000,vi.fn())).toMatchObject({review_wait:{status:"invalid_approval"}});
  });
  it("cancels promptly and propagates disconnect rather than approving", async () => {
    const controller=new AbortController();
    const pending=waitForReview(draft,reviewCursor(draft),5000,vi.fn(),controller.signal);
    controller.abort(); await expect(pending).rejects.toThrow();
    vi.useFakeTimers();
    const disconnected=waitForReview(draft,reviewCursor(draft),5000,async()=>{throw new Error("disconnected");});
    const check=expect(disconnected).rejects.toThrow("disconnected");
    await vi.advanceTimersByTimeAsync(500);await check;
  });
});
