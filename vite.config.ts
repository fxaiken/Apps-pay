import { defineConfig } from "vite";

// Relative base so the same build works on GitHub Pages (sub-path) and inside the Android app.
export default defineConfig({
  base: "./",
});
