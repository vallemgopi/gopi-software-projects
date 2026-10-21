import React from "react";
import { render, screen } from "@testing-library/react";
import { expect, test, vi } from "vitest";
vi.mock("react-dom/client", () => ({
  createRoot: () => ({ render: vi.fn() }),
}));
import { Confidence } from "./main";
test("missing evidence is explicitly marked for review", () => {
  render(
    <Confidence
      field={{ value: null, confidence: 0, evidence: null, needsReview: true }}
    />,
  );
  expect(screen.getByText(/Needs review/)).toHaveTextContent("0% score");
});
