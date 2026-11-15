// Membuat ../app/src/main/assets/editor.html (satu file, offline) dari main.js + template.html
import { build } from "esbuild";
import fs from "fs";

const r = await build({
  entryPoints: ["main.js"],
  bundle: true,
  minify: true,
  format: "iife",
  target: ["chrome90"],
  write: false
});
const js = r.outputFiles[0].text.replace(/<\/script/gi, "<\\/script");
const tpl = fs.readFileSync("template.html", "utf8");
fs.mkdirSync("../app/src/main/assets", { recursive: true });
fs.writeFileSync("../app/src/main/assets/editor.html", tpl.replace("/*__BUNDLE__*/", () => js));
console.log("OK editor.html", Math.round(fs.statSync("../app/src/main/assets/editor.html").size / 1024) + " KB");
