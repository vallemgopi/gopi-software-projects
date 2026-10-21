import { test, expect, type Page } from "@playwright/test";
import path from "node:path";
async function capture(page: Page, name: string) {
  await expect(
    page.getByRole("status").filter({ hasText: /^Working/ }),
  ).not.toBeVisible();
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: "../docs/" + name + ".png", fullPage: true });
}
test("create, review, evaluate and delete a synthetic case", async ({
  page,
}) => {
  await page.goto("/");
  await page
    .getByLabel("Username", { exact: true })
    .fill(process.env.DEMO_USER || "reviewer");
  await page
    .getByLabel("Password", { exact: true })
    .fill(process.env.DEMO_PASSWORD || "local-demo-change-me");
  await page.getByRole("button", { name: "Sign in →" }).click();
  await page
    .getByRole("heading", { name: "Reliability, made visible." })
    .waitFor();
  await capture(page, "dashboard");
  await page
    .getByRole("navigation")
    .getByRole("button", { name: "New case" })
    .click();
  await page
    .getByRole("button", { name: "Use synthetic sample transcript" })
    .click();
  const creation = page.waitForResponse(
    (r) => r.url().endsWith("/api/cases") && r.request().method() === "POST",
  );
  await page.getByRole("button", { name: "Extract & review" }).click();
  const created = await (await creation).json();
  await page.getByLabel("Reviewed location", { exact: true }).waitFor();
  await capture(page, "review");
  await page
    .getByLabel("Reviewed location", { exact: true })
    .fill("Corrected Demo Colony");
  await page.getByLabel("I reviewed all fields").check();
  await page.getByRole("button", { name: "Save reviewed case" }).click();
  await expect(
    page.getByRole("status").filter({ hasText: "Review saved" }),
  ).toBeVisible();
  await page
    .getByRole("navigation")
    .getByRole("button", { name: "Evaluation" })
    .click();
  await page
    .getByRole("button", { name: "Run reproducible evaluation" })
    .click();
  await expect(
    page.getByRole("heading", { name: "Exact match by field" }),
  ).toBeVisible();
  const downloaded = page.waitForEvent("download");
  await page.getByRole("button", { name: "Export report JSON" }).click();
  expect((await downloaded).suggestedFilename()).toBe("evaluation.json");
  await capture(page, "evaluation");
  await page
    .getByRole("navigation")
    .getByRole("button", { name: "Case history" })
    .click();
  await page.getByLabel("Search transcripts").fill("Paani nahi aa raha");
  page.once("dialog", (dialog) => dialog.dismiss());
  const deleteButton = page.getByRole("button", {
    name: "Delete case " + created.id.slice(0, 8),
    exact: true,
  });
  await deleteButton.click();
  await expect(deleteButton).toBeVisible();
  page.once("dialog", (dialog) => dialog.accept());
  await deleteButton.click();
  await expect(deleteButton).not.toBeVisible();
});
test("invalid credentials show an accessible API error", async ({ page }) => {
  await page.goto("/");
  await page
    .getByLabel("Password", { exact: true })
    .fill("incorrect-test-password");
  await page.getByRole("button", { name: "Sign in →" }).click();
  await expect(page.getByRole("alert")).toContainText(
    "Authentication required",
  );
  await expect(
    page.getByRole("heading", { name: "Enter the reliability lab" }),
  ).toBeVisible();
});
test("sample audio is clearly labeled and supports a mobile review", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");
  await page
    .getByLabel("Username", { exact: true })
    .fill(process.env.DEMO_USER || "reviewer");
  await page
    .getByLabel("Password", { exact: true })
    .fill(process.env.DEMO_PASSWORD || "local-demo-change-me");
  await page.getByRole("button", { name: "Sign in →" }).click();
  await page
    .getByRole("navigation")
    .getByRole("button", { name: "New case" })
    .click();
  await page
    .getByRole("button", { name: "Use synthetic sample transcript" })
    .click();
  await page
    .getByLabel("Optional audio clip")
    .setInputFiles(path.resolve("../ml/data/synthetic-silence.wav"));
  await page
    .getByRole("combobox", { name: "Speech provider", exact: true })
    .selectOption("sample");
  const creation = page.waitForResponse(
    (r) =>
      r.url().endsWith("/api/cases/audio") && r.request().method() === "POST",
  );
  await page.getByRole("button", { name: "Extract & review" }).click();
  const created = await (await creation).json();
  await expect(
    page.getByText(/DEMO: supplied transcript, not speech recognition/),
  ).toBeVisible();
  await capture(page, "mobile-review");
  await page.getByLabel("I reviewed all fields").check();
  await page.getByRole("button", { name: "Save reviewed case" }).click();
  await expect(
    page.getByText("Review saved. Original prediction preserved."),
  ).toBeVisible();
  await page
    .getByRole("navigation")
    .getByRole("button", { name: "Case history" })
    .click();
  page.once("dialog", (d) => d.accept());
  const deleteButton = page.getByRole("button", {
    name: "Delete case " + created.id.slice(0, 8),
    exact: true,
  });
  await deleteButton.click();
  await expect(deleteButton).not.toBeVisible();
});
for (const language of ["ta", "te", "kn", "ml"]) {
  test(`native ${language} capture preserves script and review labels`, async ({
    page,
  }) => {
    await page.goto("/");
    await page
      .getByLabel("Username", { exact: true })
      .fill(process.env.DEMO_USER || "reviewer");
    await page
      .getByLabel("Password", { exact: true })
      .fill(process.env.DEMO_PASSWORD || "local-demo-change-me");
    await page.getByRole("button", { name: "Sign in →" }).click();
    await page
      .getByRole("navigation")
      .getByRole("button", { name: "New case" })
      .click();
    await page.getByLabel("Speech / sample language").selectOption(language);
    await page
      .getByRole("button", { name: "Use synthetic sample transcript" })
      .click();
    const creation = page.waitForResponse(
      (r) => r.url().endsWith("/api/cases") && r.request().method() === "POST",
    );
    await page.getByRole("button", { name: "Extract & review" }).click();
    const created = await (await creation).json();
    expect(created.prediction.languageHints).toContain(language);
    await capture(page, 'review-' + language);
    await page.getByLabel("I reviewed all fields").check();
    await page.getByRole("button", { name: "Save reviewed case" }).click();
    await expect(
      page.getByRole("status").filter({ hasText: "Review saved" }),
    ).toBeVisible();
    await page
      .getByRole("navigation")
      .getByRole("button", { name: "Case history" })
      .click();
    page.once("dialog", (d) => d.accept());
    const button = page.getByRole("button", {
      name: "Delete case " + created.id.slice(0, 8),
      exact: true,
    });
    await button.click();
    await expect(button).not.toBeVisible();
  });
}
