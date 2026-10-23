// Explicit, reproducible fixture generation; expected plans are independent of planner code.
import { writeFileSync } from "node:fs";
const cases = [];
const metrics = [
  ["Revenue", "REVENUE"],
  ["Order count", "ORDER_COUNT"],
  ["Return rate", "RETURN_RATE"],
];
const dimensions = ["MONTH", "CATEGORY", "REGION", "STATUS"];
const windows = [
  ["all time", "ALL_TIME"],
  ["last 7 days", "LAST_7_DAYS"],
  ["last 30 days", "LAST_30_DAYS"],
  ["last 90 days", "LAST_90_DAYS"],
];
for (const [word, metric] of metrics)
  for (const dimension of dimensions)
    for (const [phrase, timeWindow] of windows) {
      const n = cases.length;
      const category = n % 3 === 0 ? "ELECTRONICS" : null;
      const region = n % 4 === 0 ? "NORTH" : null;
      const limit = n % 5 === 0 ? 5 : 20;
      cases.push({
        id: `valid-${n + 1}`,
        kind: "valid",
        question: `${n % 2 ? "Please show me the " : ""}${word} by ${dimension.toLowerCase()} ${phrase}${category ? " for electronics" : ""}${region ? " in north" : ""}${limit === 5 ? " top 5" : ""}`,
        expected: { metric, dimension, timeWindow, category, region, limit },
      });
    }
for (const question of [
  "Sales by region",
  "How many orders by category last 7 days",
  "Number of orders by status for home",
  "What is the return rate by month?",
]) {
  const metric = question.startsWith("Sales")
    ? "REVENUE"
    : question.includes("return")
      ? "RETURN_RATE"
      : "ORDER_COUNT";
  const dimension = question.includes("region")
    ? "REGION"
    : question.includes("category")
      ? "CATEGORY"
      : question.includes("status")
        ? "STATUS"
        : "MONTH";
  cases.push({
    id: `variant-${cases.length}`,
    kind: "valid",
    question,
    expected: {
      metric,
      dimension,
      timeWindow: question.includes("7 days") ? "LAST_7_DAYS" : "ALL_TIME",
      category: question.includes("home") ? "HOME" : null,
      region: null,
      limit: 20,
    },
  });
}
for (const [kind, questions] of Object.entries({
  ambiguous: [
    "How is the business doing?",
    "Revenue",
    "Revenue and order count by region",
    "Revenue by region yesterday",
  ],
  out_of_scope: [
    "Show customer email addresses",
    "Forecast revenue by region",
    "Profit by category",
    "Delete all orders",
    "Weather in London",
    "Salary by region",
  ],
}))
  for (const question of questions)
    cases.push({
      id: `reject-${cases.length}`,
      kind,
      question,
      expected: null,
    });
writeFileSync(
  new URL("./benchmark.json", import.meta.url),
  JSON.stringify(cases, null, 2) + "\n",
);
console.log(`Wrote ${cases.length} benchmark questions.`);
