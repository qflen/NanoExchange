import React from "react";
import ReactDOM from "react-dom/client";
import { App } from "./App";
import { ExchangeProvider } from "./state/ExchangeContext";
import "./index.css";

const WS_URL =
  (import.meta.env.VITE_WS_URL as string | undefined) ?? "ws://127.0.0.1:8765";

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <ExchangeProvider wsUrl={WS_URL}>
      <App />
    </ExchangeProvider>
  </React.StrictMode>,
);
