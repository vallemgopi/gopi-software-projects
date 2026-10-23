import { readFileSync } from "node:fs";
import { createInterface } from "node:readline/promises";
import { stdin, stdout } from "node:process";
import { QueryFenceClient } from "../client/queryfence.js";
const file = process.env.QUERYFENCE_TOKEN_FILE;
if (!file)
  throw new Error(
    "Set QUERYFENCE_TOKEN_FILE to an ignored owner-issued credential file.",
  );
const token = JSON.parse(readFileSync(file, "utf8")).token;
const client = new QueryFenceClient({
  apiUrl: process.env.QUERYFENCE_API_URL || "http://localhost:18080/api/v2",
  getAccessToken: () => token,
});
console.log(JSON.stringify(await client.schema(), null, 2));
const review = await client.plan("Revenue by category last 30 days");
console.log(JSON.stringify(review, null, 2));
if (review.status === "BLOCKED" || review.status === "CLARIFICATION_REQUIRED")
  throw new Error("Resolve clarification or budget block before approval.");
// A synchronous read for redirected stdin avoids Windows libuv/readline shutdown bugs.
let confirmation: string;
if (stdin.isTTY) {
  const terminal = createInterface({ input: stdin, output: stdout });
  try {
    confirmation = await terminal.question(
      "Review the plan above. Type APPROVE to run this synthetic SELECT: ",
    );
  } finally {
    terminal.close();
  }
} else {
  stdout.write(
    "Review the plan above. Type APPROVE to run this synthetic SELECT:\n",
  );
  confirmation = readFileSync(0, "utf8").trim();
}
if (confirmation !== "APPROVE") process.exit(0);
const approval = await client.approve(
  String(review.planId),
  String(review.reviewDigest),
  true,
);
const result = await client.execute(
  String(review.planId),
  approval.approvalToken,
  true,
);
console.log(JSON.stringify(result, null, 2));
console.log(
  JSON.stringify(await client.results(String(review.planId), true), null, 2),
);
