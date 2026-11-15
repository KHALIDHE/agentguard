import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// The dashboard talks to the gateway (login, admin) and to the demo agent (chat) through one address.
const GATEWAY = process.env.GATEWAY_URL || "http://localhost:8080";
const AGENT = process.env.AGENT_URL || "http://localhost:8082";

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      "/auth": GATEWAY,
      "/admin": GATEWAY,
      "/agent": { target: AGENT, rewrite: (path) => path.replace(/^\/agent/, "") },
    },
  },
});
