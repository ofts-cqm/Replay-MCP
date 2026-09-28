import {it,expect} from "vitest";
import {receiptCollision} from "../src/production/store.js";
it("retires old false-positive collision receipts without converting skips to proof",()=>{
  for(const policy of [undefined,"native-linear-frozen-sweep/1","native-linear-frozen-sweep/2"])
    expect(receiptCollision({collision:"verified",clearance_evidence:{policy,collision_check:"verified"}})).toBe("unverified");
  const evidence={policy:"native-linear-frozen-sweep/3",collision_check:"verified"};
  expect(receiptCollision({collision:"verified",clearance_evidence:evidence})).toBe("verified");
  expect(receiptCollision({collision:"skipped",clearance_evidence:evidence})).toBe("skipped");
  expect(receiptCollision({collision:"verified",clearance_evidence:{...evidence,collision_check:"blocked"}})).toBe("unverified");
});
it("accepts advancing camera proof only with supported complete temporal history",()=>{
 const proof={policy:"native-linear-packet-static-sweep/1",collision_check:"verified"};
 expect(receiptCollision({collision:"verified",clearance_evidence:proof})).toBe("unverified");
 expect(receiptCollision({collision:"verified",clearance_evidence:{...proof,temporal_history:{policy:"packet-static-world/1",verified:true}}})).toBe("verified");
 expect(receiptCollision({collision:"verified",clearance_evidence:{...proof,temporal_history:{policy:"future-policy",verified:true}}})).toBe("unverified");
});
