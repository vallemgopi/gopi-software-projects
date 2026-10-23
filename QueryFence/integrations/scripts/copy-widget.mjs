import { copyFileSync, mkdirSync } from "node:fs";
mkdirSync("dist/widget", { recursive: true });
copyFileSync("widget/widget.css", "dist/widget/widget.css");
mkdirSync("dist/examples", { recursive: true });
copyFileSync("examples/widget-host.html", "dist/examples/widget-host.html");
