import { test, expect, chromium } from "@playwright/test";
import path from "node:path";

test("anonymous mobile complaint gets a private receipt and staff can review it", async ({
  page,
  request,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/complaint");
  await expect(
    page.getByRole("heading", { name: "Tell us what happened." }),
  ).toBeVisible();
  await page
    .getByRole("combobox", { name: "Language", exact: true })
    .selectOption("ta");
  await page
    .getByLabel("Your complaint", { exact: true })
    .fill("தண்ணீர் வரவில்லை at Synthetic Public Lane. ID: SYN-PUBLIC.");
  await expect(
    page.getByRole("button", { name: "Send complaint" }),
  ).toBeDisabled();
  await page.getByLabel("I agree to send this complaint").check();
  await page.screenshot({
    path: "../docs/public-complaint.png",
    fullPage: true,
  });
  const response = page.waitForResponse(
    (r) =>
      r.url().endsWith("/api/public/submissions") &&
      r.request().method() === "POST",
  );
  await page.getByRole("button", { name: "Send complaint" }).click();
  const reply = await response;
  expect(reply.status()).toBe(201);
  const receipt = await reply.json();
  expect(receipt.prediction).toBeUndefined();
  expect(receipt.transcript).toBeUndefined();
  await expect(
    page.getByRole("heading", { name: "Your complaint was received." }),
  ).toBeFocused();
  await expect(
    page.getByText(receipt.reference, { exact: true }),
  ).toBeVisible();
  expect((await request.get("/api/cases/" + receipt.reference)).status()).toBe(
    401,
  );
  await page.screenshot({ path: "../docs/public-receipt.png", fullPage: true });
  await page.getByRole("link", { name: "Staff sign in" }).click();
  await page
    .getByLabel("Username", { exact: true })
    .fill(process.env.DEMO_USER || "reviewer");
  await page
    .getByLabel("Password", { exact: true })
    .fill(process.env.DEMO_PASSWORD || "local-demo-change-me");
  await page.getByRole("button", { name: "Sign in →" }).click();
  await page
    .getByRole("navigation")
    .getByRole("button", { name: "Case history" })
    .click();
  await page.getByLabel("Search transcripts").fill("SYN-PUBLIC");
  const row = page
    .locator(".case-row")
    .filter({
      has: page.getByRole("button", {
        name: "Delete case " + receipt.reference.slice(0, 8),
        exact: true,
      }),
    });
  await expect(row).toContainText("Public submission");
  await row.getByRole("button").first().click();
  await page
    .getByLabel("Reviewed location", { exact: true })
    .fill("Reviewed Synthetic Public Lane");
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
  const remove = page.getByRole("button", {
    name: "Delete case " + receipt.reference.slice(0, 8),
    exact: true,
  });
  await remove.click();
  await expect(remove).not.toBeVisible();
});

test("anonymous upload uses real speech and rejects invalid files", async ({
  page,
}) => {
  await page.goto("/complaint");
  await page.getByRole("button", { name: "Record or upload voice" }).click();
  await page
    .getByLabel("Or upload a WAV clip")
    .setInputFiles({
      name: "invalid.txt",
      mimeType: "text/plain",
      buffer: Buffer.from("not audio"),
    });
  await expect(page.getByRole("alert")).toContainText("PCM WAV");
  await page
    .getByLabel("Or upload a WAV clip")
    .setInputFiles(path.resolve("../ml/data/synthetic-silence.wav"));
  await page.getByLabel("I agree to send this complaint").check();
  await page.getByRole("button", { name: "Send complaint" }).click();
  await expect(page.getByRole("alert")).toContainText("No speech detected", {
    timeout: 30000,
  });
  await expect(
    page.getByRole("heading", { name: "Your complaint was received." }),
  ).not.toBeVisible();
});

test("microphone permission errors are actionable", async ({ page }) => {
  await page.goto("/complaint");
  await page.evaluate(() => {
    navigator.mediaDevices.getUserMedia = async () => {
      throw new DOMException("Denied", "NotAllowedError");
    };
  });
  await page.getByRole("button", { name: "Record or upload voice" }).click();
  await page.getByRole("button", { name: "Start recording" }).click();
  await expect(page.getByRole("alert")).toContainText(
    "Microphone access was denied",
  );
});

test("browser microphone recording converts to a previewable WAV", async () => {
  const browser = await chromium.launch({
    args: [
      "--use-fake-device-for-media-stream",
      "--use-fake-ui-for-media-stream",
    ],
  });
  const context = await browser.newContext({ permissions: ["microphone"] });
  try {
    const page = await context.newPage();
    await page.goto(
      (process.env.BASE_URL || "http://localhost:5173") + "/complaint",
    );
    await page.getByRole("button", { name: "Record or upload voice" }).click();
    await page.getByRole("button", { name: "Start recording" }).click();
    await expect(
      page.getByRole("status").filter({ hasText: "Recording" }),
    ).toBeVisible();
    await expect(
      page.getByRole("status").filter({ hasText: "Recording" }),
    ).toContainText("2s", { timeout: 10000 });
    await page.getByRole("button", { name: "Stop recording" }).click();
    await expect(
      page.getByRole("status").filter({ hasText: "Voice clip ready" }),
    ).toContainText("recorded-complaint.wav");
    const media = await page
      .locator("audio")
      .evaluate(async (audio: HTMLAudioElement) => {
        const data = await (await fetch(audio.src)).arrayBuffer();
        const v = new DataView(data);
        return {
          magic: String.fromCharCode(...new Uint8Array(data, 0, 4)),
          sampleRate: v.getUint32(24, true),
          length: data.byteLength,
        };
      });
    expect(media.magic).toBe("RIFF");
    expect(media.sampleRate).toBe(16000);
    expect(media.length).toBeGreaterThan(44);
  } finally {
    await context.close();
    await browser.close();
  }
});
