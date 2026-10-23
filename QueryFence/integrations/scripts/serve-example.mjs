import { createServer } from "node:http";
import { readFile, stat } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { resolve, extname, sep } from "node:path";
const root = fileURLToPath(new URL("../dist/", import.meta.url));
const types = {
  ".js": "text/javascript",
  ".css": "text/css",
  ".html": "text/html",
  ".json": "application/json",
};
createServer(async (req, res) => {
  try {
    const pathname = decodeURIComponent(
      new URL(req.url, "http://localhost").pathname,
    );
    const target = resolve(root, "." + pathname);
    if (
      !target.startsWith(resolve(root) + sep) ||
      !(await stat(target)).isFile()
    ) {
      res.writeHead(404);
      res.end("Not found");
      return;
    }
    res.setHeader(
      "Content-Type",
      types[extname(target)] || "application/octet-stream",
    );
    res.setHeader("X-Content-Type-Options", "nosniff");
    res.setHeader("Referrer-Policy", "no-referrer");
    res.end(await readFile(target));
  } catch {
    res.writeHead(404);
    res.end("Not found");
  }
}).listen(Number(process.env.EXAMPLE_PORT || 8085), "127.0.0.1", () =>
  console.log(
    "Example host: http://localhost:" +
      (process.env.EXAMPLE_PORT || 8085) +
      "/examples/widget-host.html",
  ),
);
