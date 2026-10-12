// The `.rc` viewer MCP App (`mcp-app/rc-viewer.html`) behind a fake MCP Apps host. Moved from
// compose-preview-server's preview harness with the page; the browser flags are the ones that
// harness uses, so the player draws the same way.
import { defineConfig } from "@playwright/test";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.HARNESS_PORT ?? 5611);

export default defineConfig({
  testDir: ".",
  testMatch: /mcp-app-rc-viewer\.spec\.mjs/,
  outputDir: resolve(here, "test-results"),
  timeout: 60_000,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? "github" : "list",
  use: {
    browserName: "chromium",
    baseURL: `http://127.0.0.1:${PORT}`,
    viewport: { width: 1024, height: 720 },
    trace: "retain-on-failure",
    launchOptions: {
      args: [
        "--enable-unsafe-swiftshader",
        "--use-gl=angle",
        "--disable-partial-raster",
        "--disable-skia-runtime-opts",
      ],
      ...(process.env.HARNESS_CHROMIUM ? { executablePath: process.env.HARNESS_CHROMIUM } : {}),
    },
  },
  webServer: {
    command: "node server.mjs",
    url: `http://127.0.0.1:${PORT}/harness/fixtures/mcp-app-rc-viewer.html`,
    reuseExistingServer: !process.env.CI,
    env: { HARNESS_PORT: String(PORT) },
    stdout: "ignore",
    stderr: "pipe",
  },
});
