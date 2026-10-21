import { test, expect } from "@playwright/test";

test("phone desk accurately reports an unconfigured line", async ({ page, request }) => {
  expect((await request.get("/api/phone/status")).status()).toBe(401);
  await page.goto("/");
  await page.getByLabel("Username", { exact: true }).fill(process.env.DEMO_USER || "reviewer");
  await page.getByLabel("Password", { exact: true }).fill(process.env.DEMO_PASSWORD || "local-demo-change-me");
  await page.getByRole("button", { name: "Sign in →" }).click();
  await page.getByRole("navigation").getByRole("button", { name: "Phone intake" }).click();
  await expect(page.getByText("Phone line not activated", { exact: true })).toBeVisible();
  await expect(page.getByText("No phone intake recorded yet.", { exact: false })).toBeVisible();
  await page.screenshot({ path: "../docs/phone-intake.png", fullPage: true });
  await page.goto("/complaint");
  await expect(page.locator('a[href^="tel:"]')).toHaveCount(0);
});
